package com.github.h3yow.loanservicingjava.service;

import com.github.h3yow.loanservicingjava.domain.event.DomainEvent;
import com.github.h3yow.loanservicingjava.domain.event.EventType;
import com.github.h3yow.loanservicingjava.domain.loan.LedgerEntry;
import com.github.h3yow.loanservicingjava.domain.loan.Loan;
import com.github.h3yow.loanservicingjava.domain.money.Money;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalCommand;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalOutcome;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalResult;
import com.github.h3yow.loanservicingjava.repo.EventStore;
import com.github.h3yow.loanservicingjava.repo.IdempotencyStore;
import com.github.h3yow.loanservicingjava.repo.LoanRepository;
import com.github.h3yow.loanservicingjava.repo.ReversalIdempotencyRecord;
import com.github.h3yow.loanservicingjava.repo.ReversalIdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 已受理还款撤销/冲正应用服务。
 *
 * <p>并发/幂等协议与还款受理一致，锁顺序固定为“撤销请求维度 CAS → 贷款锁”：
 * <ol>
 *   <li>先在撤销键维度做 CAS 登记（putIfAbsent PROCESSING），同一撤销键全局只有一个候选处理者；</li>
 *   <li>再获取贷款级分段锁，锁内复查撤销幂等记录与<b>贷款台账</b>，区分
 *       REPLAYED / CONFLICT_CONCURRENT / REJECTED_*；</li>
 *   <li>真正回退走 {@link Loan#reversePayment}（事件重放，天然不透支、不破坏恒等式）；</li>
 *   <li>处理失败回滚 PROCESSING 占位；成功在锁内固化 COMPLETED + 首次结果快照，重放绝不重复退账。</li>
 * </ol>
 * “同一笔还款被不同撤销键重复/并发撤销”由台账中的 reversedBy 标记拦截为
 * REJECTED_ALREADY_REVERSED（或赢家持锁期间的 CONFLICT_CONCURRENT）。
 * 任何异常输入都返回可区分业务结果，不抛服务端错误，也不会看起来成功。
 */
@Service
public class ReversalService {

    private static final Logger log = LoggerFactory.getLogger(ReversalService.class);

    private final LoanRepository loanRepository;
    private final IdempotencyStore idempotencyStore;
    private final ReversalIdempotencyStore reversalIdempotencyStore;
    private final EventStore eventStore;

    public ReversalService(LoanRepository loanRepository,
                           IdempotencyStore idempotencyStore,
                           ReversalIdempotencyStore reversalIdempotencyStore,
                           EventStore eventStore) {
        this.loanRepository = loanRepository;
        this.idempotencyStore = idempotencyStore;
        this.reversalIdempotencyStore = reversalIdempotencyStore;
        this.eventStore = eventStore;
    }

    /** 撤销一笔已成功受理的还款。结果可区分：成功 / 重放 / 并发冲突 / 各类拒绝。 */
    public ReversalResult reverse(ReversalCommand command) {
        // 0) 参数自检：空键/空日期等非法输入返回业务结果，而不是 NPE/500
        ReversalResult invalid = validate(command);
        if (invalid != null) {
            return invalid;
        }
        log.info("[撤销请求] reversalRequestId={}, loanId={}, paymentRequestId={}, asOf={}",
                command.reversalRequestId(), command.loanId(),
                command.paymentRequestId(), command.asOf());

        // 1) 撤销请求维度 CAS 占位
        ReversalIdempotencyRecord mine = ReversalIdempotencyRecord.processing(
                command.reversalRequestId(), command.loanId(),
                command.paymentRequestId(), command.fingerprint());
        Optional<ReversalIdempotencyRecord> existingAtCas =
                reversalIdempotencyStore.putIfAbsent(mine);
        boolean candidateOwner = existingAtCas.isEmpty();

        // 2) 统一进入贷款锁后复查最新撤销记录再决策（贷款不存在时 readLocked 返回 null）
        ReversalResult result = loanRepository.readLocked(command.loanId(), loan -> {
            ReversalIdempotencyRecord latest =
                    reversalIdempotencyStore.find(command.reversalRequestId()).orElse(null);
            if (!candidateOwner) {
                return resolveDuplicate(command, latest, loan);
            }
            return processAsOwner(command, loan);
        });
        if (result != null) {
            return result;
        }
        // readLocked 对不存在的贷款返回 null：候选者回滚占位并返回可区分业务结果
        if (candidateOwner) {
            reversalIdempotencyStore.removeProcessing(command.reversalRequestId());
        }
        return finishReject(command, null, ReversalOutcome.REJECTED_LOAN_NOT_FOUND,
                "贷款不存在: " + command.loanId());
    }

    private ReversalResult validate(ReversalCommand command) {
        if (command == null) {
            return invalidResult(null, null, null, null, "撤销请求不能为空");
        }
        String revId;
        String loanId;
        String paymentId;
        LocalDate asOf;
        try {
            revId = command.reversalRequestId();
            loanId = command.loanId();
            paymentId = command.paymentRequestId();
            asOf = command.asOf();
        } catch (NullPointerException ex) {
            return invalidResult(null, null, null, null,
                    "撤销请求缺少必填字段（reversalRequestId/loanId/paymentRequestId/asOf）");
        }
        if (revId == null || revId.isBlank()) {
            return invalidResult(revId, loanId, paymentId, asOf, "reversalRequestId 不能为空");
        }
        if (loanId == null || loanId.isBlank()) {
            return invalidResult(revId, loanId, paymentId, asOf, "loanId 不能为空");
        }
        if (paymentId == null || paymentId.isBlank()) {
            return invalidResult(revId, loanId, paymentId, asOf, "paymentRequestId 不能为空");
        }
        if (asOf == null) {
            return invalidResult(revId, loanId, paymentId, asOf, "asOf 业务日期不能为空");
        }
        return null;
    }

    private ReversalResult processAsOwner(ReversalCommand command, Loan loan) {
        String revId = command.reversalRequestId();
        try {
            if (loan == null) {
                reversalIdempotencyStore.removeProcessing(revId);
                return finishReject(command, null, ReversalOutcome.REJECTED_LOAN_NOT_FOUND,
                        "贷款不存在: " + command.loanId());
            }

            // 台账校验：目标必须是该贷款上一笔成功的“客户还款”，且尚未被撤销
            Optional<LedgerEntry> entry = loan.findLedgerEntry(command.paymentRequestId());
            if (entry.isEmpty()) {
                reversalIdempotencyStore.removeProcessing(revId);
                String hint = idempotencyStore.find(command.paymentRequestId())
                        .map(r -> "（该 requestId 存在但属于贷款 " + r.loanId()
                                + "，或未成功受理）")
                        .orElse("（系统中不存在该还款 requestId）");
                return finishReject(command, loan, ReversalOutcome.REJECTED_PAYMENT_NOT_FOUND,
                        "目标还款不存在或未成功受理，paymentRequestId="
                                + command.paymentRequestId() + hint);
            }
            if (entry.get().kind() != LedgerEntry.Kind.PAYMENT) {
                reversalIdempotencyStore.removeProcessing(revId);
                return finishReject(command, loan, ReversalOutcome.REJECTED_PAYMENT_NOT_REVERSIBLE,
                        "目标动作是挂账冲抵而非客户还款，不能按还款撤销，requestId="
                                + command.paymentRequestId());
            }
            Optional<String> priorReversal = loan.reversalOf(command.paymentRequestId());
            if (priorReversal.isPresent()) {
                reversalIdempotencyStore.removeProcessing(revId);
                return finishReject(command, loan, ReversalOutcome.REJECTED_ALREADY_REVERSED,
                        "该还款已被撤销，首次撤销 reversalRequestId=" + priorReversal.get()
                                + "；本次不重复退账");
            }

            // 真正回退：事件重放。重放机制保证不透支、恒等式成立，因此走到这里的撤销总能安全完成。
            Money refund = loan.reversePayment(
                    command.paymentRequestId(), revId, command.asOf());

            ReversalResult result = buildResult(command, loan, refund,
                    ReversalOutcome.REVERSED,
                    "撤销成功：按事件重放回退，退款金额=" + refund
                            + "（含原入账与原挂账），状态/账龄已重算", false);
            reversalIdempotencyStore.complete(revId, result);
            eventStore.appendAll(loan.drainEvents());
            log.info("[撤销完成] revId={}, paymentRequestId={}, refund={}, unpaidAfter(P/I/N)={}/{}/{}, status={}",
                    revId, command.paymentRequestId(), refund,
                    loan.unpaidPrincipal(), loan.unpaidInterest(),
                    loan.unpaidPenalty(), loan.getStatus());
            return result;
        } catch (RuntimeException ex) {
            // 临界区内发生意外：回滚占位，且不固化任何“成功”结果；贷款状态未提交则保持原样
            reversalIdempotencyStore.removeProcessing(revId);
            log.error("[撤销异常] revId={}, paymentRequestId={}, 已回滚占位: {}",
                    revId, command.paymentRequestId(), ex.toString());
            throw ex;
        }
    }

    private ReversalResult resolveDuplicate(ReversalCommand command,
                                            ReversalIdempotencyRecord latest, Loan loan) {
        if (latest == null) {
            // 理论不可达：CAS 已见记录；防御性按并发冲突处理，不退账
            return finishReject(command, loan, ReversalOutcome.CONFLICT_CONCURRENT,
                    "撤销幂等状态在锁内复查时缺失，按并发冲突处理，本次不退账");
        }
        if (!latest.fingerprint().equals(command.fingerprint())) {
            return finishReject(command, loan, ReversalOutcome.REJECTED_IDEMPOTENCY_MISMATCH,
                    "reversalRequestId=" + command.reversalRequestId() + " 已用于不同的贷款/还款，"
                            + "已有指纹=" + latest.fingerprint() + "，本次指纹=" + command.fingerprint());
        }
        if (latest.state() == ReversalIdempotencyRecord.State.PROCESSING) {
            return finishReject(command, loan, ReversalOutcome.CONFLICT_CONCURRENT,
                    "同一撤销请求正在处理中，本次不退账", false);
        }
        ReversalResult first = latest.result();
        log.info("[撤销重放] revId={}, 返回首次结果 firstOutcome={}, 不重复退账",
                command.reversalRequestId(), first.outcome());
        ReversalResult replayed = new ReversalResult(first.reversalRequestId(), first.loanId(),
                first.paymentRequestId(), ReversalOutcome.REPLAYED,
                "[重放] 首次撤销结果=" + first.outcome() + "；" + first.message(),
                Money.ZERO, first.suspenseBalanceAfter(),
                first.unpaidPrincipalAfter(), first.unpaidInterestAfter(),
                first.unpaidPenaltyAfter(), first.loanStatusAfter(),
                first.businessDate(), true);
        eventStore.append(DomainEvent.builder(EventType.REVERSAL_REPLAYED)
                .loanId(command.loanId())
                .businessDate(command.asOf())
                .requestId(command.reversalRequestId())
                .summary("撤销重复提交，返回首次撤销结果")
                .attr("firstOutcome", first.outcome())
                .attr("paymentRequestId", command.paymentRequestId())
                .build());
        return replayed;
    }

    /** 记录拒绝事件并返回业务结果；拒绝不产生任何账务变动。 */
    private ReversalResult finishReject(ReversalCommand command, Loan loan,
                                        ReversalOutcome outcome, String message) {
        return finishReject(command, loan, outcome, message, true);
    }

    private ReversalResult finishReject(ReversalCommand command, Loan loan,
                                        ReversalOutcome outcome, String message,
                                        boolean appendEvent) {
        log.info("[撤销拒绝] revId={}, paymentRequestId={}, outcome={}, reason={}",
                command == null ? null : command.reversalRequestId(),
                command == null ? null : command.paymentRequestId(), outcome, message);
        if (appendEvent && command != null && command.loanId() != null) {
            eventStore.append(DomainEvent.builder(EventType.REVERSAL_REJECTED)
                    .loanId(command.loanId())
                    .requestId(command.reversalRequestId())
                    .businessDate(command.asOf() == null ? LocalDate.now() : command.asOf())
                    .summary(message)
                    .attr("outcome", outcome)
                    .attr("paymentRequestId",
                            command.paymentRequestId() == null ? "" : command.paymentRequestId())
                    .build());
        }
        return buildResult(command, loan, Money.ZERO, outcome, message, false);
    }

    private ReversalResult invalidResult(String revId, String loanId, String paymentId,
                                         LocalDate asOf, String message) {
        log.info("[撤销非法请求] revId={}, loanId={}, paymentRequestId={}, reason={}",
                revId, loanId, paymentId, message);
        if (loanId != null && !loanId.isBlank()) {
            eventStore.append(DomainEvent.builder(EventType.REVERSAL_REJECTED)
                    .loanId(loanId)
                    .requestId(revId)
                    .businessDate(asOf == null ? LocalDate.now() : asOf)
                    .summary(message)
                    .attr("outcome", ReversalOutcome.REJECTED_REQUEST_INVALID)
                    .attr("paymentRequestId", paymentId == null ? "" : paymentId)
                    .build());
        }
        return new ReversalResult(revId, loanId, paymentId,
                ReversalOutcome.REJECTED_REQUEST_INVALID, message,
                Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO,
                null, asOf, false);
    }

    private ReversalResult buildResult(ReversalCommand command, Loan loan, Money refund,
                                       ReversalOutcome outcome, String message, boolean replay) {
        return new ReversalResult(
                command == null ? null : command.reversalRequestId(),
                command == null ? null : command.loanId(),
                command == null ? null : command.paymentRequestId(),
                outcome, message, refund,
                loan == null ? Money.ZERO : loan.getSuspenseBalance(),
                loan == null ? Money.ZERO : loan.unpaidPrincipal(),
                loan == null ? Money.ZERO : loan.unpaidInterest(),
                loan == null ? Money.ZERO : loan.unpaidPenalty(),
                loan == null ? null : loan.getStatus(),
                command == null || command.asOf() == null ? LocalDate.now() : command.asOf(),
                replay);
    }
}

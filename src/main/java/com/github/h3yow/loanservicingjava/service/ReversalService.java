package com.github.h3yow.loanservicingjava.service;

import com.github.h3yow.loanservicingjava.domain.event.DomainEvent;
import com.github.h3yow.loanservicingjava.domain.event.EventType;
import com.github.h3yow.loanservicingjava.domain.loan.LedgerOperation;
import com.github.h3yow.loanservicingjava.domain.loan.Loan;
import com.github.h3yow.loanservicingjava.domain.loan.LoanStatus;
import com.github.h3yow.loanservicingjava.domain.money.Money;
import com.github.h3yow.loanservicingjava.domain.payment.InstallmentAllocation;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentResult;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalCommand;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalOutcome;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalResult;
import com.github.h3yow.loanservicingjava.repo.EventStore;
import com.github.h3yow.loanservicingjava.repo.IdempotencyRecord;
import com.github.h3yow.loanservicingjava.repo.IdempotencyStore;
import com.github.h3yow.loanservicingjava.repo.LoanRepository;
import com.github.h3yow.loanservicingjava.repo.ReversalRecord;
import com.github.h3yow.loanservicingjava.repo.ReversalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 还款撤销（冲正）应用服务。并发/幂等协议与 {@link PaymentService} 一致：
 * 请求维度 CAS 占位 → 贷款维度分段锁 → 锁内复查决定 处理/重放/并发冲突/指纹不符；
 * 锁内完成“目标校验 → 台账重放重建 → 现金守恒自检 → 原子替换聚合”，
 * 保证同一笔还款的并发撤销只真正生效一次。
 *
 * <p>业务规则：仅“已成功受理”（ACCEPTED_*）的还款可撤销；目标不存在、重复撤销等均返回
 * 彼此可区分的拒绝结果，任何情况下都不抛服务端错误、不静默改账。撤销不做手工减法，
 * 而是从事件流水还原历史操作并按“该笔还款从未发生”重放（见 {@link Loan#replayWithout}）。
 */
@Service
public class ReversalService {

    private static final Logger log = LoggerFactory.getLogger(ReversalService.class);

    private final LoanRepository loanRepository;
    private final IdempotencyStore idempotencyStore;
    private final ReversalStore reversalStore;
    private final EventStore eventStore;

    public ReversalService(LoanRepository loanRepository,
                           IdempotencyStore idempotencyStore,
                           ReversalStore reversalStore,
                           EventStore eventStore) {
        this.loanRepository = loanRepository;
        this.idempotencyStore = idempotencyStore;
        this.reversalStore = reversalStore;
        this.eventStore = eventStore;
    }

    /**
     * Web 边界入口：先校验原始入参，非法输入统一返回 {@link ReversalOutcome#REJECTED_REQUEST_INVALID}
     * 业务结果，不抛异常、不触碰任何存储（幂等表/事件流水/台账一律不变）。
     * 非法请求不落幂等记录：同一 requestId 修正参数后重新提交可按正常请求处理。
     */
    public ReversalResult reverse(String requestId, String loanId,
                                  String targetPaymentRequestId, LocalDate asOf) {
        String violation = ReversalCommand.validate(requestId, loanId, targetPaymentRequestId, asOf);
        if (violation != null) {
            log.info("[撤销拒绝] reversalRequestId={}, loanId={}, target={}, outcome={}, reason={}",
                    requestId, loanId, targetPaymentRequestId,
                    ReversalOutcome.REJECTED_REQUEST_INVALID, violation);
            return new ReversalResult(requestId, loanId, targetPaymentRequestId,
                    ReversalOutcome.REJECTED_REQUEST_INVALID,
                    "撤销请求本身不合法（" + violation + "），未做任何账务处理；"
                            + "请补全 requestId/targetPaymentRequestId 等必填字段后重试",
                    Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO,
                    null, List.of(), asOf, false);
        }
        return reverse(new ReversalCommand(requestId, loanId, targetPaymentRequestId, asOf));
    }

    /** 撤销一笔已成功受理的还款；成功、重复撤销、并发冲突、拒绝均返回可区分结果。 */
    public ReversalResult reverse(ReversalCommand command) {
        log.info("[撤销请求] reversalRequestId={}, loanId={}, targetPaymentRequestId={}, asOf={}",
                command.requestId(), command.loanId(), command.targetPaymentRequestId(), command.asOf());

        // 1) 请求维度 CAS 占位：决定本线程是否为该撤销 requestId 的候选处理者
        ReversalRecord mine = ReversalRecord.processing(command.requestId(), command.loanId(),
                command.targetPaymentRequestId(), command.fingerprint());
        boolean candidateOwner = reversalStore.putIfAbsent(mine).isEmpty();

        // 2) 统一进入贷款锁后复查最新撤销记录再决策（与还款同一把贷款锁，锁序固定：请求CAS → 贷款锁）。
        //    readLocked 对不存在的贷款返回 null（lambda 不执行），故先做一次存在性校验，
        //    保证“贷款不存在”返回可区分业务结果而非空指针/服务端错误。
        if (loanRepository.findById(command.loanId()).isEmpty()) {
            reversalStore.removeProcessing(command.requestId());
            return reject(command, ReversalOutcome.REJECTED_PAYMENT_NOT_FOUND,
                    "贷款不存在: " + command.loanId(), null, null);
        }
        return loanRepository.readLocked(command.loanId(), loan -> {
            ReversalRecord latest = reversalStore.find(command.requestId()).orElse(null);
            if (!candidateOwner) {
                return resolveDuplicate(command, latest, loan);
            }
            return processAsOwner(command, loan);
        });
    }

    private ReversalResult processAsOwner(ReversalCommand command, Loan loan) {
        String reversalRequestId = command.requestId();
        try {
            if (loan == null) {
                reversalStore.removeProcessing(reversalRequestId);
                return reject(command, ReversalOutcome.REJECTED_PAYMENT_NOT_FOUND,
                        "贷款不存在: " + command.loanId(), null, null);
            }

            // 2.1) 目标还款必须存在且为“已成功受理”（COMPLETED + ACCEPTED_*）
            IdempotencyRecord target =
                    idempotencyStore.find(command.targetPaymentRequestId()).orElse(null);
            if (target == null || target.state() != IdempotencyRecord.State.COMPLETED
                    || target.result() == null || !target.result().accepted()) {
                reversalStore.removeProcessing(reversalRequestId);
                String detail = target == null
                        ? "目标还款请求不存在: " + command.targetPaymentRequestId()
                        : "目标还款未成功受理（outcome="
                                + (target.result() == null ? "PROCESSING" : target.result().outcome())
                                + "），无可撤销账务";
                return reject(command, ReversalOutcome.REJECTED_PAYMENT_NOT_FOUND,
                        detail, loan, null);
            }
            // 跨贷款串用：原还款不属于本贷款，视为目标不匹配（不允许撤销）
            if (!command.loanId().equals(target.loanId())) {
                reversalStore.removeProcessing(reversalRequestId);
                return reject(command, ReversalOutcome.REJECTED_PAYMENT_NOT_FOUND,
                        "目标还款 " + command.targetPaymentRequestId() + " 属于贷款 "
                                + target.loanId() + "，不属于 " + command.loanId(), loan, target.result());
            }

            // 2.2) 同一笔还款已被“另一个撤销 requestId”成功撤销 → 明确拒绝（区别于同键重放）
            Optional<ReversalRecord> priorSuccess = reversalStore
                    .findByTargetPayment(command.targetPaymentRequestId()).stream()
                    .filter(r -> r.state() == ReversalRecord.State.COMPLETED
                            && r.result() != null && r.result().succeeded())
                    .findFirst();
            if (priorSuccess.isPresent()) {
                reversalStore.removeProcessing(reversalRequestId);
                // 拒绝结果不得携带任何“本次红冲金额”快照：本次没有任何账务动作
                return reject(command, ReversalOutcome.REJECTED_ALREADY_REVERSED,
                        "目标还款已被撤销，首次撤销 requestId=" + priorSuccess.get().requestId()
                                + "；本次不再退账", loan, null);
            }

            // 2.3) 锁内台账重放：以“目标还款从未发生”重建账本。
            //      历史流水是已落账动作的唯一事实来源；剔除本次目标 + 历史上已成功撤销的还款。
            PaymentResult original = target.result();
            List<String> previouslyReversed = eventStore.findByLoan(command.loanId()).stream()
                    .filter(e -> e.type() == EventType.PAYMENT_REVERSED)
                    .map(e -> e.attributes().get("reversedPaymentRequestId"))
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            java.util.Set<String> excluded = new java.util.HashSet<>(previouslyReversed);
            excluded.add(command.targetPaymentRequestId());

            List<LedgerOperation> operations = collectOperations(command.loanId());
            log.info("[撤销重放] loanId={}, target={}, excluded={}, replayOps={}",
                    command.loanId(), command.targetPaymentRequestId(), excluded,
                    operations.stream().filter(o -> !excluded.contains(o.requestId())).count());
            Loan rebuilt = loan.replayWithout(operations, excluded,
                    command.targetPaymentRequestId(), original.requestedAmount(),
                    reversalRequestId, command.asOf());

            // 2.4) 现金守恒自检（防御性）：重放后科目+挂账的现金必须等于“历史有效现金流入之和”。
            //      不守恒说明流水与账本不一致，此时绝不落库替换。
            Money expectedCash = operations.stream()
                    .filter(op -> !excluded.contains(op.requestId()))
                    .filter(op -> op instanceof LedgerOperation.Payment p)
                    .map(op -> ((LedgerOperation.Payment) op).amount())
                    .reduce(Money.ZERO, Money::plus);
            Money actualCash = sumCash(rebuilt);
            if (!expectedCash.equals(actualCash)) {
                reversalStore.removeProcessing(reversalRequestId);
                return reject(command, ReversalOutcome.REJECTED_NOT_REVERSIBLE,
                        "重放后现金守恒校验失败: 期望有效现金=" + expectedCash
                                + "，重放账本现金=" + actualCash, loan, original);
            }

            List<DomainEvent> rebuiltEvents = rebuilt.drainEvents();
            ReversalResult result = buildResult(command, ReversalOutcome.REVERSED,
                    "撤销成功：台账已按“该笔还款从未发生”重放重建，原还款金额="
                            + original.requestedAmount() + "（其中原入账="
                            + original.appliedAmount() + "，原挂账=" + original.suspenseHeld()
                            + "），并按 " + command.asOf() + " 补提罚息、重算状态与账龄",
                    rebuilt, original, false);
            // 先整体替换聚合，再固化撤销记录、落事件，全部处于同一贷款锁临界区
            loanRepository.replaceLocked(command.loanId(), rebuilt);
            reversalStore.complete(reversalRequestId, result);
            eventStore.appendAll(rebuiltEvents);
            log.info("[撤销完成] reversalRequestId={}, target={}, 重放后 unpaid(P/I/N)={}/{}/{}, "
                            + "suspense={}, status={}",
                    reversalRequestId, command.targetPaymentRequestId(),
                    rebuilt.unpaidPrincipal(), rebuilt.unpaidInterest(), rebuilt.unpaidPenalty(),
                    rebuilt.getSuspenseBalance(), rebuilt.getStatus());
            return result;
        } catch (RuntimeException ex) {
            reversalStore.removeProcessing(reversalRequestId);
            throw ex;
        }
    }

    /**
     * 从事件流水还原该贷款全部“真实落账”的现金动作，按发生时间排序（同刻按追加顺序）。
     * 还款金额取 PAYMENT_APPLIED.requestedAmount（含后续挂账部分）；
     * 同一 requestId 若存在 SUSPENSE_APPLIED，则该动作是挂账冲抵（不引入新现金）。
     */
    private List<LedgerOperation> collectOperations(String loanId) {
        List<DomainEvent> loanEvents = eventStore.findByLoan(loanId);
        java.util.Set<String> sweepRequestIds = loanEvents.stream()
                .filter(e -> e.type() == EventType.SUSPENSE_APPLIED && e.requestId() != null)
                .map(DomainEvent::requestId)
                .collect(java.util.stream.Collectors.toSet());
        return loanEvents.stream()
                .filter(e -> e.type() == EventType.PAYMENT_APPLIED && e.requestId() != null)
                .map(e -> {
                    LocalDate asOf = e.businessDate();
                    String rid = e.requestId();
                    if (sweepRequestIds.contains(rid)) {
                        return (LedgerOperation) new LedgerOperation.SuspenseSweep(rid, asOf);
                    }
                    String requested = e.attributes().get("requestedAmount");
                    return (LedgerOperation) new LedgerOperation.Payment(
                            rid, asOf, Money.yuan(requested));
                })
                .toList();
    }

    private Money sumCash(Loan loan) {
        return loan.getInstallments().stream()
                .map(i -> i.getPaidPrincipal().plus(i.getPaidInterest()).plus(i.getPaidPenalty()))
                .reduce(Money.ZERO, Money::plus).plus(loan.getSuspenseBalance());
    }

    private ReversalResult resolveDuplicate(ReversalCommand command,
                                            ReversalRecord latest, Loan loan) {
        if (latest == null) {
            // 理论不可达：CAS 已见记录；防御性按并发冲突处理，不退账
            return reject(command, ReversalOutcome.CONFLICT_CONCURRENT,
                    "撤销幂等状态在锁内复查时缺失，按并发冲突处理，本次不退账", loan, null);
        }
        if (!latest.fingerprint().equals(command.fingerprint())) {
            return reject(command, ReversalOutcome.REJECTED_IDEMPOTENCY_MISMATCH,
                    "撤销 requestId=" + command.requestId() + " 已用于不同请求，已有指纹="
                            + latest.fingerprint() + "，本次指纹=" + command.fingerprint(), loan, null);
        }
        if (latest.state() == ReversalRecord.State.PROCESSING) {
            return reject(command, ReversalOutcome.CONFLICT_CONCURRENT,
                    "同一撤销 requestId 的另一请求正在处理中，本次不退账", loan, null);
        }
        ReversalResult firstSnapshot = latest.result();
        log.info("[撤销重放] reversalRequestId={}, 返回首次结果 firstOutcome={}, 不重复退账",
                command.requestId(), firstSnapshot.outcome());
        eventStore.append(DomainEvent.builder(EventType.PAYMENT_REVERSAL_REJECTED)
                .loanId(command.loanId())
                .businessDate(command.asOf())
                .requestId(command.requestId())
                .summary("撤销请求重复提交，返回首次结果")
                .attr("outcome", ReversalOutcome.REPLAYED)
                .attr("reversedPaymentRequestId", command.targetPaymentRequestId())
                .attr("firstOutcome", firstSnapshot.outcome())
                .build());
        return buildReplay(firstSnapshot, command);
    }

    private ReversalResult reject(ReversalCommand command, ReversalOutcome outcome,
                                  String message, Loan loan, PaymentResult original) {
        log.info("[撤销拒绝] reversalRequestId={}, target={}, outcome={}, reason={}",
                command.requestId(), command.targetPaymentRequestId(), outcome, message);
        eventStore.append(DomainEvent.builder(EventType.PAYMENT_REVERSAL_REJECTED)
                .loanId(command.loanId())
                .businessDate(command.asOf())
                .requestId(command.requestId())
                .summary(message)
                .attr("outcome", outcome)
                .attr("reversedPaymentRequestId", command.targetPaymentRequestId())
                .build());
        return buildResult(command, outcome, message, loan, original, false);
    }

    /** 组装撤销结果：reversed* 字段描述“被撤销那笔还款当时的现金口径”，账龄/余额快照取重放后账本。 */
    private ReversalResult buildResult(ReversalCommand command, ReversalOutcome outcome,
                                       String message, Loan rebuilt,
                                       PaymentResult original, boolean replay) {
        return new ReversalResult(command.requestId(), command.loanId(),
                command.targetPaymentRequestId(), outcome, message,
                original == null ? Money.ZERO : original.appliedAmount(),
                original == null ? Money.ZERO : original.suspenseHeld(),
                rebuilt == null ? Money.ZERO : rebuilt.getSuspenseBalance(),
                rebuilt == null ? Money.ZERO : rebuilt.unpaidPrincipal(),
                rebuilt == null ? Money.ZERO : rebuilt.unpaidInterest(),
                rebuilt == null ? Money.ZERO : rebuilt.unpaidPenalty(),
                rebuilt == null ? LoanStatus.NORMAL : rebuilt.getStatus(),
                original == null ? List.of() : original.allocations(),
                command.asOf(), replay);
    }

    private ReversalResult buildReplay(ReversalResult first, ReversalCommand command) {
        return new ReversalResult(command.requestId(), command.loanId(),
                command.targetPaymentRequestId(), ReversalOutcome.REPLAYED,
                "[重放] 首次撤销结果=" + first.outcome() + "；" + first.message(),
                Money.ZERO, Money.ZERO,
                first.suspenseBalanceAfter(), first.unpaidPrincipalAfter(),
                first.unpaidInterestAfter(), first.unpaidPenaltyAfter(),
                first.loanStatusAfter(), List.of(), command.asOf(), true);
    }
}

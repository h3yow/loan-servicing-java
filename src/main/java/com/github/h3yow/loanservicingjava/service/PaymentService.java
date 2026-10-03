package com.github.h3yow.loanservicingjava.service;

import com.github.h3yow.loanservicingjava.domain.event.DomainEvent;
import com.github.h3yow.loanservicingjava.domain.event.EventType;
import com.github.h3yow.loanservicingjava.domain.loan.Loan;
import com.github.h3yow.loanservicingjava.domain.loan.LoanStatus;
import com.github.h3yow.loanservicingjava.domain.money.Money;
import com.github.h3yow.loanservicingjava.domain.payment.InstallmentAllocation;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentAllocation;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentCommand;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentOutcome;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentResult;
import com.github.h3yow.loanservicingjava.repo.EventStore;
import com.github.h3yow.loanservicingjava.repo.IdempotencyRecord;
import com.github.h3yow.loanservicingjava.repo.IdempotencyStore;
import com.github.h3yow.loanservicingjava.repo.LoanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 还款受理应用服务。并发/幂等协议：
 * <ol>
 *   <li>先在请求维度做 CAS 登记（putIfAbsent PROCESSING），保证全局同一 requestId 只有一个处理者；</li>
 *   <li>再获取贷款级分段锁，保证同一贷款的账务修改串行化；锁内重读幂等记录区分
 *       REPLAYED / CONFLICT_CONCURRENT / REJECTED_IDEMPOTENCY_MISMATCH；</li>
 *   <li>处理失败时回滚 PROCESSING 占位；成功时固化 COMPLETED + 结果快照，重放永不重复扣款。</li>
 * </ol>
 * 锁顺序固定为“请求维度 CAS → 贷款锁”，不反向获取，杜绝交叉死锁。
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final LoanRepository loanRepository;
    private final IdempotencyStore idempotencyStore;
    private final EventStore eventStore;

    public PaymentService(LoanRepository loanRepository,
                          IdempotencyStore idempotencyStore,
                          EventStore eventStore) {
        this.loanRepository = loanRepository;
        this.idempotencyStore = idempotencyStore;
        this.eventStore = eventStore;
    }

    /**
     * 受理一笔还款。不足额（ACCEPTED_PARTIAL）、超额（ACCEPTED_SUSPENSE）均不静默；
     * 重放、并发冲突、指纹不一致各有独立结果码。
     */
    public PaymentResult acceptPayment(PaymentCommand command) {
        log.info("[还款请求] requestId={}, loanId={}, amount={}, asOf={}",
                command.requestId(), command.loanId(), command.amount(), command.asOf());

        Money requested;
        try {
            requested = Money.yuan(command.amount());
        } catch (RuntimeException ex) {
            return reject(command, PaymentOutcome.REJECTED_AMOUNT_INVALID,
                    "金额无法解析: " + command.amount(), null);
        }
        if (!requested.isPositive()) {
            return reject(command, PaymentOutcome.REJECTED_AMOUNT_INVALID,
                    "还款金额必须为正数，实际=" + requested, null);
        }

        // 1) 请求维度 CAS 占位：决定本线程是否为该 requestId 的“候选处理者”
        IdempotencyRecord mine = IdempotencyRecord.processing(
                command.requestId(), command.loanId(), command.fingerprint());
        Optional<IdempotencyRecord> existingAtCas = idempotencyStore.putIfAbsent(mine);
        boolean candidateOwner = existingAtCas.isEmpty();

        // 2) 无论 CAS 结果如何，统一进入贷款锁后“复查最新幂等记录”再决策：
        //    - 候选者负责真正扣款并在锁内固化 COMPLETED；
        //    - 重复者可能观察到 PROCESSING（并发冲突）或 COMPLETED（重放），
        //      复查保证不会使用锁外的过期快照而重复扣款。
        return loanRepository.readLocked(command.loanId(), loan -> {
            IdempotencyRecord latest = idempotencyStore.find(command.requestId()).orElse(null);
            if (!candidateOwner) {
                return resolveDuplicate(command, latest, loan);
            }
            return processAsOwner(command, requested, loan);
        });
    }

    private PaymentResult processAsOwner(PaymentCommand command, Money requested, Loan loan) {
        String requestId = command.requestId();
        try {
            if (loan == null) {
                idempotencyStore.removeProcessing(requestId);
                return reject(command, PaymentOutcome.REJECTED_AMOUNT_INVALID,
                        "贷款不存在: " + command.loanId(), null);
            }
            if (loan.getStatus() == LoanStatus.SETTLED && loan.totalDue().isZero()) {
                idempotencyStore.removeProcessing(requestId);
                return reject(command, PaymentOutcome.REJECTED_LOAN_CLOSED,
                        "贷款已结清且无挂账，拒绝再次扣款", loan);
            }

            LocalDate asOf = command.asOf();
            // 先计提罚息、再推进逾期/账龄，保证分摊基数与判定日期一致
            loan.accruePenalty(asOf);
            loan.refreshOverdueStatus(asOf);

            Money dueBefore = loan.totalDue();
            PaymentAllocation allocation = loan.computeAllocation(requested, asOf);
            log.info("[分摊判定] requestId={}, dueBefore={}, requested={}, applied={}, remaining={}, shortfall={}",
                    requestId, dueBefore, requested,
                    allocation.applied(), allocation.remaining(), allocation.hasShortfall());

            // 成功落账（科目分摊 + 必要时挂账）并在聚合台账留痕，供后续撤销重放
            loan.acceptPaymentEntry(allocation, requestId, asOf);

            PaymentOutcome outcome;
            Money suspenseHeld = Money.ZERO;
            String message;
            if (allocation.hasSurplus()) {
                suspenseHeld = allocation.remaining();
                outcome = PaymentOutcome.ACCEPTED_SUSPENSE;
                message = allocation.applied().isZero()
                        ? "当前无应还（或已结清），全部金额挂账，金额=" + suspenseHeld
                        : "应还已结清，溢缴部分挂账，挂账金额=" + suspenseHeld;
            } else if (allocation.hasShortfall()) {
                outcome = PaymentOutcome.ACCEPTED_PARTIAL;
                Money gap = allocation.totalDueBefore().minus(allocation.applied());
                message = "还款不足，已按 罚息→利息→本金 顺序分摊，剩余应还缺口=" + gap;
            } else {
                outcome = PaymentOutcome.ACCEPTED_FULL;
                message = "还款全额分摊，当前应还结清";
            }

            List<DomainEvent> events = loan.drainEvents();
            PaymentResult result = buildResult(command, requested, outcome, message,
                    suspenseHeld, allocation, loan, false);
            // 结果在同一贷款锁内固化，随后进入锁的重复者一定能读到 COMPLETED
            idempotencyStore.complete(requestId, result);
            eventStore.appendAll(events);
            log.info("[还款完成] requestId={}, outcome={}, unpaidAfter(P/I/N)={}/{}/{}, status={}",
                    requestId, outcome, loan.unpaidPrincipal(),
                    loan.unpaidInterest(), loan.unpaidPenalty(), loan.getStatus());
            return result;
        } catch (RuntimeException ex) {
            idempotencyStore.removeProcessing(requestId);
            throw ex;
        }
    }

    private PaymentResult resolveDuplicate(PaymentCommand command,
                                           IdempotencyRecord latest, Loan loan) {
        String fingerprint = command.fingerprint();
        if (latest == null) {
            // 理论上不可达：CAS 已见记录；防御性处理为并发冲突，不扣款
            return reject(command, PaymentOutcome.CONFLICT_CONCURRENT,
                    "幂等状态在锁内复查时缺失，按并发冲突处理，本次不扣款", loan);
        }
        if (!latest.fingerprint().equals(fingerprint)) {
            return reject(command, PaymentOutcome.REJECTED_IDEMPOTENCY_MISMATCH,
                    "requestId=" + command.requestId() + " 已用于不同请求，已有指纹="
                            + latest.fingerprint() + "，本次指纹=" + fingerprint, loan);
        }
        if (latest.state() == IdempotencyRecord.State.PROCESSING) {
            return reject(command, PaymentOutcome.CONFLICT_CONCURRENT,
                    "同一 requestId 的另一请求正在处理中，本次不扣款", loan);
        }
        PaymentResult first = latest.result();
        log.info("[幂等重放] requestId={}, 返回首次结果 firstOutcome={}, 不重复扣款",
                command.requestId(), first.outcome());
        PaymentResult replayed = new PaymentResult(first.requestId(), first.loanId(),
                PaymentOutcome.REPLAYED,
                "[重放] 首次受理结果=" + first.outcome() + "；" + first.message(),
                first.requestedAmount(),
                Money.ZERO, Money.ZERO, first.suspenseBalanceAfter(),
                first.unpaidPrincipalAfter(), first.unpaidInterestAfter(),
                first.unpaidPenaltyAfter(), first.loanStatusAfter(), first.allocations(),
                first.businessDate(), true);
        eventStore.append(DomainEvent.builder(EventType.PAYMENT_REPLAYED)
                .loanId(command.loanId())
                .businessDate(command.asOf())
                .requestId(command.requestId())
                .summary("重复提交，返回首次结果")
                .attr("firstOutcome", first.outcome())
                .build());
        return replayed;
    }

    private PaymentResult reject(PaymentCommand command, PaymentOutcome outcome,
                                 String message, Loan loan) {
        log.info("[还款拒绝] requestId={}, outcome={}, reason={}",
                command.requestId(), outcome, message);
        eventStore.append(DomainEvent.builder(EventType.PAYMENT_REJECTED)
                .loanId(command.loanId())
                .requestId(command.requestId())
                .businessDate(command.asOf())
                .summary(message)
                .attr("outcome", outcome)
                .attr("amount", command.amount())
                .build());
        return buildResult(command, Money.yuan(command.amount()), outcome, message,
                Money.ZERO, null, loan, false);
    }

    private PaymentResult buildResult(PaymentCommand command, Money requested,
                                      PaymentOutcome outcome, String message,
                                      Money suspenseHeld, PaymentAllocation allocation,
                                      Loan loan, boolean replay) {
        List<InstallmentAllocation> lines = allocation == null ? List.of() : allocation.lines();
        Money applied = allocation == null ? Money.ZERO : allocation.applied();
        return new PaymentResult(command.requestId(), command.loanId(), outcome, message,
                requested, applied, suspenseHeld,
                loan == null ? Money.ZERO : loan.getSuspenseBalance(),
                loan == null ? Money.ZERO : loan.unpaidPrincipal(),
                loan == null ? Money.ZERO : loan.unpaidInterest(),
                loan == null ? Money.ZERO : loan.unpaidPenalty(),
                loan == null ? null : loan.getStatus(),
                lines, command.asOf(), replay);
    }

    /** 用挂账余额冲抵应还（计提/逾期推进在冲抵前执行）。 */
    public PaymentResult applySuspense(String loanId, String requestId, LocalDate asOf) {
        log.info("[挂账冲抵请求] loanId={}, requestId={}, asOf={}", loanId, requestId, asOf);
        final PaymentResult[] holder = new PaymentResult[1];
        loanRepository.updateLocked(loanId, loan -> {
            if (loan.getSuspenseBalance().isZero()) {
                holder[0] = buildResult(new PaymentCommand(requestId, loanId,
                        java.math.BigDecimal.ZERO, asOf), Money.ZERO,
                        PaymentOutcome.REJECTED_AMOUNT_INVALID, "挂账余额为零，无可冲抵金额",
                        Money.ZERO, null, loan, false);
                return;
            }
            loan.accruePenalty(asOf);
            loan.refreshOverdueStatus(asOf);
            PaymentAllocation allocation = loan.applySuspense(requestId, asOf);
            PaymentResult result = buildResult(new PaymentCommand(requestId, loanId,
                    allocation.applied().plus(allocation.remaining()).toYuan(), asOf),
                    allocation.applied().plus(allocation.remaining()),
                    PaymentOutcome.ACCEPTED_FULL,
                    "挂账冲抵完成，冲抵金额=" + allocation.applied()
                            + "，剩余挂账=" + allocation.remaining(),
                    Money.ZERO, allocation, loan, false);
            eventStore.appendAll(loan.drainEvents());
            holder[0] = result;
        });
        return holder[0];
    }
}

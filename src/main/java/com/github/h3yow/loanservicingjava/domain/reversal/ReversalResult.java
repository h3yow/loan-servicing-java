package com.github.h3yow.loanservicingjava.domain.reversal;

import com.github.h3yow.loanservicingjava.domain.loan.LoanStatus;
import com.github.h3yow.loanservicingjava.domain.money.Money;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 还款撤销/冲正的完整结果（不可变）。成功、拒绝、重放、并发冲突都返回本类型，
 * 由 {@link ReversalOutcome} 区分语义；message 给出人可读判定依据。
 *
 * @param refundAmount          本次撤销实际退回的现金（=该笔还款当时入科目的金额+挂账金额）；重放/拒绝时为零。
 * @param suspenseBalanceAfter  撤销后挂账余额快照。
 * @param unpaidPrincipalAfter  撤销后未结本金快照。
 * @param unpaidInterestAfter   撤销后未结利息快照。
 * @param unpaidPenaltyAfter    撤销后未结罚息快照（含撤销后按撤销日补提的区间罚息）。
 * @param loanStatusAfter       撤销后贷款总态。
 * @param replay                是否为幂等重放（重放不再退账）。
 */
public record ReversalResult(
        String reversalRequestId,
        String loanId,
        String paymentRequestId,
        ReversalOutcome outcome,
        String message,
        Money refundAmount,
        Money suspenseBalanceAfter,
        Money unpaidPrincipalAfter,
        Money unpaidInterestAfter,
        Money unpaidPenaltyAfter,
        LoanStatus loanStatusAfter,
        LocalDate businessDate,
        boolean replay) {

    public ReversalResult {
        Objects.requireNonNull(outcome, "outcome");
    }

    public boolean succeeded() {
        return outcome == ReversalOutcome.REVERSED;
    }
}

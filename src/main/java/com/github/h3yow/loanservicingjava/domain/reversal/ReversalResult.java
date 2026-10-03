package com.github.h3yow.loanservicingjava.domain.reversal;

import com.github.h3yow.loanservicingjava.domain.loan.LoanStatus;
import com.github.h3yow.loanservicingjava.domain.money.Money;
import com.github.h3yow.loanservicingjava.domain.payment.InstallmentAllocation;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 撤销处理的完整结果（不可变）。无论成功、拒绝、重放还是并发冲突都返回本类型，
 * 由 {@link ReversalOutcome} 区分语义。
 *
 * @param reversedLines 本次从各期科目红冲回来的金额快照（成功时非空）。
 */
public record ReversalResult(
        String requestId,
        String loanId,
        String targetPaymentRequestId,
        ReversalOutcome outcome,
        String message,
        Money reversedAppliedAmount,
        Money reversedSuspenseAmount,
        Money suspenseBalanceAfter,
        Money unpaidPrincipalAfter,
        Money unpaidInterestAfter,
        Money unpaidPenaltyAfter,
        LoanStatus loanStatusAfter,
        List<InstallmentAllocation> reversedLines,
        LocalDate businessDate,
        boolean replay) {

    public ReversalResult {
        Objects.requireNonNull(outcome, "outcome");
        reversedLines = reversedLines == null ? List.of() : List.copyOf(reversedLines);
    }

    public boolean succeeded() {
        return outcome == ReversalOutcome.REVERSED;
    }
}

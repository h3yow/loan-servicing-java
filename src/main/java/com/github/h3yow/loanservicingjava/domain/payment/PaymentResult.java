package com.github.h3yow.loanservicingjava.domain.payment;

import com.github.h3yow.loanservicingjava.domain.loan.LoanStatus;
import com.github.h3yow.loanservicingjava.domain.money.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 还款受理的完整结果（不可变）。无论成功、拒绝、重放还是并发冲突都返回本类型，
 * 由 {@link PaymentOutcome} 区分语义；message 给出人可读判定依据，allocation 为落账快照（拒绝/重放时可能为 null）。
 */
public record PaymentResult(
        String requestId,
        String loanId,
        PaymentOutcome outcome,
        String message,
        Money requestedAmount,
        Money appliedAmount,
        Money suspenseHeld,
        Money suspenseBalanceAfter,
        Money unpaidPrincipalAfter,
        Money unpaidInterestAfter,
        Money unpaidPenaltyAfter,
        LoanStatus loanStatusAfter,
        List<InstallmentAllocation> allocations,
        LocalDate businessDate,
        boolean replay) {

    public PaymentResult {
        Objects.requireNonNull(outcome, "outcome");
        allocations = allocations == null ? List.of() : List.copyOf(allocations);
    }

    public boolean accepted() {
        return outcome == PaymentOutcome.ACCEPTED_FULL
                || outcome == PaymentOutcome.ACCEPTED_PARTIAL
                || outcome == PaymentOutcome.ACCEPTED_SUSPENSE;
    }
}

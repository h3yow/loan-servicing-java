package com.github.h3yow.loanservicingjava.domain;

import java.time.LocalDate;

/**
 * 还款计划（一期）：记录应还本息、已还本息、罚息计提与结清日期。
 */
public class RepaymentSchedule {

    private final int period;
    private final LocalDate dueDate;
    private final Money principalDue;
    private final Money interestDue;

    private Money principalPaid = Money.ZERO;
    private Money interestPaid = Money.ZERO;
    private Money penaltyAccrued = Money.ZERO;
    private Money penaltyPaid = Money.ZERO;
    /** 宽限期届满后仍未结清时，罚息已计提到的日期（含当日）。 */
    private LocalDate penaltyAccruedTo;
    /** 全部结清的日期，未结清为 null。 */
    private LocalDate settledDate;

    public RepaymentSchedule(int period, LocalDate dueDate, Money principalDue, Money interestDue) {
        this.period = period;
        this.dueDate = dueDate;
        this.principalDue = principalDue;
        this.interestDue = interestDue;
    }

    public int getPeriod() {
        return period;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public Money getPrincipalDue() {
        return principalDue;
    }

    public Money getInterestDue() {
        return interestDue;
    }

    public Money getPrincipalPaid() {
        return principalPaid;
    }

    public Money getInterestPaid() {
        return interestPaid;
    }

    public Money getPenaltyAccrued() {
        return penaltyAccrued;
    }

    public Money getPenaltyPaid() {
        return penaltyPaid;
    }

    public LocalDate getPenaltyAccruedTo() {
        return penaltyAccruedTo;
    }

    public LocalDate getSettledDate() {
        return settledDate;
    }

    public Money outstandingPrincipal() {
        return principalDue.minus(principalPaid);
    }

    public Money outstandingInterest() {
        return interestDue.minus(interestPaid);
    }

    public Money outstandingPenalty() {
        return penaltyAccrued.minus(penaltyPaid);
    }

    public Money outstandingTotal() {
        return outstandingPrincipal().plus(outstandingInterest()).plus(outstandingPenalty());
    }

    public boolean isSettled() {
        return settledDate != null;
    }

    // ---- 以下为包内可变操作，由服务层在保证并发安全的前提下调用 ----

    public void applyPrincipal(Money amount) {
        if (amount.isNegative() || amount.isGreaterThan(outstandingPrincipal())) {
            throw new IllegalArgumentException("本金分摊越界: " + amount + " 未还本金: " + outstandingPrincipal());
        }
        this.principalPaid = this.principalPaid.plus(amount);
    }

    public void applyInterest(Money amount) {
        if (amount.isNegative() || amount.isGreaterThan(outstandingInterest())) {
            throw new IllegalArgumentException("利息分摊越界: " + amount + " 未还利息: " + outstandingInterest());
        }
        this.interestPaid = this.interestPaid.plus(amount);
    }

    public void applyPenalty(Money amount) {
        if (amount.isNegative() || amount.isGreaterThan(outstandingPenalty())) {
            throw new IllegalArgumentException("罚息分摊越界: " + amount + " 未还罚息: " + outstandingPenalty());
        }
        this.penaltyPaid = this.penaltyPaid.plus(amount);
    }

    public void addPenalty(Money amount, LocalDate accruedTo) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("罚息计提不能为负: " + amount);
        }
        this.penaltyAccrued = this.penaltyAccrued.plus(amount);
        this.penaltyAccruedTo = accruedTo;
    }

    public void markSettled(LocalDate date) {
        if (!outstandingTotal().isZero()) {
            throw new IllegalStateException("存在未清余额，不能标记结清: " + outstandingTotal());
        }
        this.settledDate = date;
    }
}

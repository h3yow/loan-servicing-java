package com.github.h3yow.loanservicingjava.domain.loan;

import com.github.h3yow.loanservicingjava.domain.money.Money;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 还款计划中的单个分期（聚合内实体，仅由 {@link Loan} 聚合根修改）。
 *
 * <p>每个分期持有三组可核对的科目余额：
 * scheduledXxx 为计划值（合同口径，永不变动），paidXxx 为已分摊金额，
 * accruedPenalty 为已计提尚未结清的罚息。恒等式：paidXxx + unpaidXxx = scheduledXxx（罚息为累计计提口径）。
 */
public final class Installment {

    private final int installmentNo;
    private final LocalDate dueDate;
    private final Money scheduledPrincipal;
    private final Money scheduledInterest;

    private Money paidPrincipal = Money.ZERO;
    private Money paidInterest = Money.ZERO;
    private Money accruedPenalty = Money.ZERO;
    private Money paidPenalty = Money.ZERO;

    /** 罚息已计提至的日期（含当日）。null 表示尚未计提过。 */
    private LocalDate lastPenaltyAccrualDate;
    /** 首次被判定为逾期的业务日期，用于追溯。 */
    private LocalDate firstOverdueDate;

    private InstallmentStatus status = InstallmentStatus.PENDING;
    private Bucket bucket = Bucket.CURRENT;
    private long overdueDays;

    private Installment(int installmentNo, LocalDate dueDate,
                        Money scheduledPrincipal, Money scheduledInterest) {
        this.installmentNo = installmentNo;
        this.dueDate = Objects.requireNonNull(dueDate, "dueDate");
        this.scheduledPrincipal = Objects.requireNonNull(scheduledPrincipal, "scheduledPrincipal");
        this.scheduledInterest = Objects.requireNonNull(scheduledInterest, "scheduledInterest");
    }

    /** 创建一笔尚未开始还款的分期。 */
    public static Installment of(int installmentNo, LocalDate dueDate,
                                 Money scheduledPrincipal, Money scheduledInterest) {
        if (installmentNo <= 0) {
            throw new IllegalArgumentException("installmentNo must be positive");
        }
        if (!scheduledPrincipal.isPositive() || scheduledInterest.isNegative()) {
            throw new IllegalArgumentException("scheduled amounts invalid");
        }
        return new Installment(installmentNo, dueDate, scheduledPrincipal, scheduledInterest);
    }

    public int getInstallmentNo() {
        return installmentNo;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public Money getScheduledPrincipal() {
        return scheduledPrincipal;
    }

    public Money getScheduledInterest() {
        return scheduledInterest;
    }

    public Money getPaidPrincipal() {
        return paidPrincipal;
    }

    public Money getPaidInterest() {
        return paidInterest;
    }

    public Money getAccruedPenalty() {
        return accruedPenalty;
    }

    public Money getPaidPenalty() {
        return paidPenalty;
    }

    public LocalDate getLastPenaltyAccrualDate() {
        return lastPenaltyAccrualDate;
    }

    public LocalDate getFirstOverdueDate() {
        return firstOverdueDate;
    }

    public InstallmentStatus getStatus() {
        return status;
    }

    public Bucket getBucket() {
        return bucket;
    }

    public long getOverdueDays() {
        return overdueDays;
    }

    public Money unpaidPrincipal() {
        return scheduledPrincipal.minus(paidPrincipal);
    }

    public Money unpaidInterest() {
        return scheduledInterest.minus(paidInterest);
    }

    public Money unpaidPenalty() {
        return accruedPenalty.minus(paidPenalty);
    }

    /** 当前应还总额（未结本金 + 未结利息 + 已计提未结罚息）。 */
    public Money totalDue() {
        return unpaidPrincipal().plus(unpaidInterest()).plus(unpaidPenalty());
    }

    public boolean isPaid() {
        return unpaidPrincipal().isZero()
                && unpaidInterest().isZero()
                && unpaidPenalty().isZero();
    }

    /** 是否已经进入逾期：业务日期晚于 到期日 + 宽限期。 */
    public boolean isOverdueOn(LocalDate asOf, int graceDays) {
        return asOf.isAfter(dueDate.plusDays(graceDays));
    }

    /** 账龄口径的逾期天数 = 业务日期 - 到期日（宽限期只影响是否判定，不影响天数口径）。 */
    public long overdueDaysOn(LocalDate asOf) {
        return Math.max(0L, asOf.toEpochDay() - dueDate.toEpochDay());
    }

    // -------- 以下为聚合根调用的包内变更方法 --------

    void addAccruedPenalty(Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("penalty amount must be positive");
        }
        this.accruedPenalty = this.accruedPenalty.plus(amount);
    }

    void markPenaltyAccruedThrough(LocalDate date) {
        this.lastPenaltyAccrualDate = date;
    }

    void markFirstOverdue(LocalDate date) {
        if (this.firstOverdueDate == null) {
            this.firstOverdueDate = date;
        }
    }

    void applyPayment(Money penalty, Money interest, Money principal) {
        this.paidPenalty = paidPenalty.plus(penalty);
        this.paidInterest = paidInterest.plus(interest);
        this.paidPrincipal = paidPrincipal.plus(principal);
        if (paidPenalty.compareTo(accruedPenalty) > 0
                || paidInterest.compareTo(scheduledInterest) > 0
                || paidPrincipal.compareTo(scheduledPrincipal) > 0) {
            throw new IllegalStateException("payment exceeds scheduled balance, ledger invariant broken");
        }
    }

    /** 按业务日期与宽限期重算分期状态、逾期天数与账龄桶，返回桶是否发生变化。 */
    boolean refreshStatus(LocalDate asOf, int graceDays) {
        Bucket oldBucket = this.bucket;
        if (isPaid()) {
            this.status = InstallmentStatus.PAID;
            this.bucket = Bucket.CURRENT;
            this.overdueDays = 0L;
        } else if (isOverdueOn(asOf, graceDays)) {
            this.status = InstallmentStatus.OVERDUE;
            this.overdueDays = overdueDaysOn(asOf);
            this.bucket = Bucket.ofOverdueDays(overdueDays);
            markFirstOverdue(asOf);
        } else {
            boolean partiallyPaid = paidPrincipal.isPositive() || paidInterest.isPositive() || paidPenalty.isPositive();
            this.status = partiallyPaid ? InstallmentStatus.PARTIAL : InstallmentStatus.PENDING;
            this.bucket = Bucket.CURRENT;
            this.overdueDays = 0L;
        }
        return oldBucket != this.bucket;
    }
}

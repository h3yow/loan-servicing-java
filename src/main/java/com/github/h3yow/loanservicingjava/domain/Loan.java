package com.github.h3yow.loanservicingjava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 贷款聚合根：持有还款计划、挂账余额与事件日志。
 * 所有变更须经服务层在贷款级锁内完成。
 */
public class Loan {

    private final String loanId;
    private final Money principal;
    private final BigDecimal annualRate;
    /** 罚息日利率（对逾期未还本金计收）。 */
    private final BigDecimal penaltyDailyRate;
    /** 宽限期天数：到期日后该天数内不认定逾期。 */
    private final int gracePeriodDays;
    private final LocalDate startDate;
    private final List<RepaymentSchedule> schedules;

    private Money suspenseBalance = Money.ZERO;
    private final List<LoanEvent> events = new ArrayList<>();
    /** 乐观版本号：每次成功变更递增，用于并发可观测与对账。 */
    private final AtomicLong version = new AtomicLong(0);

    public Loan(String loanId, Money principal, BigDecimal annualRate, BigDecimal penaltyDailyRate,
                int gracePeriodDays, LocalDate startDate, List<RepaymentSchedule> schedules) {
        this.loanId = loanId;
        this.principal = principal;
        this.annualRate = annualRate;
        this.penaltyDailyRate = penaltyDailyRate;
        this.gracePeriodDays = gracePeriodDays;
        this.startDate = startDate;
        this.schedules = new ArrayList<>(schedules);
    }

    public String getLoanId() {
        return loanId;
    }

    public Money getPrincipal() {
        return principal;
    }

    public BigDecimal getAnnualRate() {
        return annualRate;
    }

    public BigDecimal getPenaltyDailyRate() {
        return penaltyDailyRate;
    }

    public int getGracePeriodDays() {
        return gracePeriodDays;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public List<RepaymentSchedule> getSchedules() {
        return Collections.unmodifiableList(schedules);
    }

    public Money getSuspenseBalance() {
        return suspenseBalance;
    }

    public List<LoanEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public long getVersion() {
        return version.get();
    }

    /** 全部计划已结清（结清后不再认定逾期）。 */
    public boolean isPaidOff() {
        return schedules.stream().allMatch(RepaymentSchedule::isSettled);
    }

    /** 剩余本金合计（可与各期本金之和核对）。 */
    public Money outstandingPrincipal() {
        return schedules.stream().map(RepaymentSchedule::outstandingPrincipal)
                .reduce(Money.ZERO, Money::plus);
    }

    /** 剩余利息合计。 */
    public Money outstandingInterest() {
        return schedules.stream().map(RepaymentSchedule::outstandingInterest)
                .reduce(Money.ZERO, Money::plus);
    }

    /** 未还罚息合计。 */
    public Money outstandingPenalty() {
        return schedules.stream().map(RepaymentSchedule::outstandingPenalty)
                .reduce(Money.ZERO, Money::plus);
    }

    public void creditSuspense(Money amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("挂账不能为负: " + amount);
        }
        this.suspenseBalance = this.suspenseBalance.plus(amount);
    }

    public void debitSuspense(Money amount) {
        if (amount.isNegative() || amount.isGreaterThan(suspenseBalance)) {
            throw new IllegalArgumentException("挂账余额不足: " + amount + " 当前: " + suspenseBalance);
        }
        this.suspenseBalance = this.suspenseBalance.minus(amount);
    }

    public void addEvent(String type, String detail) {
        events.add(new LoanEvent(type, detail));
    }

    public void bumpVersion() {
        version.incrementAndGet();
    }
}

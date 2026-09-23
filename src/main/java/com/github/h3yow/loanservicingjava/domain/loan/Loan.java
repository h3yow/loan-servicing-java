package com.github.h3yow.loanservicingjava.domain.loan;

import com.github.h3yow.loanservicingjava.domain.event.DomainEvent;
import com.github.h3yow.loanservicingjava.domain.event.EventType;
import com.github.h3yow.loanservicingjava.domain.money.Money;
import com.github.h3yow.loanservicingjava.domain.payment.InstallmentAllocation;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentAllocation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 贷款聚合根。所有账务变更（计提、分摊、挂账、状态推进）必须经过本类型，
 * 以保证“计划值 = 已还 + 未还”等科目恒等式不被外部破坏。
 *
 * <p>计息约定：日利率 = 年化利率 / 360（30/360 惯例），按天单利计提；
 * 罚息基数为逾期分期的未结本金，逾期首日 = 到期日 + 宽限期 + 1。
 */
public final class Loan {

    /** 30/360 惯例的年度天数基数。 */
    public static final int DAY_BASIS = 360;
    /** 基点精度：年化利率以基点（万分之一）存储。 */
    private static final long BPS_DENOMINATOR = 10_000L;

    private final String loanId;
    private final int penaltyRateBps;
    private final int graceDays;
    private final List<Installment> installments;

    /** 客户溢缴款挂账余额（不属于任何科目，等待后续冲抵或退回）。 */
    private Money suspenseBalance = Money.ZERO;
    private LoanStatus status = LoanStatus.NORMAL;
    private long version = 0L;

    private final transient List<DomainEvent> pendingEvents = new ArrayList<>();

    private Loan(String loanId, int penaltyRateBps, int graceDays, List<Installment> schedule) {
        this.loanId = Objects.requireNonNull(loanId, "loanId");
        if (penaltyRateBps < 0 || graceDays < 0) {
            throw new IllegalArgumentException("rate/grace must be non-negative");
        }
        this.penaltyRateBps = penaltyRateBps;
        this.graceDays = graceDays;
        List<Installment> sorted = new ArrayList<>(schedule);
        sorted.sort(Comparator.comparing(Installment::getDueDate)
                .thenComparingInt(Installment::getInstallmentNo));
        this.installments = List.copyOf(sorted);
    }

    /** 按还款计划创建贷款。 */
    public static Loan create(String loanId, int penaltyRateBps, int graceDays,
                              List<Installment> schedule) {
        if (schedule == null || schedule.isEmpty()) {
            throw new IllegalArgumentException("schedule must not be empty");
        }
        Loan loan = new Loan(loanId, penaltyRateBps, graceDays, schedule);
        loan.record(DomainEvent.builder(EventType.LOAN_CREATED)
                .businessDate(LocalDate.now())
                .summary("贷款建档")
                .attr("installmentCount", loan.installments.size())
                .attr("penaltyRateBps", penaltyRateBps)
                .attr("graceDays", graceDays)
                .attr("totalPrincipal",
                        loan.installments.stream().map(Installment::getScheduledPrincipal)
                                .reduce(Money.ZERO, Money::plus))
                .attr("totalInterest",
                        loan.installments.stream().map(Installment::getScheduledInterest)
                                .reduce(Money.ZERO, Money::plus))
                .build(), LocalDate.now());
        return loan;
    }

    public String getLoanId() {
        return loanId;
    }

    public int getPenaltyRateBps() {
        return penaltyRateBps;
    }

    public int getGraceDays() {
        return graceDays;
    }

    public List<Installment> getInstallments() {
        return installments;
    }

    public Money getSuspenseBalance() {
        return suspenseBalance;
    }

    public LoanStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Money totalDue() {
        return installments.stream().map(Installment::totalDue).reduce(Money.ZERO, Money::plus);
    }

    public Money unpaidPrincipal() {
        return installments.stream().map(Installment::unpaidPrincipal).reduce(Money.ZERO, Money::plus);
    }

    public Money unpaidInterest() {
        return installments.stream().map(Installment::unpaidInterest).reduce(Money.ZERO, Money::plus);
    }

    public Money unpaidPenalty() {
        return installments.stream().map(Installment::unpaidPenalty).reduce(Money.ZERO, Money::plus);
    }

    /**
     * 罚息计提（幂等）：计提区间为 (lastPenaltyAccrualDate, asOf]。
     * 首次计提锚点为“到期日 + 宽限期”，因此不会对宽限期内或未到期分期计提；
     * 同一业务日期重复调用不会重复计收。
     */
    public void accruePenalty(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        for (Installment inst : installments) {
            if (inst.isPaid()) {
                continue;
            }
            LocalDate anchor = inst.getLastPenaltyAccrualDate() != null
                    ? inst.getLastPenaltyAccrualDate()
                    : inst.getDueDate().plusDays(graceDays);
            long days = asOf.toEpochDay() - anchor.toEpochDay();
            if (days <= 0) {
                continue;
            }
            Money base = inst.unpaidPrincipal();
            if (!base.isPositive()) {
                // 本金已结但利息/罚息尾差未结：不再产生新罚息，仅推进锚点避免重复判定。
                inst.markPenaltyAccruedThrough(asOf);
                continue;
            }
            Money amount = base.prorate((long) penaltyRateBps * days, DAY_BASIS * BPS_DENOMINATOR);
            if (amount.isPositive()) {
                inst.addAccruedPenalty(amount);
                record(DomainEvent.builder(EventType.PENALTY_ACCRUED)
                        .businessDate(asOf)
                        .summary("罚息计提")
                        .attr("installmentNo", inst.getInstallmentNo())
                        .attr("accrualFrom", anchor.plusDays(1))
                        .attr("accrualThrough", asOf)
                        .attr("days", days)
                        .attr("basePrincipal", base)
                        .attr("penaltyRateBps", penaltyRateBps)
                        .attr("accruedPenalty", amount)
                        .attr("accruedPenaltyTotal", inst.getAccruedPenalty())
                        .build(), asOf);
            }
            inst.markPenaltyAccruedThrough(asOf);
        }
        version++;
    }

    /**
     * 按业务日期刷新逾期状态与账龄桶。已结清分期不会再翻转回逾期（先计提后判定，
     * 且已结分期 unpaidPrincipal 为零，不再产生新罚息）。
     */
    public void refreshOverdueStatus(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        for (Installment inst : installments) {
            boolean wasOverdue = inst.getStatus() == InstallmentStatus.OVERDUE;
            Bucket oldBucket = inst.getBucket();
            boolean bucketChanged = inst.refreshStatus(asOf, graceDays);
            if (!wasOverdue && inst.getStatus() == InstallmentStatus.OVERDUE) {
                record(DomainEvent.builder(EventType.INSTALLMENT_OVERDUE)
                        .businessDate(asOf)
                        .summary("分期进入逾期")
                        .attr("installmentNo", inst.getInstallmentNo())
                        .attr("dueDate", inst.getDueDate())
                        .attr("graceDays", graceDays)
                        .attr("overdueStart", inst.getDueDate().plusDays(graceDays + 1L))
                        .attr("judgedOn", asOf)
                        .attr("overdueDays", inst.getOverdueDays())
                        .attr("basis", "asOf 晚于 dueDate+graceDays")
                        .build(), asOf);
            }
            if (bucketChanged && oldBucket != Bucket.CURRENT) {
                record(DomainEvent.builder(EventType.BUCKET_CHANGED)
                        .businessDate(asOf)
                        .summary("账龄迁移")
                        .attr("installmentNo", inst.getInstallmentNo())
                        .attr("fromBucket", oldBucket)
                        .attr("toBucket", inst.getBucket())
                        .attr("overdueDays", inst.getOverdueDays())
                        .attr("judgedOn", asOf)
                        .build(), asOf);
            }
        }
        LoanStatus newStatus = deriveStatus();
        if (newStatus != status) {
            record(DomainEvent.builder(EventType.LOAN_STATUS_CHANGED)
                    .businessDate(asOf)
                    .summary("贷款状态变化")
                    .attr("fromStatus", status)
                    .attr("toStatus", newStatus)
                    .attr("judgedOn", asOf)
                    .build(), asOf);
            status = newStatus;
        }
        version++;
    }

    private LoanStatus deriveStatus() {
        boolean anyOverdue = installments.stream()
                .anyMatch(i -> i.getStatus() == InstallmentStatus.OVERDUE);
        if (anyOverdue) {
            return LoanStatus.OVERDUE;
        }
        boolean allPaid = installments.stream().allMatch(Installment::isPaid);
        if (allPaid && suspenseBalance.isZero()) {
            return LoanStatus.SETTLED;
        }
        return LoanStatus.NORMAL;
    }

    /**
     * 计算分摊方案（纯函数，不落账）。
     * 顺序：先罚息、再利息、后本金；每个科目内按到期日从早到晚。
     * 到期口径：仅 dueDate &lt;= asOf 的分期（已到期/逾期）参与自动分摊；
     * 未到期分期不被提前还本，超出到期应还的现金留给调用方挂账。
     */
    public PaymentAllocation computeAllocation(Money requested, LocalDate asOf) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(asOf, "asOf");
        List<Installment> eligible = installments.stream()
                .filter(i -> !i.getDueDate().isAfter(asOf))
                .toList();
        Money totalDue = eligible.stream().map(Installment::totalDue).reduce(Money.ZERO, Money::plus);
        Money remaining = requested;
        Map<Integer, InstallmentAllocation> lineMap = new LinkedHashMap<>();
        for (Installment inst : installments) {
            lineMap.put(inst.getInstallmentNo(),
                    new InstallmentAllocation(inst.getInstallmentNo(),
                            Money.ZERO, Money.ZERO, Money.ZERO));
        }
        remaining = allocateSubject(remaining, lineMap, eligible, Subject.PENALTY);
        remaining = allocateSubject(remaining, lineMap, eligible, Subject.INTEREST);
        remaining = allocateSubject(remaining, lineMap, eligible, Subject.PRINCIPAL);

        Money applied = requested.minus(remaining);
        List<InstallmentAllocation> lines = lineMap.values().stream()
                .filter(a -> !a.isEmpty())
                .toList();
        return new PaymentAllocation(requested, totalDue, lines, applied, remaining);
    }

    private enum Subject { PENALTY, INTEREST, PRINCIPAL }

    private Money allocateSubject(Money remaining, Map<Integer, InstallmentAllocation> lineMap,
                                  List<Installment> eligible, Subject subject) {
        for (Installment inst : eligible) {
            if (remaining.isZero()) {
                break;
            }
            Money owed = switch (subject) {
                case PENALTY -> inst.unpaidPenalty();
                case INTEREST -> inst.unpaidInterest();
                case PRINCIPAL -> inst.unpaidPrincipal();
            };
            if (!owed.isPositive()) {
                continue;
            }
            Money take = remaining.min(owed);
            InstallmentAllocation old = lineMap.get(inst.getInstallmentNo());
            InstallmentAllocation next = switch (subject) {
                case PENALTY -> new InstallmentAllocation(inst.getInstallmentNo(),
                        old.penalty().plus(take), old.interest(), old.principal());
                case INTEREST -> new InstallmentAllocation(inst.getInstallmentNo(),
                        old.penalty(), old.interest().plus(take), old.principal());
                case PRINCIPAL -> new InstallmentAllocation(inst.getInstallmentNo(),
                        old.penalty(), old.interest(), old.principal().plus(take));
            };
            lineMap.put(inst.getInstallmentNo(), next);
            remaining = remaining.minus(take);
        }
        return remaining;
    }

    /** 提交分摊方案到科目，并重算状态。调用方须保证方案由 {@link #computeAllocation} 产生。 */
    public void commitAllocation(PaymentAllocation allocation, String requestId, LocalDate asOf) {
        for (InstallmentAllocation line : allocation.lines()) {
            Installment inst = installments.stream()
                    .filter(i -> i.getInstallmentNo() == line.installmentNo())
                    .findFirst().orElseThrow();
            inst.applyPayment(line.penalty(), line.interest(), line.principal());
            inst.refreshStatus(asOf, graceDays);
        }
        LoanStatus newStatus = deriveStatus();
        if (newStatus != status) {
            record(DomainEvent.builder(EventType.LOAN_STATUS_CHANGED)
                    .businessDate(asOf)
                    .requestId(requestId)
                    .summary("还款导致贷款状态变化")
                    .attr("fromStatus", status)
                    .attr("toStatus", newStatus)
                    .attr("judgedOn", asOf)
                    .build(), asOf);
            status = newStatus;
        }
        record(DomainEvent.builder(EventType.PAYMENT_APPLIED)
                .businessDate(asOf)
                .requestId(requestId)
                .summary("还款分摊入账")
                .attr("requestedAmount", allocation.requestedAmount())
                .attr("appliedAmount", allocation.applied())
                .attr("remainingAmount", allocation.remaining())
                .attr("totalDueBefore", allocation.totalDueBefore())
                .attr("unpaidPrincipalAfter", unpaidPrincipal())
                .attr("unpaidInterestAfter", unpaidInterest())
                .attr("unpaidPenaltyAfter", unpaidPenalty())
                .build(), asOf);
        version++;
    }

    /** 溢缴部分挂账。 */
    public void holdSuspense(Money amount, String requestId, LocalDate asOf) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("suspense amount must be positive");
        }
        suspenseBalance = suspenseBalance.plus(amount);
        record(DomainEvent.builder(EventType.SUSPENSE_HELD)
                .businessDate(asOf)
                .requestId(requestId)
                .summary("溢缴款挂账")
                .attr("heldAmount", amount)
                .attr("suspenseBalanceAfter", suspenseBalance)
                .build(), asOf);
        version++;
    }

    /**
     * 用挂账余额冲抵当前应还。若挂账大于应还，剩余继续保留在挂账科目。
     */
    public PaymentAllocation applySuspense(String requestId, LocalDate asOf) {
        if (suspenseBalance.isZero()) {
            throw new IllegalStateException("no suspense balance");
        }
        Money before = suspenseBalance;
        PaymentAllocation allocation = computeAllocation(suspenseBalance, asOf);
        commitAllocation(allocation, requestId, asOf);
        suspenseBalance = allocation.remaining();
        record(DomainEvent.builder(EventType.SUSPENSE_APPLIED)
                .businessDate(asOf)
                .requestId(requestId)
                .summary("挂账款冲抵应还")
                .attr("suspenseBefore", before)
                .attr("appliedAmount", allocation.applied())
                .attr("suspenseAfter", suspenseBalance)
                .build(), asOf);
        version++;
        return allocation;
    }

    private void record(DomainEvent event, LocalDate businessDate) {
        pendingEvents.add(new DomainEvent(event.eventId(), event.type(), loanId,
                event.requestId(), businessDate, event.occurredAt(),
                event.summary(), event.attributes()));
    }

    /** 取出并清空待发布事件。 */
    public List<DomainEvent> drainEvents() {
        List<DomainEvent> events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return events;
    }
}

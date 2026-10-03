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

    /** 只增账务动作台账：每次成功还款/挂账冲抵留痕，是撤销重放的依据。 */
    private final List<LedgerEntry> ledger = new ArrayList<>();
    private long nextLedgerSequence = 0L;
    /** 已成功撤销的还款：paymentRequestId → reversalRequestId（因果链）。 */
    private final Map<String, String> reversedBy = new LinkedHashMap<>();
    /** 撤销重放进行中：为 true 时不产生领域事件、不追加台账，只重建科目状态。 */
    private boolean replaying = false;

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

    /** 只读账务动作台账（按发生顺序）。 */
    public List<LedgerEntry> getLedger() {
        return List.copyOf(ledger);
    }

    /** 该贷款上是否存在对应幂等键的成功账务动作（还款或挂账冲抵）。 */
    public boolean hasLedgerEntry(String requestId) {
        return ledger.stream().anyMatch(e -> e.requestId().equals(requestId));
    }

    /** 按幂等键查找账务动作台账条目。 */
    public java.util.Optional<LedgerEntry> findLedgerEntry(String requestId) {
        return ledger.stream().filter(e -> e.requestId().equals(requestId)).findFirst();
    }

    /** 某笔还款是否已被成功撤销；若是，返回执行撤销的 reversalRequestId。 */
    public java.util.Optional<String> reversalOf(String paymentRequestId) {
        return java.util.Optional.ofNullable(reversedBy.get(paymentRequestId));
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

    /**
     * 受理一笔成功还款的完整落账：提交科目分摊，若有溢缴则挂账，并在台账留痕。
     * 台账只记录真正成功的还款（拒绝/重放/冲突不产生台账），供撤销重放与事后追溯。
     * 调用方须保证 allocation 由 {@link #computeAllocation} 产生。
     *
     * @return 该笔还款的台账条目
     */
    public LedgerEntry acceptPaymentEntry(PaymentAllocation allocation, String requestId, LocalDate asOf) {
        commitAllocation(allocation, requestId, asOf);
        if (allocation.hasSurplus()) {
            holdSuspense(allocation.remaining(), requestId, asOf);
        }
        return appendLedger(LedgerEntry.Kind.PAYMENT, requestId, asOf,
                allocation.requestedAmount(), allocation.lines());
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
        appendLedger(LedgerEntry.Kind.SUSPENSE_SWEEP, requestId, asOf, before, allocation.lines());
        version++;
        return allocation;
    }

    /** 追加一条只增账务台账（撤销重放期间不追加，避免重放动作二次留痕）。 */
    private LedgerEntry appendLedger(LedgerEntry.Kind kind, String requestId, LocalDate asOf,
                                     Money requestedAmount, List<InstallmentAllocation> lines) {
        if (replaying) {
            throw new IllegalStateException("must not append ledger while replaying");
        }
        LedgerEntry entry = new LedgerEntry(nextLedgerSequence++, kind, requestId, asOf,
                requestedAmount, List.copyOf(lines));
        ledger.add(entry);
        return entry;
    }

    private void record(DomainEvent event, LocalDate businessDate) {
        if (replaying) {
            // 重放是在重建“当前状态”，历史事件已在首次发生时入流水，不重复产生。
            return;
        }
        pendingEvents.add(new DomainEvent(event.eventId(), event.type(), loanId,
                event.requestId(), businessDate, event.occurredAt(),
                event.summary(), event.attributes()));
    }

    /**
     * 撤销（冲正）一笔已成功受理的还款：把贷款账务重建为“该笔还款从未发生”的状态。
     *
     * <p>实现方式为<b>事件重放</b>：清空全部科目/挂账/状态后，按台账原顺序重放除目标还款以外的
     * 所有账务动作——每个动作在其<b>原始业务日期</b>重新计提罚息、重新按当时应还计算分摊
     * （挂账冲抵同样基于重放后的挂账余额重新计算）。由此保证：
     * <ul>
     *   <li>不会出现负余额/透支：每条重放动作都是在“此前重放状态”上对真实应还的合法冲抵；</li>
     *   <li>科目恒等式与现金守恒在重放过程中每一步都成立；</li>
     *   <li>目标还款之后发生的逾期推进、后续还款、挂账冲抵（哪怕动到更靠后的期）都被安全地
     *       重新演算，挂账冲抵顺序自动后移，绝不只改一半；</li>
     *   <li>目标还款结清某期后、撤销前区间内的罚息会按“该期本金一直未结”重新补提，
     *       得到与“这笔还款从没发生过”一致、可复现的账龄与罚息结论。</li>
     * </ul>
     * 调用方须已在贷款锁内完成“贷款存在、目标为可撤销还款、未被撤销”等校验。
     *
     * @return 该笔还款退给客户的现金额（其原始还款金额）
     */
    public Money reversePayment(String paymentRequestId, String reversalRequestId, LocalDate asOf) {
        Objects.requireNonNull(paymentRequestId, "paymentRequestId");
        Objects.requireNonNull(reversalRequestId, "reversalRequestId");
        Objects.requireNonNull(asOf, "asOf");
        LedgerEntry target = ledger.stream()
                .filter(e -> e.requestId().equals(paymentRequestId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("ledger entry not found: " + paymentRequestId));
        if (target.kind() != LedgerEntry.Kind.PAYMENT) {
            throw new IllegalStateException("target is not a payment: " + paymentRequestId);
        }
        if (reversedBy.containsKey(paymentRequestId)) {
            throw new IllegalStateException("payment already reversed: " + paymentRequestId);
        }

        Money refund = target.requestedAmount();
        // 1) 重置为放款初始状态（计划值不变，科目/罚息锚点/账龄清零，挂账清零）
        for (Installment inst : installments) {
            inst.replayReset();
        }
        suspenseBalance = Money.ZERO;
        status = LoanStatus.NORMAL;

        // 2) 重放其余账务动作（抑制事件与台账追加）
        replaying = true;
        try {
            for (LedgerEntry entry : ledger) {
                // 跳过本次目标，以及此前已被撤销的全部动作（支持撤销→重新还款→再撤销链）
                if (entry.requestId().equals(paymentRequestId)
                        || reversedBy.containsKey(entry.requestId())) {
                    continue;
                }
                LocalDate d = entry.businessDate();
                accruePenalty(d);
                refreshOverdueStatus(d);
                if (entry.kind() == LedgerEntry.Kind.PAYMENT) {
                    // 重新按当时应还计算分摊：目标还款消失后，后续还款的分摊自动重算
                    PaymentAllocation recomputed = computeAllocation(entry.requestedAmount(), d);
                    commitAllocation(recomputed, entry.requestId(), d);
                    if (recomputed.hasSurplus()) {
                        suspenseBalance = suspenseBalance.plus(recomputed.remaining());
                    }
                } else {
                    // 挂账冲抵：基于重放后的挂账余额与当时应还重新冲抵
                    Money sweepFrom = suspenseBalance;
                    PaymentAllocation recomputed = computeAllocation(sweepFrom, d);
                    commitAllocation(recomputed, entry.requestId(), d);
                    suspenseBalance = recomputed.remaining();
                }
            }
        } finally {
            replaying = false;
        }

        // 3) 记录撤销因果并按撤销业务日期重算罚息/逾期账龄（这些事件真实发生，正常入流水）
        reversedBy.put(paymentRequestId, reversalRequestId);
        accruePenalty(asOf);
        refreshOverdueStatus(asOf);
        record(DomainEvent.builder(EventType.PAYMENT_REVERSED)
                .businessDate(asOf)
                .requestId(reversalRequestId)
                .summary("已受理还款撤销/冲正，账务按事件重放回退")
                .attr("paymentRequestId", paymentRequestId)
                .attr("reversalRequestId", reversalRequestId)
                .attr("paymentBusinessDate", target.businessDate())
                .attr("refundAmount", refund)
                .attr("unpaidPrincipalAfter", unpaidPrincipal())
                .attr("unpaidInterestAfter", unpaidInterest())
                .attr("unpaidPenaltyAfter", unpaidPenalty())
                .attr("suspenseBalanceAfter", suspenseBalance)
                .attr("replayedEntryCount", ledger.size() - 1)
                .attr("basis", "跳过目标还款，其余台账按原业务日期重放")
                .build(), asOf);
        version++;
        return refund;
    }

    /** 取出并清空待发布事件。 */
    public List<DomainEvent> drainEvents() {
        List<DomainEvent> events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return events;
    }
}

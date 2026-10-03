package com.github.h3yow.loanservicingjava.tests;

import com.github.h3yow.loanservicingjava.domain.event.DomainEvent;
import com.github.h3yow.loanservicingjava.domain.event.EventType;
import com.github.h3yow.loanservicingjava.domain.loan.Installment;
import com.github.h3yow.loanservicingjava.domain.loan.Loan;
import com.github.h3yow.loanservicingjava.domain.money.Money;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentCommand;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentOutcome;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentResult;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalCommand;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalOutcome;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalResult;
import com.github.h3yow.loanservicingjava.repo.EventStore;
import com.github.h3yow.loanservicingjava.service.LoanService;
import com.github.h3yow.loanservicingjava.service.OverdueService;
import com.github.h3yow.loanservicingjava.service.PaymentService;
import com.github.h3yow.loanservicingjava.service.ReconciliationReport;
import com.github.h3yow.loanservicingjava.service.ReconciliationService;
import com.github.h3yow.loanservicingjava.service.ReversalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已受理还款撤销/冲正：回退正确性、幂等重放、并发唯一生效、后续账务变动后撤销、
 * 异常输入可区分结果。每个撤销场景结束都强制核对科目恒等式与现金守恒。
 */
@SpringBootTest
class ReversalTest {

    private static final Logger log = LoggerFactory.getLogger(ReversalTest.class);
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 23);

    @Autowired private ReversalService reversalService;
    @Autowired private PaymentService paymentService;
    @Autowired private OverdueService overdueService;
    @Autowired private LoanService loanService;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private EventStore eventStore;

    @BeforeEach
    void noteStart() {
        log.info("==================== 撤销用例开始 ====================");
    }

    private String twoInstallmentLoan(String prefix) {
        String id = prefix + "-" + UUID.randomUUID();
        loanService.createLoan(id, 1800, 3, List.of(
                new LoanService.InstallmentSpec(1, AS_OF.minusDays(10),
                        new BigDecimal("1000"), new BigDecimal("30")),
                new LoanService.InstallmentSpec(2, AS_OF.plusDays(20),
                        new BigDecimal("1000"), new BigDecimal("30"))));
        return id;
    }

    /** 现金守恒：科目已还总额 + 当前挂账 = 所有未撤销还款的现金之和。 */
    private Money cashInLedger(Loan loan) {
        return loan.getInstallments().stream()
                .map(i -> i.getPaidPrincipal().plus(i.getPaidInterest()).plus(i.getPaidPenalty()))
                .reduce(Money.ZERO, Money::plus)
                .plus(loan.getSuspenseBalance());
    }

    private void assertReconciled(String loanId, LocalDate asOf) {
        ReconciliationReport report = reconciliationService.reconcile(loanId, asOf);
        assertTrue(report.balanced(), "撤销后对账必须平衡: " + report.violations());
        log.info("[对账结果] balanced=true, paid(P/I/N)={}/{}/{}, suspense={}, violations=0",
                report.paidPrincipal(), report.paidInterest(), report.paidPenalty(),
                report.suspenseBalance());
    }

    @Test
    @DisplayName("撤销部分还款：科目/罚息/状态回到该笔还款从未发生，区间罚息按本金一直未结补提")
    void reversePartialPayment_restoresLedgerAndReaccrues() {
        String loanId = twoInstallmentLoan("REV");
        // 先判定，第1期逾期，罚息计提7天=3.50
        overdueService.assess(loanId, AS_OF);
        Money penaltyAtPay = loanService.get(loanId).getInstallments().get(0).getAccruedPenalty();
        assertEquals(Money.yuan("3.50"), penaltyAtPay);

        // 还款200：罚息3.50+利息30+本金166.50
        PaymentResult pay = paymentService.acceptPayment(new PaymentCommand(
                "REV-P-1", loanId, new BigDecimal("200"), AS_OF));
        assertEquals(PaymentOutcome.ACCEPTED_PARTIAL, pay.outcome());
        log.info("[输入] 先判定再还款200：applied={}, 未还本金={}", pay.appliedAmount(),
                loanService.get(loanId).unpaidPrincipal());

        // 10天后撤销：区间7天按“本金一直未结1000”补提罚息 = 1000*18%*7/360=3.50，累计7.00
        LocalDate revDate = AS_OF.plusDays(7);
        ReversalResult rev = reversalService.reverse(new ReversalCommand(
                "REV-R-1", loanId, "REV-P-1", revDate));
        log.info("[判定依据] outcome={}, refund={}, 撤销日罚息累计={}",
                rev.outcome(), rev.refundAmount(), rev.unpaidPenaltyAfter());

        assertEquals(ReversalOutcome.REVERSED, rev.outcome());
        assertEquals(Money.yuan("200"), rev.refundAmount(), "退款=原还款现金200");
        Loan loan = loanService.get(loanId);
        Installment inst1 = loan.getInstallments().get(0);
        assertEquals(Money.ZERO, inst1.getPaidPrincipal(), "已还本金回退为0");
        assertEquals(Money.ZERO, inst1.getPaidInterest(), "已还利息回退为0");
        assertEquals(Money.ZERO, inst1.getPaidPenalty(), "已还罚息回退为0");
        assertEquals(Money.yuan("1000"), inst1.unpaidPrincipal());
        assertEquals(Money.yuan("30"), inst1.unpaidInterest());
        assertEquals(Money.yuan("7.00"), inst1.getAccruedPenalty(),
                "区间7天罚息按本金未结补提，累计3.50+3.50=7.00");
        assertEquals(Money.yuan("7.00"), inst1.unpaidPenalty());
        assertEquals(Money.ZERO, cashInLedger(loan), "现金守恒：还款已退，科目与挂账均无该笔现金");
        assertReconciled(loanId, revDate);

        // 因果链：PAYMENT_REVERSED 同时携带 paymentRequestId 与 reversalRequestId
        List<DomainEvent> revEvents = eventStore.findByLoan(loanId).stream()
                .filter(e -> e.type() == EventType.PAYMENT_REVERSED).toList();
        assertEquals(1, revEvents.size());
        assertEquals("REV-P-1", revEvents.get(0).attributes().get("paymentRequestId"));
        assertEquals("REV-R-1", revEvents.get(0).requestId());
        log.info("[追溯] PAYMENT_REVERSED 属性={}", revEvents.get(0).attributes());
    }

    @Test
    @DisplayName("重复撤销：同一撤销键重放返回 REPLAYED 不二次退账；换键再撤销返回 ALREADY_REVERSED")
    void duplicateReversal_replayedAndAlreadyReversed() {
        String loanId = twoInstallmentLoan("DUPR");
        paymentService.acceptPayment(new PaymentCommand(
                "DUPR-P-1", loanId, new BigDecimal("200"), AS_OF));

        ReversalResult first = reversalService.reverse(new ReversalCommand(
                "DUPR-R-1", loanId, "DUPR-P-1", AS_OF));
        ReversalResult replay = reversalService.reverse(new ReversalCommand(
                "DUPR-R-1", loanId, "DUPR-P-1", AS_OF));
        ReversalResult anotherKey = reversalService.reverse(new ReversalCommand(
                "DUPR-R-2", loanId, "DUPR-P-1", AS_OF));

        log.info("[判定依据] first={}, sameKey={}, anotherKey={}",
                first.outcome(), replay.outcome(), anotherKey.outcome());
        log.info("[关键状态] replay.refund={}, replay.replay={}, anotherKey.refund={}",
                replay.refundAmount(), replay.replay(), anotherKey.refundAmount());

        assertEquals(ReversalOutcome.REVERSED, first.outcome());
        assertEquals(ReversalOutcome.REPLAYED, replay.outcome());
        assertTrue(replay.replay());
        assertEquals(Money.ZERO, replay.refundAmount(), "重放不重复退账");
        assertEquals(first.unpaidPrincipalAfter(), replay.unpaidPrincipalAfter(),
                "重放返回首次撤销后的同一快照");
        assertEquals(ReversalOutcome.REJECTED_ALREADY_REVERSED, anotherKey.outcome());
        assertFalse(anotherKey.succeeded());
        assertEquals(Money.ZERO, anotherKey.refundAmount());
        assertEquals(Money.ZERO, cashInLedger(loanService.get(loanId)),
                "无论重复多少次，现金只退一次");
        assertReconciled(loanId, AS_OF);
    }

    @Test
    @DisplayName("同一笔还款被并发撤销：12 线程同撤销键，恰一次 REVERSED，其余 CONFLICT/REPLAYED，账只退一次")
    void concurrentSameReversalKey_onlyOneTakesEffect() throws Exception {
        String loanId = twoInstallmentLoan("CREV");
        paymentService.acceptPayment(new PaymentCommand(
                "CREV-P-1", loanId, new BigDecimal("300"), AS_OF));
        Money paidBefore = cashInLedger(loanService.get(loanId));
        assertEquals(Money.yuan("300"), paidBefore);

        int threads = 12;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<ReversalResult>> futures = new java.util.ArrayList<>();

        log.info("[输入] {} 个线程用同一 reversalRequestId=CREV-R-1 同时撤销还款 CREV-P-1", threads);
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return reversalService.reverse(new ReversalCommand(
                        "CREV-R-1", loanId, "CREV-P-1", AS_OF));
            }));
        }
        ready.await();
        start.countDown();

        List<ReversalResult> results = new java.util.ArrayList<>();
        for (var f : futures) {
            results.add(f.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        pool.shutdown();

        long reversed = results.stream().filter(ReversalResult::succeeded).count();
        long conflicts = results.stream()
                .filter(r -> r.outcome() == ReversalOutcome.CONFLICT_CONCURRENT).count();
        long replayed = results.stream()
                .filter(r -> r.outcome() == ReversalOutcome.REPLAYED).count();
        log.info("[判定依据] CAS 占位成功数=1；赢家持锁期间 PROCESSING→CONFLICT_CONCURRENT，"
                + "释放锁后 COMPLETED→REPLAYED，二者均不退账");
        log.info("[关键状态] reversed={}, conflict={}, replayed={}", reversed, conflicts, replayed);

        assertEquals(1, reversed, "同一笔还款并发撤销只能真正生效一次");
        assertEquals(threads - 1, conflicts + replayed, "其余必须是并发冲突或重放");
        assertEquals(Money.ZERO, cashInLedger(loanService.get(loanId)),
                "并发撤销后现金只退一次，无重复退账/透支");
        assertReconciled(loanId, AS_OF);
    }

    @Test
    @DisplayName("同一笔还款被不同撤销键并发撤销：至多一次成功，另一键得到 ALREADY_REVERSED/冲突，账只退一次")
    void concurrentDifferentReversalKeys_onlyOneTakesEffect() throws Exception {
        String loanId = twoInstallmentLoan("DREV");
        paymentService.acceptPayment(new PaymentCommand(
                "DREV-P-1", loanId, new BigDecimal("300"), AS_OF));

        int threads = 8;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<ReversalResult>> futures = new java.util.ArrayList<>();

        log.info("[输入] {} 个线程各用不同 reversalRequestId 同时撤销同一笔还款 DREV-P-1", threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return reversalService.reverse(new ReversalCommand(
                        "DREV-R-" + idx, loanId, "DREV-P-1", AS_OF));
            }));
        }
        ready.await();
        start.countDown();

        List<ReversalResult> results = new java.util.ArrayList<>();
        for (var f : futures) {
            results.add(f.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        pool.shutdown();

        long reversed = results.stream().filter(ReversalResult::succeeded).count();
        long already = results.stream()
                .filter(r -> r.outcome() == ReversalOutcome.REJECTED_ALREADY_REVERSED).count();
        log.info("[关键状态] reversed={}, alreadyReversed={}", reversed, already);

        assertEquals(1, reversed, "不同撤销键并发撤销同一还款，仍只能有一次真正退账");
        assertEquals(threads - 1, already, "其余键必须被识别为该还款已撤销，不退账");
        assertEquals(Money.ZERO, cashInLedger(loanService.get(loanId)));
        assertReconciled(loanId, AS_OF);
    }

    @Test
    @DisplayName("复杂回退1：还款结清某期→逾期/账龄再推进→撤销，分期状态/罚息/账龄回到该还款从未发生")
    void reverseAfterFurtherOverdueAging_restoresInstallmentState() {
        String loanId = twoInstallmentLoan("AGE");
        LocalDate d1 = AS_OF;
        // 第1期到期应还 = 本金1000+利息30+罚息3.50 = 1033.50，全额结清
        PaymentResult pay = paymentService.acceptPayment(new PaymentCommand(
                "AGE-P-1", loanId, new BigDecimal("1033.50"), d1));
        assertEquals(PaymentOutcome.ACCEPTED_FULL, pay.outcome());
        assertEquals("PAID", loanService.get(loanId).getInstallments().get(0).getStatus().name());
        log.info("[输入] 还款1033.50 结清第1期，状态 PAID");

        // 账龄再推进 40 天：PAID 分期不翻转、不计新罚息
        LocalDate d2 = d1.plusDays(40);
        overdueService.assess(loanId, d2);
        Installment paidInst = loanService.get(loanId).getInstallments().get(0);
        assertEquals("PAID", paidInst.getStatus().name());
        Money penaltyWhilePaid = paidInst.getAccruedPenalty();
        log.info("[判定依据] 结清后推进40天，status=PAID 不翻转，罚息保持={}", penaltyWhilePaid);

        // 撤销：该期本金视为一直未结，需补提 40 天罚息 = 1000*18%*40/360=20.00，累计23.50
        ReversalResult rev = reversalService.reverse(new ReversalCommand(
                "AGE-R-1", loanId, "AGE-P-1", d2));
        Installment after = loanService.get(loanId).getInstallments().get(0);
        log.info("[关键状态] 撤销后 status={}, bucket={}, overdueDays={}, 罚息={}, 未还本金={}",
                after.getStatus(), after.getBucket(), after.getOverdueDays(),
                after.getAccruedPenalty(), after.unpaidPrincipal());

        assertEquals(ReversalOutcome.REVERSED, rev.outcome());
        assertEquals("OVERDUE", after.getStatus().name(), "撤销后该期恢复逾期，而非停留在 PAID");
        assertEquals("M2", after.getBucket().name(), "账龄按撤销日重算为 M2");
        assertEquals(50, after.getOverdueDays());
        assertEquals(Money.yuan("1000"), after.unpaidPrincipal());
        assertEquals(Money.yuan("30"), after.unpaidInterest());
        assertEquals(Money.yuan("23.50"), after.getAccruedPenalty(),
                "本金视为一直未结：前7天3.50 + 补提40天20.00 = 23.50");
        assertEquals(Money.ZERO, cashInLedger(loanService.get(loanId)), "现金守恒：唯一还款已退");
        assertReconciled(loanId, d2);
    }

    @Test
    @DisplayName("复杂回退2：还款挂账→挂账已冲抵到更靠后的期→撤销，挂账安全收回、后期分摊自动回退，无负余额")
    void reverseAfterSuspenseSwept_safeUnwindNoNegative() {
        // 两期：第1期 d1 到期，第2期 d1+30 到期
        LocalDate d1 = AS_OF;
        LocalDate d2 = d1.plusDays(30);
        String loanId = "SWP-" + UUID.randomUUID();
        loanService.createLoan(loanId, 1800, 3, List.of(
                new LoanService.InstallmentSpec(1, d1, new BigDecimal("1000"), new BigDecimal("30")),
                new LoanService.InstallmentSpec(2, d2, new BigDecimal("1000"), new BigDecimal("30"))));

        // 第1期 d1 刚到期：计提区间为空故罚息=0，应还=本金1000+利息30=1030；
        // 还款1500：入账1030结清第1期，470挂账
        PaymentResult overpay = paymentService.acceptPayment(new PaymentCommand(
                "SWP-P-1", loanId, new BigDecimal("1500"), d1));
        assertEquals(PaymentOutcome.ACCEPTED_SUSPENSE, overpay.outcome());
        assertEquals(Money.yuan("470"), loanService.get(loanId).getSuspenseBalance());

        // 第2期到期，挂账冲抵：利息30+本金440，挂账清零
        PaymentResult sweep = paymentService.applySuspense(loanId, "SWP-SWEEP", d2);
        assertEquals(Money.yuan("470"), sweep.appliedAmount());
        assertEquals(Money.yuan("560"), loanService.get(loanId).unpaidPrincipal());
        log.info("[输入] 还款1500→结清第1期+挂账470；挂账已冲抵第2期（利息30+本金440）");

        // 撤销原还款：挂账被抽回，冲抵第2期的动作在重放中无钱可冲 → 第2期自动恢复未还
        ReversalResult rev = reversalService.reverse(new ReversalCommand(
                "SWP-R-1", loanId, "SWP-P-1", d2));
        Loan loan = loanService.get(loanId);
        log.info("[判定依据] outcome={}, refund={}", rev.outcome(), rev.refundAmount());
        log.info("[关键状态] 挂账={}, 第2期未还本金={}/利息={}, 总未还本金={}",
                loan.getSuspenseBalance(),
                loan.getInstallments().get(1).unpaidPrincipal(),
                loan.getInstallments().get(1).unpaidInterest(),
                loan.unpaidPrincipal());

        assertEquals(ReversalOutcome.REVERSED, rev.outcome());
        assertEquals(Money.yuan("1500"), rev.refundAmount(), "整笔还款现金退回（含已被冲抵的挂账）");
        assertEquals(Money.ZERO, loan.getSuspenseBalance(), "挂账不允许出现负余额");
        assertEquals(Money.yuan("1000"), loan.getInstallments().get(1).unpaidPrincipal(),
                "挂账冲抵被安全回退，第2期本金恢复全额未还");
        assertEquals(Money.yuan("30"), loan.getInstallments().get(1).unpaidInterest(),
                "第2期利息恢复未还");
        assertEquals(Money.yuan("2000"), loan.unpaidPrincipal());
        // 第1期恢复逾期：撤销重放时锚点=到期+宽限(d1+3)，计提区间(d1+3,d2]=27天，
        // 罚息=1000*18%*27/360=13.50
        assertEquals(Money.yuan("13.50"), loan.getInstallments().get(0).getAccruedPenalty());
        assertEquals(Money.ZERO, cashInLedger(loan), "现金守恒：冲抵动作回退后无残留现金");
        assertReconciled(loanId, d2);
    }

    @Test
    @DisplayName("复杂回退3：两笔还款后撤销第一笔，第二笔按新的应还顺序重新分摊，结果与平行对照一致可复现")
    void reverseEarlierPayment_reallocatesLaterPayment_reproducible() {
        String target = twoInstallmentLoan("SEQ");
        String control = twoInstallmentLoan("SEQC");
        // 第1期逾期7天，罚息3.50
        overdueService.assess(target, AS_OF);
        overdueService.assess(control, AS_OF);

        // 目标贷款：先还200（罚息3.5+利息30+本金166.5），再还200
        paymentService.acceptPayment(new PaymentCommand(
                "T-P-1", target, new BigDecimal("200"), AS_OF));
        paymentService.acceptPayment(new PaymentCommand(
                "T-P-2", target, new BigDecimal("200"), AS_OF));
        log.info("[输入] 目标贷款两笔各200，当前未还本金={}",
                loanService.get(target).unpaidPrincipal());

        // 撤销第一笔：第二笔重放时直接冲 罚息3.5+利息30+本金166.5
        ReversalResult rev = reversalService.reverse(new ReversalCommand(
                "T-R-1", target, "T-P-1", AS_OF));
        assertEquals(ReversalOutcome.REVERSED, rev.outcome());

        // 平行对照：只还第二笔200（第一笔从未发生）
        PaymentResult onlySecond = paymentService.acceptPayment(new PaymentCommand(
                "C-P-2", control, new BigDecimal("200"), AS_OF));

        Loan t = loanService.get(target);
        Loan c = loanService.get(control);
        log.info("[判定依据] 撤销重放后 未还P/I/N={}/{}/{}；平行只还一笔 未还P/I/N={}/{}/{}",
                t.unpaidPrincipal(), t.unpaidInterest(), t.unpaidPenalty(),
                c.unpaidPrincipal(), c.unpaidInterest(), c.unpaidPenalty());

        assertEquals(c.unpaidPrincipal(), t.unpaidPrincipal(), "撤销第一笔后，账态须等价于只有第二笔");
        assertEquals(c.unpaidInterest(), t.unpaidInterest());
        assertEquals(c.unpaidPenalty(), t.unpaidPenalty());
        assertEquals(Money.yuan("200"), cashInLedger(t), "现金守恒：只剩第二笔200");
        assertEquals(Money.yuan("200"), cashInLedger(c));
        assertEquals(Money.yuan("200"), onlySecond.appliedAmount());
        assertReconciled(target, AS_OF);
        assertReconciled(control, AS_OF);
    }

    @Test
    @DisplayName("异常输入可区分：贷款不存在/还款不存在/目标是挂账冲抵/撤销键为空/指纹复用，均不静默成功")
    void invalidInputs_distinguishableBusinessResults() {
        String loanId = twoInstallmentLoan("INV");
        paymentService.acceptPayment(new PaymentCommand(
                "INV-P-1", loanId, new BigDecimal("100"), AS_OF));

        ReversalResult noLoan = reversalService.reverse(new ReversalCommand(
                "INV-R-X", "NO-SUCH-LOAN", "INV-P-1", AS_OF));
        ReversalResult noPayment = reversalService.reverse(new ReversalCommand(
                "INV-R-Y", loanId, "NO-SUCH-PAYMENT", AS_OF));

        // 挂账冲抵动作不是客户还款，不能按还款撤销
        paymentService.acceptPayment(new PaymentCommand(
                "INV-P-2", loanId, new BigDecimal("5000"), AS_OF));
        paymentService.applySuspense(loanId, "INV-SWEEP", AS_OF.plusDays(20));
        ReversalResult sweepTarget = reversalService.reverse(new ReversalCommand(
                "INV-R-Z", loanId, "INV-SWEEP", AS_OF.plusDays(20)));

        ReversalResult blankKey = reversalService.reverse(new ReversalCommand(
                "  ", loanId, "INV-P-1", AS_OF));
        ReversalResult nullAsOf = reversalService.reverse(new ReversalCommand(
                "INV-R-NULLDATE", loanId, "INV-P-1", null));

        // 快照取在所有（必然被拒绝的）撤销调用之后：它们合计不得改动任何账务
        Money cashAfterRejections = cashInLedger(loanService.get(loanId));

        log.info("[判定依据] noLoan={}, noPayment={}, sweepTarget={}, blankKey={}, nullAsOf={}",
                noLoan.outcome(), noPayment.outcome(), sweepTarget.outcome(),
                blankKey.outcome(), nullAsOf.outcome());

        assertEquals(ReversalOutcome.REJECTED_LOAN_NOT_FOUND, noLoan.outcome());
        assertEquals(ReversalOutcome.REJECTED_PAYMENT_NOT_FOUND, noPayment.outcome());
        assertEquals(ReversalOutcome.REJECTED_PAYMENT_NOT_REVERSIBLE, sweepTarget.outcome());
        assertEquals(ReversalOutcome.REJECTED_REQUEST_INVALID, blankKey.outcome());
        assertEquals(ReversalOutcome.REJECTED_REQUEST_INVALID, nullAsOf.outcome());
        assertFalse(noLoan.succeeded() || noPayment.succeeded() || sweepTarget.succeeded()
                || blankKey.succeeded() || nullAsOf.succeeded());

        // 再做一次只应拒绝的调用（目标还款不存在），现金必须纹丝不动
        reversalService.reverse(new ReversalCommand(
                "INV-R-AGAIN", loanId, "INV-P-NEVER", AS_OF.plusDays(20)));
        assertEquals(cashAfterRejections, cashInLedger(loanService.get(loanId)),
                "所有拒绝均不得改动账务");
        assertReconciled(loanId, AS_OF.plusDays(20));
    }

    @Test
    @DisplayName("撤销键指纹复用：同一 reversalRequestId 指向不同还款 → MISMATCH，不退账")
    void reversalKeyReusedForDifferentPayment_mismatch() {
        String loanId = twoInstallmentLoan("MISR");
        paymentService.acceptPayment(new PaymentCommand(
                "MISR-P-1", loanId, new BigDecimal("100"), AS_OF));
        paymentService.acceptPayment(new PaymentCommand(
                "MISR-P-2", loanId, new BigDecimal("100"), AS_OF));

        ReversalResult first = reversalService.reverse(new ReversalCommand(
                "MISR-R-1", loanId, "MISR-P-1", AS_OF));
        ReversalResult tampered = reversalService.reverse(new ReversalCommand(
                "MISR-R-1", loanId, "MISR-P-2", AS_OF));
        log.info("[判定依据] first={}, 同键指向另一笔还款={}", first.outcome(), tampered.outcome());

        assertEquals(ReversalOutcome.REVERSED, first.outcome());
        assertEquals(ReversalOutcome.REJECTED_IDEMPOTENCY_MISMATCH, tampered.outcome());
        // 只有第一笔被退，第二笔100仍在账
        assertEquals(Money.yuan("100"), cashInLedger(loanService.get(loanId)));
        assertReconciled(loanId, AS_OF);
    }

    @Test
    @DisplayName("全额挂账还款撤销：未入科目的溢缴款挂账被一并退回，挂账余额恢复且不出现负值")
    void reverseFullySuspendedPayment_refundsSuspense() {
        String loanId = twoInstallmentLoan("FSU");
        // 第1期已到期；还一笔超大金额，全部到期应还结清后仍有大额挂账
        PaymentResult pay = paymentService.acceptPayment(new PaymentCommand(
                "FSU-P-1", loanId, new BigDecimal("9000"), AS_OF));
        assertEquals(PaymentOutcome.ACCEPTED_SUSPENSE, pay.outcome());
        Money suspenseBefore = loanService.get(loanId).getSuspenseBalance();
        log.info("[输入] 还款9000，入科目1033.50，挂账={}", suspenseBefore);

        ReversalResult rev = reversalService.reverse(new ReversalCommand(
                "FSU-R-1", loanId, "FSU-P-1", AS_OF));
        Loan loan = loanService.get(loanId);
        log.info("[关键状态] refund={}, 撤销后挂账={}, 未还本金={}",
                rev.refundAmount(), loan.getSuspenseBalance(), loan.unpaidPrincipal());

        assertEquals(ReversalOutcome.REVERSED, rev.outcome());
        assertEquals(Money.yuan("9000"), rev.refundAmount(), "整笔现金退回（入科目+挂账）");
        assertEquals(Money.ZERO, loan.getSuspenseBalance(), "挂账随撤销归零，无负值");
        assertEquals(Money.yuan("2000"), loan.unpaidPrincipal());
        assertEquals(Money.ZERO, cashInLedger(loan), "现金守恒：还款完全退回");
        assertReconciled(loanId, AS_OF);
    }

    @Test
    @DisplayName("撤销后可重新还款；撤销链（撤销A→新还款B→撤销B）结果可复现，恒等式始终成立")
    void reverseThenRepayThenReverseAgain_chainConsistent() {
        String loanId = twoInstallmentLoan("CHN");
        overdueService.assess(loanId, AS_OF);

        paymentService.acceptPayment(new PaymentCommand(
                "CHN-P-A", loanId, new BigDecimal("200"), AS_OF));
        ReversalResult revA = reversalService.reverse(new ReversalCommand(
                "CHN-R-A", loanId, "CHN-P-A", AS_OF));
        assertEquals(ReversalOutcome.REVERSED, revA.outcome());
        assertEquals(Money.ZERO, cashInLedger(loanService.get(loanId)), "撤销A后无现金残留");

        // 重新还款 B（相当于正常受理一笔新还款）
        PaymentResult payB = paymentService.acceptPayment(new PaymentCommand(
                "CHN-P-B", loanId, new BigDecimal("200"), AS_OF));
        assertEquals(PaymentOutcome.ACCEPTED_PARTIAL, payB.outcome());
        assertEquals(Money.yuan("200"), cashInLedger(loanService.get(loanId)));

        // 再撤销 B
        ReversalResult revB = reversalService.reverse(new ReversalCommand(
                "CHN-R-B", loanId, "CHN-P-B", AS_OF));
        Loan loan = loanService.get(loanId);
        log.info("[关键状态] 撤销链 revA={}, payB={}, revB={}, 最终未还P={}, 现金={}",
                revA.outcome(), payB.outcome(), revB.outcome(),
                loan.unpaidPrincipal(), cashInLedger(loan));

        assertEquals(ReversalOutcome.REVERSED, revB.outcome());
        assertEquals(Money.ZERO, cashInLedger(loan), "A、B 均已退，账上无客户现金");
        assertEquals(Money.yuan("2000"), loan.unpaidPrincipal());
        // 两笔撤销因果都在流水
        List<DomainEvent> revEvents = eventStore.findByLoan(loanId).stream()
                .filter(e -> e.type() == EventType.PAYMENT_REVERSED).toList();
        assertEquals(2, revEvents.size());
        assertReconciled(loanId, AS_OF);
    }
}

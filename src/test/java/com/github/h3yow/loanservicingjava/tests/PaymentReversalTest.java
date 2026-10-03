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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已受理还款撤销/冲正链路：
 * 基础红冲、重复撤销、并发撤销、参数异常、挂账/后续账务变动后的安全回退或拒绝、
 * “这笔还款从没发生过”影子台账核对。每个撤销场景结束都强制对账恒等式 + 现金守恒。
 */
@SpringBootTest
class PaymentReversalTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentReversalTest.class);
    private static final LocalDate D0 = LocalDate.of(2026, 9, 23);

    @Autowired private PaymentService paymentService;
    @Autowired private ReversalService reversalService;
    @Autowired private LoanService loanService;
    @Autowired private OverdueService overdueService;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private EventStore eventStore;

    private String twoInstallmentLoan(String prefix) {
        String id = prefix + "-" + UUID.randomUUID();
        loanService.createLoan(id, 1800, 3, List.of(
                new LoanService.InstallmentSpec(1, D0.minusDays(10),
                        new BigDecimal("1000"), new BigDecimal("30")),
                new LoanService.InstallmentSpec(2, D0.plusDays(20),
                        new BigDecimal("1000"), new BigDecimal("30"))));
        return id;
    }

    @BeforeEach
    void noteStart() {
        log.info("==================== 撤销用例开始 ====================");
    }

    @Test
    @DisplayName("部分还款撤销：科目余额回到未还款状态，结果 REVERSED 且现金守恒")
    void reversePartialPayment_restoresBalances() {
        String loanId = twoInstallmentLoan("REV-P");
        PaymentResult pay = paymentService.acceptPayment(new PaymentCommand(
                "REV-P-PAY", loanId, new BigDecimal("200"), D0));
        Money paidPrincipalBefore = loanService.get(loanId).getInstallments().get(0).getPaidPrincipal();
        log.info("[输入] 先还款200（outcome={}），再撤销", pay.outcome());
        log.info("[关键状态] 撤销前 第1期 paidP={}, paidI={}, paidN={}",
                paidPrincipalBefore,
                loanService.get(loanId).getInstallments().get(0).getPaidInterest(),
                loanService.get(loanId).getInstallments().get(0).getPaidPenalty());

        ReversalResult rev = reversalService.reverse(new ReversalCommand(
                "REV-P-REV", loanId, "REV-P-PAY", D0));

        log.info("[判定依据] outcome={}, reversedApplied={}, reversedSuspense={}",
                rev.outcome(), rev.reversedAppliedAmount(), rev.reversedSuspenseAmount());
        Installment inst = loanService.get(loanId).getInstallments().get(0);
        log.info("[关键状态] 撤销后 第1期 paidP={}, paidI={}, paidN={}, 未还本金合计={}",
                inst.getPaidPrincipal(), inst.getPaidInterest(), inst.getPaidPenalty(),
                rev.unpaidPrincipalAfter());

        assertEquals(ReversalOutcome.REVERSED, rev.outcome());
        assertEquals(Money.yuan("200"), rev.reversedAppliedAmount());
        assertEquals(Money.ZERO, rev.reversedSuspenseAmount());
        assertEquals(Money.ZERO, inst.getPaidPrincipal());
        assertEquals(Money.ZERO, inst.getPaidInterest());
        assertEquals(Money.ZERO, inst.getPaidPenalty(), "原冲抵罚息 3.50 必须退回未结");
        assertEquals(Money.yuan("2000"), rev.unpaidPrincipalAfter());
        assertEquals(Money.yuan("60"), rev.unpaidInterestAfter());
        assertEquals(Money.yuan("3.50"), rev.unpaidPenaltyAfter(), "撤销不抹掉已计提罚息，只是恢复未结");
        assertReconciled(loanId, D0);
        // 现金守恒：撤销后没有任何现金留在科目或挂账
        assertEquals(Money.ZERO, totalCash(loanId), "撤销后现金科目必须全部退出，现金守恒");
        assertReversalLinked(loanId, "REV-P-PAY", "REV-P-REV");
    }

    @Test
    @DisplayName("撤销后用同一撤销 requestId 重放：REPLAYED，不再退账")
    void duplicateReversal_replayedNoDoubleRefund() {
        String loanId = twoInstallmentLoan("REV-DUP");
        paymentService.acceptPayment(new PaymentCommand(
                "REV-DUP-PAY", loanId, new BigDecimal("200"), D0));
        ReversalCommand cmd = new ReversalCommand(
                "REV-DUP-REV", loanId, "REV-DUP-PAY", D0);

        ReversalResult first = reversalService.reverse(cmd);
        Money unpaidAfterFirst = loanService.get(loanId).unpaidPrincipal();
        ReversalResult second = reversalService.reverse(cmd);

        log.info("[输入] 同一撤销 requestId=REV-DUP-REV 顺序提交两次");
        log.info("[判定依据] first={}, second={}, replay={}",
                first.outcome(), second.outcome(), second.replay());
        log.info("[关键状态] 首次后未还本金={}, 重放applied={}, 重放后未还本金={}",
                unpaidAfterFirst, second.reversedAppliedAmount(),
                loanService.get(loanId).unpaidPrincipal());

        assertEquals(ReversalOutcome.REVERSED, first.outcome());
        assertEquals(ReversalOutcome.REPLAYED, second.outcome());
        assertTrue(second.replay());
        assertEquals(Money.ZERO, second.reversedAppliedAmount(), "重放不产生第二次退账");
        assertEquals(unpaidAfterFirst, loanService.get(loanId).unpaidPrincipal());
        assertReconciled(loanId, D0);
    }

    @Test
    @DisplayName("换另一个撤销 requestId 重复撤销：REJECTED_ALREADY_REVERSED，不退账")
    void secondDistinctReversal_alreadyReversed() {
        String loanId = twoInstallmentLoan("REV-AR");
        paymentService.acceptPayment(new PaymentCommand(
                "REV-AR-PAY", loanId, new BigDecimal("200"), D0));
        ReversalResult first = reversalService.reverse(new ReversalCommand(
                "REV-AR-REV1", loanId, "REV-AR-PAY", D0));
        ReversalResult second = reversalService.reverse(new ReversalCommand(
                "REV-AR-REV2", loanId, "REV-AR-PAY", D0));

        log.info("[输入] 两个不同撤销 requestId 先后撤销同一笔还款");
        log.info("[判定依据] first={}, second={}, message={}",
                first.outcome(), second.outcome(), second.message());
        log.info("[关键状态] second.reversedApplied={}, 第1期paidP={}",
                second.reversedAppliedAmount(),
                loanService.get(loanId).getInstallments().get(0).getPaidPrincipal());

        assertEquals(ReversalOutcome.REVERSED, first.outcome());
        assertEquals(ReversalOutcome.REJECTED_ALREADY_REVERSED, second.outcome(),
                "重复撤销必须与重放/并发冲突用不同结果码区分");
        assertEquals(Money.ZERO, second.reversedAppliedAmount());
        assertReconciled(loanId, D0);
    }

    @Test
    @DisplayName("同一撤销 requestId 并发：恰有一次 REVERSED，其余 CONFLICT_CONCURRENT/REPLAYED，账只退一次")
    void concurrentReversal_onlyOneTakesEffect() throws Exception {
        String loanId = twoInstallmentLoan("REV-C");
        paymentService.acceptPayment(new PaymentCommand(
                "REV-C-PAY", loanId, new BigDecimal("300"), D0));
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReversalResult>> futures = new ArrayList<>();

        log.info("[输入] {} 个线程用同一撤销 requestId=REV-C-REV 并发撤销同一笔还款", threads);
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return reversalService.reverse(new ReversalCommand(
                        "REV-C-REV", loanId, "REV-C-PAY", D0));
            }));
        }
        ready.await();
        start.countDown();

        List<ReversalResult> results = new ArrayList<>();
        for (Future<ReversalResult> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long reversed = results.stream().filter(r -> r.outcome() == ReversalOutcome.REVERSED).count();
        long conflicts = results.stream()
                .filter(r -> r.outcome() == ReversalOutcome.CONFLICT_CONCURRENT).count();
        long replayed = results.stream().filter(r -> r.outcome() == ReversalOutcome.REPLAYED).count();
        log.info("[判定依据] CAS 占位成功数=1；赢家持锁期间→CONFLICT_CONCURRENT，释放锁后→REPLAYED");
        log.info("[关键状态] reversed={}, conflict={}, replayed={}", reversed, conflicts, replayed);
        results.forEach(r -> log.info("  线程结果: outcome={}, reversedApplied={}, replay={}",
                r.outcome(), r.reversedAppliedAmount(), r.replay()));

        assertEquals(1, reversed, "同一笔还款并发撤销只能真正生效一次");
        assertEquals(threads - 1, conflicts + replayed, "其余必须为并发冲突或重放，均不退账");
        Installment inst = loanService.get(loanId).getInstallments().get(0);
        assertEquals(Money.ZERO, inst.getPaidPrincipal().plus(inst.getPaidInterest())
                .plus(inst.getPaidPenalty()), "红冲只发生一次，无重复退账");
        assertEquals(Money.yuan("2000"), loanService.get(loanId).unpaidPrincipal());
        assertReconciled(loanId, D0);
        assertEquals(Money.ZERO, totalCash(loanId), "现金守恒：并发撤销后账上无该笔现金");
    }

    @Test
    @DisplayName("异常输入：贷款不存在/目标还款不存在/撤销不存在的键，均为可区分拒绝，无服务端异常")
    void invalidInputs_distinguishableRejections() {
        String loanId = twoInstallmentLoan("REV-INV");
        paymentService.acceptPayment(new PaymentCommand(
                "REV-INV-PAY", loanId, new BigDecimal("100"), D0));

        ReversalResult missingLoan = reversalService.reverse(new ReversalCommand(
                "REV-INV-R1", "NO-SUCH-LOAN", "REV-INV-PAY", D0));
        ReversalResult missingPayment = reversalService.reverse(new ReversalCommand(
                "REV-INV-R2", loanId, "NO-SUCH-PAYMENT", D0));
        // 对一笔被拒绝（未成功受理）的“还款”发起撤销，同样拒绝
        PaymentResult rejectedPay = paymentService.acceptPayment(new PaymentCommand(
                "REV-INV-BAD", loanId, new BigDecimal("-5"), D0));
        ReversalResult reverseRejected = reversalService.reverse(new ReversalCommand(
                "REV-INV-R3", loanId, "REV-INV-BAD", D0));

        log.info("[输入] 贷款不存在/还款不存在/撤销被拒还款 三类异常");
        log.info("[判定依据] missingLoan={}, missingPayment={}, rejectedPay.outcome={}, reverseRejected={}",
                missingLoan.outcome(), missingPayment.outcome(),
                rejectedPay.outcome(), reverseRejected.outcome());

        assertEquals(ReversalOutcome.REJECTED_PAYMENT_NOT_FOUND, missingLoan.outcome());
        assertEquals(ReversalOutcome.REJECTED_PAYMENT_NOT_FOUND, missingPayment.outcome());
        assertEquals(PaymentOutcome.REJECTED_AMOUNT_INVALID, rejectedPay.outcome());
        assertEquals(ReversalOutcome.REJECTED_PAYMENT_NOT_FOUND, reverseRejected.outcome(),
                "未成功受理的还款没有账务可撤，必须拒绝而非看似成功");
        // 原正常还款仍在，账未被任何拒绝请求改动
        assertEquals(Money.yuan("100"), totalCash(loanId), "拒绝撤销不得改动任何余额");
        assertReconciled(loanId, D0);
    }

    @Test
    @DisplayName("同一撤销 requestId 指纹不符：REJECTED_IDEMPOTENCY_MISMATCH")
    void reversalFingerprintMismatch_rejected() {
        String loanId = twoInstallmentLoan("REV-MIS");
        paymentService.acceptPayment(new PaymentCommand(
                "REV-MIS-P1", loanId, new BigDecimal("100"), D0));
        paymentService.acceptPayment(new PaymentCommand(
                "REV-MIS-P2", loanId, new BigDecimal("100"), D0.plusDays(1)));
        ReversalResult first = reversalService.reverse(new ReversalCommand(
                "REV-MIS-R", loanId, "REV-MIS-P1", D0));
        ReversalResult tampered = reversalService.reverse(new ReversalCommand(
                "REV-MIS-R", loanId, "REV-MIS-P2", D0));

        log.info("[输入] 撤销键 REV-MIS-R 先指向 P1 再指向 P2");
        log.info("[判定依据] first={}, tampered={}", first.outcome(), tampered.outcome());

        assertEquals(ReversalOutcome.REVERSED, first.outcome());
        assertEquals(ReversalOutcome.REJECTED_IDEMPOTENCY_MISMATCH, tampered.outcome());
        assertReconciled(loanId, D0);
    }

    @Test
    @DisplayName("撤销后原还款 requestId 重放仍是 REPLAYED；新 requestId 可重新还款，多轮往返账始终一致")
    void replayAfterReversal_andPayReverseAgain() {
        String loanId = twoInstallmentLoan("REV-RR");
        paymentService.acceptPayment(new PaymentCommand(
                "REV-RR-P1", loanId, new BigDecimal("200"), D0));
        reversalService.reverse(new ReversalCommand(
                "REV-RR-REV1", loanId, "REV-RR-P1", D0));
        assertEquals(Money.ZERO, totalCash(loanId), "撤销后无现金");

        // 原还款键重放：还款侧幂等记录仍在，返回 REPLAYED，不会把钱“还回来”
        PaymentResult replayPay = paymentService.acceptPayment(new PaymentCommand(
                "REV-RR-P1", loanId, new BigDecimal("200"), D0));
        log.info("[判定依据] 撤销后重放原还款 outcome={}（快照保留），账上现金={}",
                replayPay.outcome(), totalCash(loanId));
        assertEquals(PaymentOutcome.REPLAYED, replayPay.outcome());
        assertEquals(Money.ZERO, totalCash(loanId), "重放被撤销的还款不得重新入账");

        // 用新 requestId 重新还一笔，再撤销它；第二轮撤销的重放不包含已撤销的 P1
        PaymentResult p2 = paymentService.acceptPayment(new PaymentCommand(
                "REV-RR-P2", loanId, new BigDecimal("100"), D0));
        assertEquals(PaymentOutcome.ACCEPTED_PARTIAL, p2.outcome());
        assertEquals(Money.yuan("100"), totalCash(loanId));
        ReversalResult rev2 = reversalService.reverse(new ReversalCommand(
                "REV-RR-REV2", loanId, "REV-RR-P2", D0));
        assertEquals(ReversalOutcome.REVERSED, rev2.outcome());
        // 第二轮撤销重放时必须同时剔除历史已撤销的 P1，否则会把它“复活”
        assertEquals(Money.ZERO, totalCash(loanId), "二次撤销后 P1 不得被重放复活");
        assertEquals(Money.yuan("2000"), loanService.get(loanId).unpaidPrincipal());
        log.info("[关键状态] 多轮 还→撤→还→撤 后 unpaidPrincipal={}, cash=0",
                loanService.get(loanId).unpaidPrincipal());
        assertReconciled(loanId, D0);

        // 撤销日再跑一次逾期判定，结论可复现（撤销是幂等重建，后续评估不改变已重放结果的现金口径）
        overdueService.assess(loanId, D0);
        assertReconciled(loanId, D0);
        assertEquals(Money.ZERO, totalCash(loanId));
    }

    private Money totalCash(String loanId) {
        Loan loan = loanService.get(loanId);
        return loan.getInstallments().stream()
                .map(i -> i.getPaidPrincipal().plus(i.getPaidInterest()).plus(i.getPaidPenalty()))
                .reduce(Money.ZERO, Money::plus).plus(loan.getSuspenseBalance());
    }

    private void assertReconciled(String loanId, LocalDate asOf) {
        ReconciliationReport report = reconciliationService.reconcile(loanId, asOf);
        assertTrue(report.balanced(), "对账必须平衡: " + report.violations());
    }

    private void assertReversalLinked(String loanId, String paymentRequestId,
                                      String reversalRequestId) {
        List<DomainEvent> events = eventStore.findByLoan(loanId);
        DomainEvent revEvent = events.stream()
                .filter(e -> e.type() == EventType.PAYMENT_REVERSED
                        && reversalRequestId.equals(e.requestId()))
                .findFirst().orElseThrow(() -> new AssertionError("缺少 PAYMENT_REVERSED 事件"));
        assertEquals(paymentRequestId, revEvent.attributes().get("reversedPaymentRequestId"),
                "撤销事件必须能关联到被撤销的原还款 requestId，因果链可追溯");
        assertFalse(revEvent.summary().isBlank());
        log.info("[追溯] PAYMENT_REVERSED 属性={}", revEvent.attributes());
    }
}

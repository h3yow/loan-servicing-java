package com.github.h3yow.loanservicingjava.tests;

import com.github.h3yow.loanservicingjava.domain.loan.Loan;
import com.github.h3yow.loanservicingjava.domain.money.Money;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentCommand;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentOutcome;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentResult;
import com.github.h3yow.loanservicingjava.repo.IdempotencyRecord;
import com.github.h3yow.loanservicingjava.repo.IdempotencyStore;
import com.github.h3yow.loanservicingjava.service.LoanService;
import com.github.h3yow.loanservicingjava.service.PaymentService;
import com.github.h3yow.loanservicingjava.service.ReconciliationReport;
import com.github.h3yow.loanservicingjava.service.ReconciliationService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等与并发安全：重复提交 / 同请求并发 / 不同请求并发三类边界。
 * 每个用例后强制执行对账，证明不重复扣款、不漏记、不透支。
 */
@SpringBootTest
class PaymentIdempotencyConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentIdempotencyConcurrencyTest.class);
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 23);

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private LoanService loanService;
    @Autowired
    private ReconciliationService reconciliationService;
    @Autowired
    private IdempotencyStore idempotencyStore;

    private String newLoan(String prefix, String p1, String p2) {
        String loanId = prefix + "-" + UUID.randomUUID();
        loanService.createLoan(loanId, 1800, 3, List.of(
                new LoanService.InstallmentSpec(1, AS_OF.minusDays(10),
                        new BigDecimal(p1), new BigDecimal("30")),
                new LoanService.InstallmentSpec(2, AS_OF.plusDays(20),
                        new BigDecimal(p2), new BigDecimal("30"))));
        return loanId;
    }

    @BeforeEach
    void noteStart() {
        log.info("==================== 用例开始 ====================");
    }

    @Test
    @DisplayName("同一请求重复提交：第二次 REPLAYED，返回首次结果，只扣一次款")
    void duplicateSubmission_replayedNoDoubleCharge() {
        String loanId = newLoan("DUP", "1000", "1000");
        PaymentCommand cmd = new PaymentCommand("REQ-DUP-1", loanId,
                new BigDecimal("200"), AS_OF);
        log.info("[输入] 同一 requestId=REQ-DUP-1 顺序提交两次，金额=200");

        PaymentResult first = paymentService.acceptPayment(cmd);
        PaymentResult second = paymentService.acceptPayment(cmd);

        log.info("[判定依据] first.outcome={}, second.outcome={}, replay={}",
                first.outcome(), second.outcome(), second.replay());
        log.info("[关键状态] 首次applied={}, 重放applied={}, 未还本金={}",
                first.appliedAmount(), second.appliedAmount(),
                loanService.get(loanId).unpaidPrincipal());

        assertEquals(PaymentOutcome.ACCEPTED_PARTIAL, first.outcome());
        assertEquals(PaymentOutcome.REPLAYED, second.outcome());
        assertTrue(second.replay());
        assertEquals(Money.ZERO, second.appliedAmount(), "重放不产生新扣款");
        assertEquals(first.requestedAmount(), second.requestedAmount(), "重放返回同一请求的快照");
        assertEquals(first.unpaidPrincipalAfter(), second.unpaidPrincipalAfter(),
                "重放后账务快照与首次完成时一致");

        Loan loan = loanService.get(loanId);
        // 200 元先冲第1期已计提罚息3.50、利息30，剩余166.50冲第1期本金；只发生一次
        assertEquals(Money.yuan("1833.50"), loan.unpaidPrincipal());
        assertEquals(Money.yuan("200"), first.appliedAmount());
        assertReconciled(loanId);
    }

    @Test
    @DisplayName("同一 requestId 并发到达：恰有一次成功，其余为 CONFLICT_CONCURRENT，账务只变动一次")
    void concurrentSameRequestId_oneWinnerRestConflict() throws Exception {
        String loanId = newLoan("CCR", "1000", "1000");
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PaymentResult>> futures = new ArrayList<>();

        log.info("[输入] {} 个线程用同一 requestId=REQ-CONC-1 同时还款 300", threads);
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return paymentService.acceptPayment(new PaymentCommand(
                        "REQ-CONC-1", loanId, new BigDecimal("300"), AS_OF));
            }));
        }
        ready.await();
        start.countDown();

        List<PaymentResult> results = new ArrayList<>();
        for (Future<PaymentResult> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long accepted = results.stream().filter(r -> r.accepted() && !r.replay()).count();
        long conflicts = results.stream()
                .filter(r -> r.outcome() == PaymentOutcome.CONFLICT_CONCURRENT).count();
        long replayed = results.stream()
                .filter(r -> r.outcome() == PaymentOutcome.REPLAYED).count();
        log.info("[判定依据] CAS 占位成功数=1；赢家持锁期间读到 PROCESSING→CONFLICT_CONCURRENT；"
                + "赢家释放锁后读到 COMPLETED→REPLAYED；两者都不扣款");
        log.info("[关键状态] accepted={}, conflict={}, replayed={}", accepted, conflicts, replayed);
        results.forEach(r -> log.info("  线程结果: outcome={}, applied={}, replay={}",
                r.outcome(), r.appliedAmount(), r.replay()));

        assertEquals(1, accepted, "同一幂等键并发时只能有一笔成功扣款");
        assertEquals(threads - 1, conflicts + replayed, "其余请求必须是并发冲突或幂等重放");
        // 赢家完成得很快时，后到者可能全部看到 COMPLETED（REPLAYED），这也是正确结果；
        // CONFLICT_CONCURRENT 与 REPLAYED 的共同语义是“本次不扣款”，二者由结果码可区分。

        Loan loan = loanService.get(loanId);
        assertEquals(Money.yuan("300"), loan.getInstallments().stream()
                .map(i -> i.getPaidPrincipal().plus(i.getPaidInterest()).plus(i.getPaidPenalty()))
                .reduce(Money.ZERO, Money::plus), "总入账恰好 300，无重复扣款/漏记");
        assertReconciled(loanId);
    }

    @Test
    @DisplayName("幂等记录处于 PROCESSING 时到达：确定返回 CONFLICT_CONCURRENT，不扣款")
    void inflightRequest_returnsConflictWithoutCharge() {
        String loanId = newLoan("INF", "1000", "1000");
        idempotencyStore.putIfAbsent(new IdempotencyRecord(
                "REQ-INFLIGHT-1", loanId,
                new PaymentCommand("REQ-INFLIGHT-1", loanId, new BigDecimal("500"), AS_OF).fingerprint(),
                IdempotencyRecord.State.PROCESSING, null));

        log.info("[输入] REQ-INFLIGHT-1 的 PROCESSING 占位已存在，另一线程提交同键同额请求");
        PaymentResult result = paymentService.acceptPayment(new PaymentCommand(
                "REQ-INFLIGHT-1", loanId, new BigDecimal("500"), AS_OF));

        log.info("[判定依据] 锁内复查到 state=PROCESSING → CONFLICT_CONCURRENT，区别于 REPLAYED");
        log.info("[关键状态] outcome={}, applied={}", result.outcome(), result.appliedAmount());

        assertEquals(PaymentOutcome.CONFLICT_CONCURRENT, result.outcome());
        assertEquals(Money.ZERO, result.appliedAmount());
        assertEquals(Money.yuan("2000"), loanService.get(loanId).unpaidPrincipal(), "冲突请求不扣款");
        assertReconciled(loanId);
    }

    @Test
    @DisplayName("不同 requestId 并发部分还款：全部成功且总额精确，不透支、不丢失")
    void concurrentDifferentRequests_allAppliedExactly() throws Exception {
        String loanId = newLoan("CDR", "1000", "1000");
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PaymentResult>> futures = new ArrayList<>();

        log.info("[输入] {} 个不同 requestId 各还 100，合计 2000（仅第1期到期，应还 1033.50）", threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return paymentService.acceptPayment(new PaymentCommand(
                        "REQ-DIFF-" + idx, loanId, new BigDecimal("100"), AS_OF));
            }));
        }
        ready.await();
        start.countDown();

        Money appliedSum = Money.ZERO;
        int partial = 0;
        int suspense = 0;
        for (Future<PaymentResult> f : futures) {
            PaymentResult r = f.get(10, TimeUnit.SECONDS);
            appliedSum = appliedSum.plus(r.appliedAmount()).plus(r.suspenseHeld());
            if (r.outcome() == PaymentOutcome.ACCEPTED_PARTIAL) {
                partial++;
            }
            if (r.outcome() == PaymentOutcome.ACCEPTED_SUSPENSE) {
                suspense++;
            }
        }
        pool.shutdown();

        log.info("[判定依据] 分段锁串行化：到期应还1033.50 被逐笔吃满期间返回 PARTIAL；"
                + "到期科目结平后再有还款则挂账返回 SUSPENSE，未到期第2期不被提前还本");
        log.info("[关键状态] applied+suspense合计={}, partialCount={}, suspenseCount={}, 贷款状态={}",
                appliedSum, partial, suspense, loanService.get(loanId).getStatus());

        assertEquals(Money.yuan("2000"), appliedSum, "并发现金总额精确，不丢不重");
        assertTrue(partial >= 10, "至少前10笔处于部分还款，结果必须显式 PARTIAL");
        assertTrue(suspense >= 1, "到期应还结平后的还款必须显式挂账 SUSPENSE，不得静默");
        Loan loan = loanService.get(loanId);
        Money totalCash = loan.getInstallments().stream()
                .map(i -> i.getPaidPrincipal().plus(i.getPaidInterest()).plus(i.getPaidPenalty()))
                .reduce(Money.ZERO, Money::plus).plus(loan.getSuspenseBalance());
        assertEquals(Money.yuan("2000"), totalCash, "现金恒等式：入账现金 = 科目已还 + 挂账");
        assertEquals(Money.yuan("1000"), loan.unpaidPrincipal(),
                "未到期第2期本金 1000 元不得被提前冲销");
        assertTrue(loan.totalDue().isPositive(), "仍有未到期应还，符合按计划分摊语义");
        assertReconciled(loanId);
    }

    @Test
    @DisplayName("同一 requestId 携带不同金额重放：REJECTED_IDEMPOTENCY_MISMATCH，不扣款")
    void sameRequestIdDifferentPayload_rejected() {
        String loanId = newLoan("MIS", "1000", "1000");
        PaymentResult first = paymentService.acceptPayment(new PaymentCommand(
                "REQ-MIS-1", loanId, new BigDecimal("100"), AS_OF));
        PaymentResult tampered = paymentService.acceptPayment(new PaymentCommand(
                "REQ-MIS-1", loanId, new BigDecimal("999"), AS_OF));

        log.info("[输入] REQ-MIS-1 先还100，再以同键提交999");
        log.info("[判定依据] 指纹 {} != 首次指纹 → REJECTED_IDEMPOTENCY_MISMATCH",
                new PaymentCommand("REQ-MIS-1", loanId, new BigDecimal("999"), AS_OF).fingerprint());
        log.info("[关键状态] first={}, tampered={}", first.outcome(), tampered.outcome());

        assertEquals(PaymentOutcome.ACCEPTED_PARTIAL, first.outcome());
        assertEquals(PaymentOutcome.REJECTED_IDEMPOTENCY_MISMATCH, tampered.outcome());
        assertEquals(Money.yuan("100"), loanService.get(loanId).getInstallments().stream()
                .map(i -> i.getPaidPrincipal().plus(i.getPaidInterest()).plus(i.getPaidPenalty()))
                .reduce(Money.ZERO, Money::plus), "篡改请求不得产生新扣款");
        assertReconciled(loanId);
    }

    private void assertReconciled(String loanId) {
        ReconciliationReport report = reconciliationService.reconcile(loanId, AS_OF);
        assertTrue(report.balanced(), "对账必须平衡: " + report.violations());
    }
}

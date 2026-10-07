package com.github.h3yow.loanservicingjava.tests;

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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 撤销链路的非法/异常输入收口：
 * 缺字段、空对象、空请求体、空串/纯空白 → HTTP 400 + REJECTED_REQUEST_INVALID（绝不 5xx、绝不动账）；
 * 贷款不存在、目标还款不存在/未成功受理 → REJECTED_PAYMENT_NOT_FOUND；
 * 重复撤销 → REJECTED_ALREADY_REVERSED；同键重放 → REPLAYED；
 * 不同撤销 requestId 并发撤销同一笔还款 → 只真正生效一次，其余为可区分拒绝。
 * 每个场景结束都强制校验对账恒等式与现金守恒。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReversalInvalidInputTest {

    private static final Logger log = LoggerFactory.getLogger(ReversalInvalidInputTest.class);
    private static final LocalDate D0 = LocalDate.of(2026, 9, 23);

    @Autowired private MockMvc mockMvc;
    @Autowired private PaymentService paymentService;
    @Autowired private ReversalService reversalService;
    @Autowired private LoanService loanService;
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
        log.info("==================== 撤销非法输入用例开始 ====================");
    }

    @Test
    @DisplayName("HTTP 缺关键字段（无 targetPaymentRequestId）：400 + REJECTED_REQUEST_INVALID，账不动")
    void missingTargetField_http400_accountUntouched() throws Exception {
        String loanId = twoInstallmentLoan("RINV-MISS");
        paymentService.acceptPayment(new PaymentCommand(
                "RINV-MISS-PAY", loanId, new BigDecimal("200"), D0));
        String before = snapshot(loanId);
        Money cashBefore = totalCash(loanId);
        log.info("[覆盖场景] 请求体缺少“要撤销哪一笔还款”这一关键字段");
        log.info("[输入] POST /api/loans/{}/payments/reversal body={{\"requestId\":\"RINV-MISS-R1\"}}", loanId);

        MvcResult res = mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RINV-MISS-R1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.outcome").value("REJECTED_REQUEST_INVALID"))
                .andExpect(jsonPath("$.succeeded").value(false))
                .andExpect(jsonPath("$.replay").value(false))
                .andReturn();

        log.info("[判定依据] HTTP={}（非 5xx），outcome=REJECTED_REQUEST_INVALID，succeeded=false，replay=false",
                res.getResponse().getStatus());
        log.info("[关键状态] 账快照前={}", before);
        log.info("[关键状态] 账快照后={}", snapshot(loanId));
        assertEquals(before, snapshot(loanId), "非法请求不得改动科目余额/挂账/分期状态");
        assertEquals(cashBefore, totalCash(loanId), "非法请求不得改变现金总量");
        assertReconciled(loanId, D0);
        log.info("[结论] 缺字段 → 400 + REJECTED_REQUEST_INVALID，账务零变动，对账平衡 ✔");
    }

    @Test
    @DisplayName("HTTP 空对象 {} 与空请求体：均 400 + REJECTED_REQUEST_INVALID，账不动")
    void emptyObjectAndEmptyBody_http400() throws Exception {
        String loanId = twoInstallmentLoan("RINV-EMPTY");
        paymentService.acceptPayment(new PaymentCommand(
                "RINV-EMPTY-PAY", loanId, new BigDecimal("100"), D0));
        String before = snapshot(loanId);
        log.info("[覆盖场景] 空对象 {{}} 与空请求体两种“什么都没传”的形态");

        mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.outcome").value("REJECTED_REQUEST_INVALID"))
                .andExpect(jsonPath("$.succeeded").value(false))
                .andExpect(jsonPath("$.replay").value(false));
        log.info("[判定依据] 空对象 {{}} → 400 + REJECTED_REQUEST_INVALID");

        mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.outcome").value("REJECTED_REQUEST_INVALID"))
                .andExpect(jsonPath("$.succeeded").value(false))
                .andExpect(jsonPath("$.replay").value(false));
        log.info("[判定依据] 空请求体 → 400 + REJECTED_REQUEST_INVALID");

        assertEquals(before, snapshot(loanId), "空对象/空请求体不得改动任何账务状态");
        assertReconciled(loanId, D0);
        assertEquals(Money.yuan("100"), totalCash(loanId), "现金守恒：原还款现金仍在账上");
        log.info("[结论] 空对象/空请求体 → 400 + REJECTED_REQUEST_INVALID，账务零变动 ✔");
    }

    @Test
    @DisplayName("HTTP 空串/纯空白字段：400 + REJECTED_REQUEST_INVALID，与缺字段同一结果码")
    void blankAndWhitespaceFields_http400() throws Exception {
        String loanId = twoInstallmentLoan("RINV-BLANK");
        paymentService.acceptPayment(new PaymentCommand(
                "RINV-BLANK-PAY", loanId, new BigDecimal("100"), D0));
        String before = snapshot(loanId);
        log.info("[覆盖场景] 字段为空串 \"\" 与纯空白 \"   \"（含 requestId 与 targetPaymentRequestId）");

        String[] bodies = {
                "{\"requestId\":\"\",\"targetPaymentRequestId\":\"RINV-BLANK-PAY\"}",
                "{\"requestId\":\"   \",\"targetPaymentRequestId\":\"RINV-BLANK-PAY\"}",
                "{\"requestId\":\"RINV-BLANK-R3\",\"targetPaymentRequestId\":\"\"}",
                "{\"requestId\":\"RINV-BLANK-R4\",\"targetPaymentRequestId\":\"   \"}",
                "{\"requestId\":null,\"targetPaymentRequestId\":null}"
        };
        for (String body : bodies) {
            mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.outcome").value("REJECTED_REQUEST_INVALID"))
                    .andExpect(jsonPath("$.succeeded").value(false))
                    .andExpect(jsonPath("$.replay").value(false));
            log.info("[判定依据] body={} → 400 + REJECTED_REQUEST_INVALID", body);
        }

        assertEquals(before, snapshot(loanId), "空串/纯空白/null 字段不得改动任何账务状态");
        assertReconciled(loanId, D0);
        log.info("[结论] 空串/纯空白/null 字段与缺字段同一结果码 REJECTED_REQUEST_INVALID，账务零变动 ✔");
    }

    @Test
    @DisplayName("服务层非法命令（null 字段/null asOf/null 命令）：REJECTED_REQUEST_INVALID，不抛异常不动账")
    void invalidCommand_serviceLevel_noExceptionNoLedgerChange() {
        String loanId = twoInstallmentLoan("RINV-SVC");
        paymentService.acceptPayment(new PaymentCommand(
                "RINV-SVC-PAY", loanId, new BigDecimal("150"), D0));
        String before = snapshot(loanId);
        log.info("[覆盖场景] 服务层直接传入非法命令：null requestId / null 目标键 / null asOf / null 命令");

        ReversalResult nullRequestId = reversalService.reverse(
                new ReversalCommand(null, loanId, "RINV-SVC-PAY", D0));
        ReversalResult nullTarget = reversalService.reverse(
                new ReversalCommand("RINV-SVC-R2", loanId, null, D0));
        ReversalResult blankTarget = reversalService.reverse(
                new ReversalCommand("RINV-SVC-R3", loanId, "  ", D0));
        ReversalResult nullAsOf = reversalService.reverse(
                new ReversalCommand("RINV-SVC-R4", loanId, "RINV-SVC-PAY", null));
        ReversalResult nullCommand = reversalService.reverse(null);

        log.info("[判定依据] nullRequestId={}, nullTarget={}, blankTarget={}, nullAsOf={}, nullCommand={}",
                nullRequestId.outcome(), nullTarget.outcome(), blankTarget.outcome(),
                nullAsOf.outcome(), nullCommand.outcome());
        for (ReversalResult r : List.of(nullRequestId, nullTarget, blankTarget, nullAsOf, nullCommand)) {
            assertEquals(ReversalOutcome.REJECTED_REQUEST_INVALID, r.outcome(),
                    "非法命令必须统一拒绝为 REJECTED_REQUEST_INVALID 而非抛异常");
            assertFalse(r.succeeded());
            assertFalse(r.replay(), "非法请求不得看起来像已处理过的重放");
        }
        assertEquals(before, snapshot(loanId), "非法命令不得改动任何账务状态");
        assertReconciled(loanId, D0);
        assertEquals(Money.yuan("150"), totalCash(loanId), "现金守恒：非法请求后现金分毫未动");
        log.info("[结论] 服务层非法命令统一 REJECTED_REQUEST_INVALID，无异常、账务零变动 ✔");
    }

    @Test
    @DisplayName("HTTP 贷款不存在/目标还款不存在/目标未成功受理：REJECTED_PAYMENT_NOT_FOUND，区别于请求非法")
    void notFoundScenarios_distinguishableFromInvalid() throws Exception {
        String loanId = twoInstallmentLoan("RINV-NF");
        paymentService.acceptPayment(new PaymentCommand(
                "RINV-NF-PAY", loanId, new BigDecimal("100"), D0));
        PaymentResult rejectedPay = paymentService.acceptPayment(new PaymentCommand(
                "RINV-NF-BAD", loanId, new BigDecimal("-5"), D0));
        assertEquals(PaymentOutcome.REJECTED_AMOUNT_INVALID, rejectedPay.outcome());
        String before = snapshot(loanId);
        log.info("[覆盖场景] 贷款不存在 / 目标还款不存在 / 目标还款当时未受理成功");

        mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", "NO-SUCH-LOAN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RINV-NF-R1\",\"targetPaymentRequestId\":\"RINV-NF-PAY\"}"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.outcome").value("REJECTED_PAYMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.succeeded").value(false));
        log.info("[判定依据] 贷款不存在 → REJECTED_PAYMENT_NOT_FOUND（非 5xx，且不同于 REJECTED_REQUEST_INVALID）");

        mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RINV-NF-R2\",\"targetPaymentRequestId\":\"NO-SUCH-PAYMENT\"}"))
                .andExpect(jsonPath("$.outcome").value("REJECTED_PAYMENT_NOT_FOUND"));
        log.info("[判定依据] 目标还款不存在 → REJECTED_PAYMENT_NOT_FOUND");

        mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RINV-NF-R3\",\"targetPaymentRequestId\":\"RINV-NF-BAD\"}"))
                .andExpect(jsonPath("$.outcome").value("REJECTED_PAYMENT_NOT_FOUND"));
        log.info("[判定依据] 目标还款当时未受理成功 → REJECTED_PAYMENT_NOT_FOUND（无账可撤，不得看似成功）");

        assertEquals(before, snapshot(loanId), "三类 not-found 拒绝均不得改动账务");
        assertReconciled(loanId, D0);
        assertEquals(Money.yuan("100"), totalCash(loanId), "现金守恒：拒绝场景现金不变");
        log.info("[结论] 三类 not-found 均为 REJECTED_PAYMENT_NOT_FOUND，与请求非法可区分 ✔");
    }

    @Test
    @DisplayName("合法撤销不被新校验误伤：REVERSED → 同键 REPLAYED → 换键 REJECTED_ALREADY_REVERSED")
    void validReversalNotAffected_byNewValidation() throws Exception {
        String loanId = twoInstallmentLoan("RINV-OK");
        paymentService.acceptPayment(new PaymentCommand(
                "RINV-OK-PAY", loanId, new BigDecimal("200"), D0));
        log.info("[覆盖场景] 合法撤销全链路：正常撤销、同键重放、换键重复撤销");

        mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RINV-OK-R1\",\"targetPaymentRequestId\":\"RINV-OK-PAY\"}"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.outcome").value("REVERSED"))
                .andExpect(jsonPath("$.succeeded").value(true));
        log.info("[判定依据] 合法撤销 → REVERSED（入参校验不误伤合法请求）");
        assertEquals(Money.ZERO, totalCash(loanId), "撤销后现金全部退出");

        mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RINV-OK-R1\",\"targetPaymentRequestId\":\"RINV-OK-PAY\"}"))
                .andExpect(jsonPath("$.outcome").value("REPLAYED"))
                .andExpect(jsonPath("$.replay").value(true));
        log.info("[判定依据] 同一撤销 requestId 重复提交 → REPLAYED（返回首次结果，不再退账）");

        mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RINV-OK-R2\",\"targetPaymentRequestId\":\"RINV-OK-PAY\"}"))
                .andExpect(jsonPath("$.outcome").value("REJECTED_ALREADY_REVERSED"))
                .andExpect(jsonPath("$.succeeded").value(false))
                .andExpect(jsonPath("$.replay").value(false));
        log.info("[判定依据] 换撤销 requestId 重复撤销 → REJECTED_ALREADY_REVERSED（与重放/并发冲突区分）");

        assertEquals(Money.ZERO, totalCash(loanId), "重放与重复撤销均不产生二次退账");
        assertEquals(Money.yuan("2000"), loanService.get(loanId).unpaidPrincipal());
        assertReconciled(loanId, D0);
        log.info("[结论] 合法撤销语义不变：REVERSED/REPLAYED/REJECTED_ALREADY_REVERSED 各司其职 ✔");
    }

    @Test
    @DisplayName("并发：不同撤销 requestId 同时撤销同一笔还款，只生效一次，其余 REJECTED_ALREADY_REVERSED")
    void concurrentDistinctRequestIds_onlyOneEffective() throws Exception {
        String loanId = twoInstallmentLoan("RINV-CONC");
        paymentService.acceptPayment(new PaymentCommand(
                "RINV-CONC-PAY", loanId, new BigDecimal("300"), D0));
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReversalResult>> futures = new ArrayList<>();
        log.info("[覆盖场景] 同一笔还款被 {} 个不同撤销 requestId 并发撤销", threads);

        for (int i = 0; i < threads; i++) {
            String reversalRequestId = "RINV-CONC-R" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return reversalService.reverse(new ReversalCommand(
                        reversalRequestId, loanId, "RINV-CONC-PAY", D0));
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
        long alreadyReversed = results.stream()
                .filter(r -> r.outcome() == ReversalOutcome.REJECTED_ALREADY_REVERSED).count();
        long others = results.stream()
                .filter(r -> r.outcome() != ReversalOutcome.REVERSED
                        && r.outcome() != ReversalOutcome.REJECTED_ALREADY_REVERSED).count();
        log.info("[判定依据] 每个 requestId 各自 CAS 占位成功，贷款锁内串行：首单生效，其余见已撤销记录");
        log.info("[关键状态] REVERSED={}, REJECTED_ALREADY_REVERSED={}, 其他={}", reversed, alreadyReversed, others);
        results.forEach(r -> log.info("  线程结果: outcome={}, reversedApplied={}, replay={}",
                r.outcome(), r.reversedAppliedAmount(), r.replay()));

        assertEquals(1, reversed, "同一笔还款的并发撤销（不同 requestId）只能真正生效一次");
        assertEquals(threads - 1, alreadyReversed, "其余必须全部是可区分的 REJECTED_ALREADY_REVERSED 拒绝");
        assertEquals(0, others, "不得出现 5xx 式异常或其他不成形结果");

        long reversalEvents = eventStore.findByLoan(loanId).stream()
                .filter(e -> e.type() == EventType.PAYMENT_REVERSED).count();
        assertEquals(1, reversalEvents, "事件流水中只能有一条真实红冲事件（账只退一次）");
        assertEquals(Money.ZERO, totalCash(loanId), "现金守恒：红冲只发生一次，无重复退账");
        assertEquals(Money.yuan("2000"), loanService.get(loanId).unpaidPrincipal());
        assertReconciled(loanId, D0);
        log.info("[结论] 并发不同撤销键：1 次 REVERSED + {} 次 REJECTED_ALREADY_REVERSED，账只退一次 ✔",
                alreadyReversed);
    }

    // ---------- 工具 ----------

    /** 账务快照：各期已还三科目+分期状态+挂账+贷款状态，非法请求前后必须逐字一致。 */
    private String snapshot(String loanId) {
        Loan loan = loanService.get(loanId);
        StringBuilder sb = new StringBuilder();
        for (Installment i : loan.getInstallments()) {
            sb.append('#').append(i.getInstallmentNo())
                    .append(" paidP=").append(i.getPaidPrincipal())
                    .append(" paidI=").append(i.getPaidInterest())
                    .append(" paidN=").append(i.getPaidPenalty())
                    .append(' ').append(i.getStatus())
                    .append(' ').append(i.getBucket())
                    .append(';');
        }
        sb.append(" suspense=").append(loan.getSuspenseBalance())
                .append(" status=").append(loan.getStatus());
        return sb.toString();
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
        log.info("[对账] loanId={} 恒等式平衡 ✔", loanId);
    }
}

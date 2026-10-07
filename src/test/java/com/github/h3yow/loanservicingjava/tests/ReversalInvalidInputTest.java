package com.github.h3yow.loanservicingjava.tests;

import com.github.h3yow.loanservicingjava.domain.loan.Loan;
import com.github.h3yow.loanservicingjava.domain.money.Money;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentCommand;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentOutcome;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentResult;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalOutcome;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 撤销链路非法/异常输入的 HTTP 边界行为：
 * 缺字段、空对象、空串/纯空白、空请求体、贷款不存在、目标还款不存在或未成功受理、
 * 重复撤销、不同撤销请求号并发撤销同一笔还款。
 * 所有非法输入必须返回可区分的业务拒绝结果（绝不 5xx），且科目余额/挂账/分期状态一律不变；
 * 每个场景结束强制校验对账恒等式与现金守恒。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReversalInvalidInputTest {

    private static final Logger log = LoggerFactory.getLogger(ReversalInvalidInputTest.class);
    private static final LocalDate D0 = LocalDate.of(2026, 9, 23);

    @Autowired private MockMvc mockMvc;
    @Autowired private PaymentService paymentService;
    @Autowired private LoanService loanService;
    @Autowired private ReconciliationService reconciliationService;

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
    @DisplayName("请求体整个为空 / 空对象 / 缺关键字段：统一 REJECTED_REQUEST_INVALID，非 5xx，账不变")
    void missingBodyOrFields_rejectedRequestInvalid() throws Exception {
        String loanId = twoInstallmentLoan("RINV-BODY");
        paymentService.acceptPayment(new PaymentCommand(
                "RINV-BODY-PAY", loanId, new BigDecimal("200"), D0));
        Money cashBefore = totalCash(loanId);
        Money suspenseBefore = loanService.get(loanId).getSuspenseBalance();

        // 1) 请求体整个为空
        mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.outcome").value("REJECTED_REQUEST_INVALID"))
                .andExpect(jsonPath("$.succeeded").value(false))
                .andExpect(jsonPath("$.replay").value(false));
        log.info("[判定依据] 空请求体 → REJECTED_REQUEST_INVALID（非 5xx，非成功，非重放）");

        // 2) 空对象 {}：关键字段全缺
        mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.outcome").value("REJECTED_REQUEST_INVALID"));
        log.info("[判定依据] 空对象{{}} → REJECTED_REQUEST_INVALID");

        // 3) 缺 targetPaymentRequestId（“要撤销哪一笔还款”这一关键信息）
        MvcResult r = mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RINV-BODY-R1\"}"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.outcome").value("REJECTED_REQUEST_INVALID"))
                .andReturn();
        log.info("[判定依据] 缺 targetPaymentRequestId → REJECTED_REQUEST_INVALID, body={}",
                r.getResponse().getContentAsString());

        log.info("[关键状态] 非法请求前 cash={}, suspense={}", cashBefore, suspenseBefore);
        assertUnchanged(loanId, cashBefore, suspenseBefore, D0);
    }

    @Test
    @DisplayName("字段为 null / 空串 / 纯空白：统一 REJECTED_REQUEST_INVALID，账不变")
    void nullOrBlankFields_rejectedRequestInvalid() throws Exception {
        String loanId = twoInstallmentLoan("RINV-BLANK");
        paymentService.acceptPayment(new PaymentCommand(
                "RINV-BLANK-PAY", loanId, new BigDecimal("200"), D0));
        Money cashBefore = totalCash(loanId);
        Money suspenseBefore = loanService.get(loanId).getSuspenseBalance();

        String[] bodies = {
                "{\"requestId\":null,\"targetPaymentRequestId\":\"RINV-BLANK-PAY\"}",
                "{\"requestId\":\"\",\"targetPaymentRequestId\":\"RINV-BLANK-PAY\"}",
                "{\"requestId\":\"   \",\"targetPaymentRequestId\":\"RINV-BLANK-PAY\"}",
                "{\"requestId\":\"RINV-BLANK-R1\",\"targetPaymentRequestId\":null}",
                "{\"requestId\":\"RINV-BLANK-R2\",\"targetPaymentRequestId\":\"\"}",
                "{\"requestId\":\"RINV-BLANK-R3\",\"targetPaymentRequestId\":\"  \"}"
        };
        String[] labels = {"requestId=null", "requestId=空串", "requestId=纯空白",
                "target=null", "target=空串", "target=纯空白"};
        for (int i = 0; i < bodies.length; i++) {
            mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                            .contentType(MediaType.APPLICATION_JSON).content(bodies[i]))
                    .andExpect(status().is2xxSuccessful())
                    .andExpect(jsonPath("$.outcome").value("REJECTED_REQUEST_INVALID"))
                    .andExpect(jsonPath("$.succeeded").value(false));
            log.info("[判定依据] {} → REJECTED_REQUEST_INVALID", labels[i]);
        }
        assertUnchanged(loanId, cashBefore, suspenseBefore, D0);
    }

    @Test
    @DisplayName("JSON 语法错误：400 而非 5xx，账不变")
    void malformedJson_badRequestNotServerError() throws Exception {
        String loanId = twoInstallmentLoan("RINV-JSON");
        paymentService.acceptPayment(new PaymentCommand(
                "RINV-JSON-PAY", loanId, new BigDecimal("200"), D0));
        Money cashBefore = totalCash(loanId);
        Money suspenseBefore = loanService.get(loanId).getSuspenseBalance();

        mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest());
        log.info("[判定依据] JSON 语法错误 → HTTP 400（非 5xx）");
        assertUnchanged(loanId, cashBefore, suspenseBefore, D0);
    }

    @Test
    @DisplayName("贷款不存在 / 目标还款不存在 / 目标还款未成功受理：各自明确拒绝，账不变")
    void notFoundTargets_distinguishableRejections() throws Exception {
        String loanId = twoInstallmentLoan("RINV-NF");
        paymentService.acceptPayment(new PaymentCommand(
                "RINV-NF-PAY", loanId, new BigDecimal("100"), D0));
        PaymentResult rejectedPay = paymentService.acceptPayment(new PaymentCommand(
                "RINV-NF-BADPAY", loanId, new BigDecimal("-5"), D0));
        assertEquals(PaymentOutcome.REJECTED_AMOUNT_INVALID, rejectedPay.outcome());
        Money cashBefore = totalCash(loanId);
        Money suspenseBefore = loanService.get(loanId).getSuspenseBalance();

        // 贷款不存在
        mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", "NO-SUCH-LOAN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RINV-NF-R1\",\"targetPaymentRequestId\":\"RINV-NF-PAY\"}"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.outcome").value("REJECTED_PAYMENT_NOT_FOUND"));
        log.info("[判定依据] 贷款不存在 → REJECTED_PAYMENT_NOT_FOUND");

        // 目标还款不存在
        mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RINV-NF-R2\",\"targetPaymentRequestId\":\"NO-SUCH-PAY\"}"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.outcome").value("REJECTED_PAYMENT_NOT_FOUND"));
        log.info("[判定依据] 目标还款不存在 → REJECTED_PAYMENT_NOT_FOUND");

        // 目标还款当时未成功受理（被还款侧拒绝）
        mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RINV-NF-R3\",\"targetPaymentRequestId\":\"RINV-NF-BADPAY\"}"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.outcome").value("REJECTED_PAYMENT_NOT_FOUND"));
        log.info("[判定依据] 目标还款未成功受理 → REJECTED_PAYMENT_NOT_FOUND（无可撤销账务）");

        assertUnchanged(loanId, cashBefore, suspenseBefore, D0);
    }

    @Test
    @DisplayName("重复撤销（换撤销请求号再来）：REJECTED_ALREADY_REVERSED，账只退一次")
    void duplicateReversalDistinctRequest_rejectedAlreadyReversed() throws Exception {
        String loanId = twoInstallmentLoan("RINV-DUP");
        paymentService.acceptPayment(new PaymentCommand(
                "RINV-DUP-PAY", loanId, new BigDecimal("200"), D0));

        mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RINV-DUP-R1\",\"targetPaymentRequestId\":\"RINV-DUP-PAY\"}"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.outcome").value("REVERSED"));
        Money cashAfterFirst = totalCash(loanId);
        Money suspenseAfterFirst = loanService.get(loanId).getSuspenseBalance();
        log.info("[关键状态] 首次撤销成功，cash={}", cashAfterFirst);

        mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RINV-DUP-R2\",\"targetPaymentRequestId\":\"RINV-DUP-PAY\"}"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.outcome").value("REJECTED_ALREADY_REVERSED"))
                .andExpect(jsonPath("$.reversedAppliedAmount").value("0.00"));
        log.info("[判定依据] 换撤销请求号重复撤销 → REJECTED_ALREADY_REVERSED，不再退账");

        assertUnchanged(loanId, cashAfterFirst, suspenseAfterFirst, D0);
    }

    @Test
    @DisplayName("合法撤销不被新校验误拒：REVERSED，对账平衡")
    void validReversal_notRejectedByValidation() throws Exception {
        String loanId = twoInstallmentLoan("RINV-OK");
        paymentService.acceptPayment(new PaymentCommand(
                "RINV-OK-PAY", loanId, new BigDecimal("200"), D0));

        mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RINV-OK-R1\",\"targetPaymentRequestId\":\"RINV-OK-PAY\"}"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.outcome").value("REVERSED"))
                .andExpect(jsonPath("$.succeeded").value(true));
        log.info("[判定依据] 字段齐全的合法撤销 → REVERSED，新增入参校验不误伤");

        assertEquals(Money.ZERO, totalCash(loanId), "撤销后现金守恒：账上无该笔现金");
        assertReconciled(loanId, D0);
    }

    @Test
    @DisplayName("不同撤销请求号并发撤销同一笔还款：恰一次 REVERSED，其余 REJECTED_ALREADY_REVERSED，账只退一次")
    void concurrentDistinctReversals_onlyOneTakesEffect() throws Exception {
        String loanId = twoInstallmentLoan("RINV-CONC");
        paymentService.acceptPayment(new PaymentCommand(
                "RINV-CONC-PAY", loanId, new BigDecimal("300"), D0));
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        log.info("[输入] {} 个线程各用不同撤销 requestId 并发撤销同一笔还款 RINV-CONC-PAY", threads);
        for (int i = 0; i < threads; i++) {
            String reversalRequestId = "RINV-CONC-R" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                MvcResult r = mockMvc.perform(post("/api/loans/{loanId}/payments/reversal", loanId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"requestId\":\"" + reversalRequestId
                                        + "\",\"targetPaymentRequestId\":\"RINV-CONC-PAY\"}"))
                        .andExpect(status().is2xxSuccessful())
                        .andReturn();
                return r.getResponse().getContentAsString();
            }));
        }
        ready.await();
        start.countDown();

        List<String> bodies = new ArrayList<>();
        for (Future<String> f : futures) {
            bodies.add(f.get(15, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long reversed = bodies.stream().filter(b -> b.contains("\"outcome\":\"REVERSED\"")).count();
        long alreadyReversed = bodies.stream()
                .filter(b -> b.contains("\"outcome\":\"REJECTED_ALREADY_REVERSED\"")).count();
        log.info("[判定依据] 贷款锁串行化：锁内首个撤销生效，其余在锁内复查到已被撤销记录");
        log.info("[关键状态] REVERSED={}, REJECTED_ALREADY_REVERSED={}", reversed, alreadyReversed);
        bodies.forEach(b -> log.info("  线程结果: {}", b));

        assertEquals(1, reversed, "同一笔还款被不同撤销请求号并发撤销，只能真正生效一次");
        assertEquals(threads - 1, alreadyReversed,
                "其余必须是可区分的 REJECTED_ALREADY_REVERSED，不得是 5xx 或不明错误");
        assertEquals(Money.ZERO, totalCash(loanId), "现金守恒：红冲只发生一次，无重复退账");
        assertEquals(Money.yuan("2000"), loanService.get(loanId).unpaidPrincipal());
        assertReconciled(loanId, D0);
    }

    /** 非法/拒绝请求不得改动账：现金、挂账不变，对账恒等式仍成立。 */
    private void assertUnchanged(String loanId, Money cashBefore, Money suspenseBefore,
                                 LocalDate asOf) {
        assertEquals(cashBefore, totalCash(loanId), "科目余额+挂账现金不得被非法/拒绝请求改动");
        assertEquals(suspenseBefore, loanService.get(loanId).getSuspenseBalance(),
                "挂账余额不得被非法/拒绝请求改动");
        assertReconciled(loanId, asOf);
        log.info("[关键状态] 校验后 cash={}, suspense={}, 对账平衡", totalCash(loanId),
                loanService.get(loanId).getSuspenseBalance());
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
}

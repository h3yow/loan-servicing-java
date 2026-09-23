package com.github.h3yow.loanservicingjava.tests;

import com.github.h3yow.loanservicingjava.domain.money.Money;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentCommand;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentOutcome;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentResult;
import com.github.h3yow.loanservicingjava.service.LoanService;
import com.github.h3yow.loanservicingjava.service.PaymentService;
import com.github.h3yow.loanservicingjava.service.ReconciliationReport;
import com.github.h3yow.loanservicingjava.service.ReconciliationService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 超额还款挂账与挂账后续冲抵。 */
@SpringBootTest
class SuspenseTest {

    private static final Logger log = LoggerFactory.getLogger(SuspenseTest.class);
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 23);

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private LoanService loanService;
    @Autowired
    private ReconciliationService reconciliationService;

    private String singleLoan(String prefix, LocalDate dueDate, String principal) {
        String id = prefix + "-" + UUID.randomUUID();
        loanService.createLoan(id, 1800, 3, List.of(
                new LoanService.InstallmentSpec(1, dueDate,
                        new BigDecimal(principal), new BigDecimal("30"))));
        return id;
    }

    private String twoInstallmentLoan(String prefix, LocalDate d1, LocalDate d2) {
        String id = prefix + "-" + UUID.randomUUID();
        loanService.createLoan(id, 1800, 3, List.of(
                new LoanService.InstallmentSpec(1, d1, new BigDecimal("1000"), new BigDecimal("30")),
                new LoanService.InstallmentSpec(2, d2, new BigDecimal("1000"), new BigDecimal("30"))));
        return id;
    }

    @Test
    @DisplayName("超额还款：应还部分入账，超出部分挂账，结果码 ACCEPTED_SUSPENSE 可区分")
    void overpayment_goesToSuspenseNotSilentlyDropped() {
        // 第1期已到期（到期日=判定日当天），应还1030；1500 中 1030 入账，470 挂账
        String loanId = singleLoan("SUS", AS_OF, "1000");
        PaymentResult result = paymentService.acceptPayment(new PaymentCommand(
                "REQ-SUS-1", loanId, new BigDecimal("1500"), AS_OF));

        log.info("[输入] 还款1500，到期应还=1030");
        log.info("[判定依据] outcome={}, applied={}, suspenseHeld={}",
                result.outcome(), result.appliedAmount(), result.suspenseHeld());
        log.info("[关键状态] 挂账余额={}, 未还本金={}",
                result.suspenseBalanceAfter(), result.unpaidPrincipalAfter());

        assertEquals(PaymentOutcome.ACCEPTED_SUSPENSE, result.outcome(),
                "超额不得静默处理，必须以可区分结果返回");
        assertEquals(Money.yuan("1030"), result.appliedAmount());
        assertEquals(Money.yuan("470"), result.suspenseHeld());
        assertEquals(Money.ZERO, result.unpaidPrincipalAfter());
        assertEquals(Money.ZERO, result.unpaidInterestAfter());
        ReconciliationReport report = reconciliationService.reconcile(loanId, AS_OF);
        assertTrue(report.balanced(), "挂账后现金恒等式必须平衡: " + report.violations());
        assertEquals(Money.yuan("470"), report.suspenseBalance());
    }

    @Test
    @DisplayName("挂账冲抵：先结清第1期并挂账，再用挂账冲抵第2期应还，按科目顺序分摊")
    void suspenseAppliedLater_coversNextInstallment() {
        LocalDate d1 = AS_OF;
        LocalDate d2 = AS_OF.plusDays(30);
        String loanId = twoInstallmentLoan("SUS2", d1, d2);

        // 还款 1200：仅第1期到期，冲 利息30 + 本金1000 = 1030，余 170 挂账
        PaymentResult overpay = paymentService.acceptPayment(new PaymentCommand(
                "REQ-SUS-3", loanId, new BigDecimal("1200"), AS_OF));
        log.info("[输入] 两期贷款还款1200，第1期到期应还1030，第2期未到期");
        log.info("[判定依据] 第1期1030全额入账，超出170挂账；outcome={}", overpay.outcome());
        assertEquals(PaymentOutcome.ACCEPTED_SUSPENSE, overpay.outcome());
        assertEquals(Money.yuan("170"), loanService.get(loanId).getSuspenseBalance());
        assertEquals(Money.yuan("1000"), loanService.get(loanId).unpaidPrincipal(),
                "第2期本金未被挂账静默冲抵");

        // 推进到第2期到期日，再用挂账冲抵：170 冲第2期 利息30 + 本金140
        PaymentResult sweep = paymentService.applySuspense(loanId, "REQ-SWEEP-2", d2);
        log.info("[判定依据] 第2期到期后挂账冲抵，顺序 罚息→利息→本金：利息30 + 本金140");
        log.info("[关键状态] applied={}, 挂账余额={}, 未还本金={}, 未还利息={}",
                sweep.appliedAmount(), sweep.suspenseBalanceAfter(),
                sweep.unpaidPrincipalAfter(), sweep.unpaidInterestAfter());

        assertEquals(Money.yuan("170"), sweep.appliedAmount());
        assertEquals(Money.ZERO, sweep.suspenseBalanceAfter());
        assertEquals(Money.yuan("860"), sweep.unpaidPrincipalAfter());
        assertEquals(Money.ZERO, sweep.unpaidInterestAfter(), "第2期利息被挂账冲平");

        ReconciliationReport report = reconciliationService.reconcile(loanId, AS_OF);
        assertTrue(report.balanced(), "挂账冲抵后所有恒等式必须平衡: " + report.violations());
    }
}

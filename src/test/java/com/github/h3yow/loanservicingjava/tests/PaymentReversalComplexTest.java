package com.github.h3yow.loanservicingjava.tests;

import com.github.h3yow.loanservicingjava.domain.loan.Installment;
import com.github.h3yow.loanservicingjava.domain.loan.Loan;
import com.github.h3yow.loanservicingjava.domain.money.Money;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentAllocation;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentCommand;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentOutcome;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentResult;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalCommand;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalOutcome;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalResult;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 撤销链路的复杂后续账务场景（撤销=确定性台账重放）：
 * 挂账退回、原挂账曾被后续冲抵使用后仍能安全回退（后续冲抵自动重新就座）、
 * 后续还款后撤销只保留后续还款、结清+账龄推进后撤销、以及与“这笔还款从没发生过”
 * 影子台账的逐字段核对。每个场景结束都验证科目恒等式与现金守恒。
 */
@SpringBootTest
class PaymentReversalComplexTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentReversalComplexTest.class);
    private static final LocalDate D0 = LocalDate.of(2026, 9, 23);

    @Autowired private PaymentService paymentService;
    @Autowired private ReversalService reversalService;
    @Autowired private LoanService loanService;
    @Autowired private OverdueService overdueService;
    @Autowired private ReconciliationService reconciliationService;

    private String twoInstallmentLoan(String prefix, LocalDate d1, LocalDate d2) {
        String id = prefix + "-" + UUID.randomUUID();
        loanService.createLoan(id, 1800, 3, List.of(
                new LoanService.InstallmentSpec(1, d1, new BigDecimal("1000"), new BigDecimal("30")),
                new LoanService.InstallmentSpec(2, d2, new BigDecimal("1000"), new BigDecimal("30"))));
        return id;
    }

    /** 影子贷款（脱离 Spring 仓储的纯领域账本）：只跑“除目标还款外”的相同操作序列。 */
    private Loan shadow(String id, LocalDate d1, LocalDate d2) {
        List<Installment> schedule = List.of(
                Installment.of(1, d1, Money.yuan("1000"), Money.yuan("30")),
                Installment.of(2, d2, Money.yuan("1000"), Money.yuan("30")));
        Loan loan = Loan.create(id, 1800, 3, schedule);
        loan.drainEvents();
        return loan;
    }

    private void payOn(Loan loan, String rid, String amount, LocalDate asOf) {
        loan.accruePenalty(asOf);
        loan.refreshOverdueStatus(asOf);
        PaymentAllocation a = loan.computeAllocation(Money.yuan(amount), asOf);
        loan.commitAllocation(a, rid, asOf);
        if (a.remaining().isPositive()) {
            loan.holdSuspense(a.remaining(), rid, asOf);
        }
        loan.drainEvents();
    }

    private void sweepOn(Loan loan, String rid, LocalDate asOf) {
        if (!loan.getSuspenseBalance().isZero()) {
            loan.accruePenalty(asOf);
            loan.refreshOverdueStatus(asOf);
            loan.applySuspense(rid, asOf);
            loan.drainEvents();
        }
    }

    private void assessOn(Loan loan, LocalDate asOf) {
        loan.accruePenalty(asOf);
        loan.refreshOverdueStatus(asOf);
        loan.drainEvents();
    }

    private void assertLedgerMatchesShadow(String realId, Loan shadow) {
        Loan real = loanService.get(realId);
        for (int i = 0; i < real.getInstallments().size(); i++) {
            Installment r = real.getInstallments().get(i);
            Installment s = shadow.getInstallments().get(i);
            assertEquals(s.getPaidPrincipal(), r.getPaidPrincipal(),
                    "分期" + (i + 1) + " paidPrincipal 应等于影子台账");
            assertEquals(s.getPaidInterest(), r.getPaidInterest(),
                    "分期" + (i + 1) + " paidInterest 应等于影子台账");
            assertEquals(s.getPaidPenalty(), r.getPaidPenalty(),
                    "分期" + (i + 1) + " paidPenalty 应等于影子台账");
            assertEquals(s.getAccruedPenalty(), r.getAccruedPenalty(),
                    "分期" + (i + 1) + " accruedPenalty 应等于影子台账");
            assertEquals(s.getStatus(), r.getStatus(), "分期状态应等于影子台账");
            assertEquals(s.getBucket(), r.getBucket(), "账龄桶应等于影子台账");
        }
        assertEquals(shadow.getSuspenseBalance(), real.getSuspenseBalance(), "挂账余额应等于影子台账");
    }

    @BeforeEach
    void noteStart() {
        log.info("==================== 撤销复杂场景用例开始 ====================");
    }

    @Test
    @DisplayName("超额还款撤销：入账与挂账一并退回，suspense 归零，与影子台账（该还款从未发生）一致")
    void reverseSuspensePayment_refundsHeldSuspense() {
        LocalDate d1 = D0;
        LocalDate d2 = D0.plusDays(20);
        String loanId = twoInstallmentLoan("REV-SUS", d1, d2);
        PaymentResult overpay = paymentService.acceptPayment(new PaymentCommand(
                "REV-SUS-PAY", loanId, new BigDecimal("1500"), D0));
        assertEquals(PaymentOutcome.ACCEPTED_SUSPENSE, overpay.outcome());
        log.info("[输入] 超额还款1500：入账={}, 挂账={}", overpay.appliedAmount(), overpay.suspenseHeld());

        ReversalResult rev = reversalService.reverse(new ReversalCommand(
                "REV-SUS-REV", loanId, "REV-SUS-PAY", D0));
        log.info("[判定依据] outcome={}, reversedApplied={}, reversedSuspense={}, suspenseAfter={}",
                rev.outcome(), rev.reversedAppliedAmount(), rev.reversedSuspenseAmount(),
                rev.suspenseBalanceAfter());

        assertEquals(ReversalOutcome.REVERSED, rev.outcome());
        assertEquals(Money.yuan("1030"), rev.reversedAppliedAmount());
        assertEquals(Money.yuan("470"), rev.reversedSuspenseAmount());
        assertEquals(Money.ZERO, rev.suspenseBalanceAfter(), "挂账必须随撤销一并退回，不得残留");

        assertLedgerMatchesShadow(loanId, shadow("SHADOW-SUS", d1, d2));
        assertReconciled(loanId, D0);
        assertEquals(Money.ZERO, totalCash(loanId), "现金守恒：撤销后无任何现金留存");
    }

    @Test
    @DisplayName("原挂账之后被挂账冲抵用到：撤销后冲抵自动重新就座，账务安全回退且与影子台账一致")
    void suspenseUsedByLaterSweep_safelyRebuilt() {
        LocalDate d1 = D0;
        LocalDate d2 = D0.plusDays(20);
        String loanId = twoInstallmentLoan("REV-SW", d1, d2);
        // 1500：第1期1030入账，470挂账
        paymentService.acceptPayment(new PaymentCommand(
                "REV-SW-PAY", loanId, new BigDecimal("1500"), D0));
        // 第2期到期，挂账冲抵 470（利息30 + 本金440）
        PaymentResult sweep = paymentService.applySuspense(loanId, "REV-SW-SWEEP", d2);
        log.info("[输入] 目标还款的挂账470已被后续冲抵使用：sweep applied={}", sweep.appliedAmount());
        assertEquals(Money.yuan("470"), sweep.appliedAmount());
        assertEquals(Money.ZERO, loanService.get(loanId).getSuspenseBalance());

        ReversalResult rev = reversalService.reverse(new ReversalCommand(
                "REV-SW-REV", loanId, "REV-SW-PAY", d2));
        log.info("[判定依据] outcome={}, message={}", rev.outcome(), rev.message());
        assertEquals(ReversalOutcome.REVERSED, rev.outcome(),
                "重放语义下：目标还款消失后，后续冲抵在新账本上无可支取挂账而自动跳过，仍可安全回退");

        // 影子台账：目标还款从未发生（无挂账），第2期到期时 sweep 无款可冲 → 全期未还
        Loan expected = shadow("SHADOW-SW", d1, d2);
        assessOn(expected, d2);
        sweepOn(expected, "REV-SW-SWEEP", d2);
        assertLedgerMatchesShadow(loanId, expected);
        Installment inst2 = loanService.get(loanId).getInstallments().get(1);
        assertEquals(Money.yuan("1000"), inst2.unpaidPrincipal(), "第2期未被任何现金冲抵");
        assertEquals(Money.yuan("30"), inst2.unpaidInterest());
        assertReconciled(loanId, d2);
        assertEquals(Money.ZERO, totalCash(loanId), "现金守恒：唯一一笔现金已撤销，账上无现金");
    }

    @Test
    @DisplayName("目标还款之后又有还款：撤销前者后后者在瀑布中重新就座，与影子台账逐字段一致")
    void laterPaymentReSeatsAfterReversal_matchesShadow() {
        LocalDate d1 = D0.minusDays(10);
        LocalDate d2 = D0.plusDays(20);
        String loanId = twoInstallmentLoan("REV-LP", d1, d2);
        PaymentResult p1 = paymentService.acceptPayment(new PaymentCommand(
                "REV-LP-P1", loanId, new BigDecimal("200"), D0));
        PaymentResult p2 = paymentService.acceptPayment(new PaymentCommand(
                "REV-LP-P2", loanId, new BigDecimal("100"), D0));
        log.info("[输入] P1={} 入账{}, P2={} 入账{}（P2 当时全部吃在本金上）",
                p1.outcome(), p1.appliedAmount(), p2.outcome(), p2.appliedAmount());

        ReversalResult rev = reversalService.reverse(new ReversalCommand(
                "REV-LP-REV", loanId, "REV-LP-P1", D0));
        log.info("[判定依据] outcome={}, 撤销后第1期 paidP/I/N={}/{}/{}",
                rev.outcome(),
                loanService.get(loanId).getInstallments().get(0).getPaidPrincipal(),
                loanService.get(loanId).getInstallments().get(0).getPaidInterest(),
                loanService.get(loanId).getInstallments().get(0).getPaidPenalty());
        assertEquals(ReversalOutcome.REVERSED, rev.outcome());

        // 影子台账：P1 从未发生，P2 的100重新按 罚息3.5→利息30→本金66.5 就座
        Loan expected = shadow("SHADOW-LP", d1, d2);
        payOn(expected, "REV-LP-P2", "100", D0);
        assertLedgerMatchesShadow(loanId, expected);
        Installment inst1 = loanService.get(loanId).getInstallments().get(0);
        assertEquals(Money.yuan("3.50"), inst1.getPaidPenalty(), "后续还款重新就座：先冲罚息");
        assertEquals(Money.yuan("30"), inst1.getPaidInterest(), "再冲利息");
        assertEquals(Money.yuan("66.50"), inst1.getPaidPrincipal(), "最后冲本金");
        assertReconciled(loanId, D0);
        assertEquals(Money.yuan("100"), totalCash(loanId), "现金守恒：撤销P1后仅剩P2的100");
    }

    @Test
    @DisplayName("结清当期后推进逾期/账龄再撤销：分期回到正确欠款与账龄，补提罚息，与影子台账一致")
    void paidOffThenAged_reversalRestoresOverdueState() {
        LocalDate d1 = D0.minusDays(10);
        LocalDate d2 = D0.plusDays(20);
        String loanId = twoInstallmentLoan("REV-AGE", d1, d2);
        PaymentResult payoff = paymentService.acceptPayment(new PaymentCommand(
                "REV-AGE-PAY", loanId, new BigDecimal("1033.50"), D0));
        assertEquals(PaymentOutcome.ACCEPTED_FULL, payoff.outcome());
        assertEquals("PAID", loanService.get(loanId).getInstallments().get(0).getStatus().name());
        LocalDate later = D0.plusDays(20);
        overdueService.assess(loanId, later);
        log.info("[输入] 第1期结清后推进到{}，inst1 仍为 PAID，再撤销", later);

        ReversalResult rev = reversalService.reverse(new ReversalCommand(
                "REV-AGE-REV", loanId, "REV-AGE-PAY", later));
        Installment inst = loanService.get(loanId).getInstallments().get(0);
        log.info("[判定依据] outcome={}, 撤销后 inst1.status={}, bucket={}, overdueDays={}, accruedPenalty={}",
                rev.outcome(), inst.getStatus(), inst.getBucket(), inst.getOverdueDays(),
                inst.getAccruedPenalty());

        assertEquals(ReversalOutcome.REVERSED, rev.outcome());
        assertNotEquals("PAID", inst.getStatus().name(), "撤销重建后分期不得停留在 PAID");
        assertTrue(inst.getAccruedPenalty().isPositive(), "恢复的未结本金应按撤销日补提逾期罚息");
        assertEquals(Money.yuan("1000"), inst.unpaidPrincipal());

        // 影子台账：该笔还款从未发生，仅做两次评估
        Loan expected = shadow("SHADOW-AGE", d1, d2);
        assessOn(expected, D0);
        assessOn(expected, later);
        assertLedgerMatchesShadow(loanId, expected);
        assertReconciled(loanId, later);
        assertEquals(Money.ZERO, totalCash(loanId), "现金守恒：撤销后无现金留存");
    }

    @Test
    @DisplayName("两笔挂账还款+跨期冲抵后撤销其中一笔：冲抵金额在重放中重新分配，现金守恒成立")
    void twoSuspensePaymentsAndSweep_reverseOne() {
        LocalDate d1 = D0;
        LocalDate d2 = D0.plusDays(20);
        String loanId = twoInstallmentLoan("REV-TWO", d1, d2);
        // 第1期到期应还1030：P1 还1200（1030入账+170挂账），P2 还100（全挂账）→ 挂账270
        paymentService.acceptPayment(new PaymentCommand(
                "REV-TWO-P1", loanId, new BigDecimal("1200"), D0));
        paymentService.acceptPayment(new PaymentCommand(
                "REV-TWO-P2", loanId, new BigDecimal("100"), D0));
        PaymentResult sweep = paymentService.applySuspense(loanId, "REV-TWO-SWEEP", d2);
        log.info("[输入] 两笔挂账共270，跨期冲抵 applied={}", sweep.appliedAmount());
        assertEquals(Money.yuan("270"), sweep.appliedAmount());

        // 撤销 P1（含170挂账与第1期1030入账）；重放后只剩 P2 的100挂账，sweep 只能冲100
        ReversalResult rev = reversalService.reverse(new ReversalCommand(
                "REV-TWO-REV", loanId, "REV-TWO-P1", d2));
        assertEquals(ReversalOutcome.REVERSED, rev.outcome());

        Loan expected = shadow("SHADOW-TWO", d1, d2);
        payOn(expected, "REV-TWO-P2", "100", D0);
        assessOn(expected, d2);
        sweepOn(expected, "REV-TWO-SWEEP", d2);
        assertLedgerMatchesShadow(loanId, expected);
        assertReconciled(loanId, d2);
        assertEquals(Money.yuan("100"), totalCash(loanId), "现金守恒：撤销P1后仅剩P2的100");
        log.info("[判定依据] 重放后第2期 paid={}, 挂账={}, 现金合计=100",
                loanService.get(loanId).getInstallments().get(1)
                        .getPaidPrincipal().plus(loanService.get(loanId).getInstallments().get(1)
                        .getPaidInterest()).plus(loanService.get(loanId).getInstallments().get(1)
                        .getPaidPenalty()),
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

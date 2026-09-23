package com.github.h3yow.loanservicingjava.tests;

import com.github.h3yow.loanservicingjava.domain.event.DomainEvent;
import com.github.h3yow.loanservicingjava.domain.event.EventType;
import com.github.h3yow.loanservicingjava.domain.loan.Loan;
import com.github.h3yow.loanservicingjava.domain.money.Money;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentCommand;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentOutcome;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentResult;
import com.github.h3yow.loanservicingjava.repo.EventStore;
import com.github.h3yow.loanservicingjava.service.LoanService;
import com.github.h3yow.loanservicingjava.service.OverdueService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 一条主线串起：逾期→部分还款→重放→超额挂账→账龄推进→挂账冲抵→对账→事件追溯。 */
@SpringBootTest
class EndToEndFlowTest {

    private static final Logger log = LoggerFactory.getLogger(EndToEndFlowTest.class);

    @Autowired private LoanService loanService;
    @Autowired private OverdueService overdueService;
    @Autowired private PaymentService paymentService;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private EventStore eventStore;

    @Test
    @DisplayName("端到端：逾期认定/部分还款/幂等重放/挂账跨期冲抵/账龄推进 结论一致且可追溯")
    void fullLifecycle() {
        LocalDate d0 = LocalDate.of(2026, 9, 23);
        LocalDate due1 = LocalDate.of(2026, 9, 5);   // 已逾期
        LocalDate due2 = LocalDate.of(2026, 10, 23); // 未来
        String loanId = "E2E-" + UUID.randomUUID();
        loanService.createLoan(loanId, 1800, 3, List.of(
                new LoanService.InstallmentSpec(1, due1, new BigDecimal("1000"), new BigDecimal("30")),
                new LoanService.InstallmentSpec(2, due2, new BigDecimal("1000"), new BigDecimal("30"))));

        // 1) 日终判定：第1期逾期 M1，罚息=1000*18%*15/360=7.50
        Loan afterAssess = overdueService.assess(loanId, d0);
        Money penalty = afterAssess.getInstallments().get(0).getAccruedPenalty();
        log.info("[输入/判定] 第1期到期={} 宽限3天，{} 判定 → OVERDUE，罚息={}", due1, d0, penalty);
        assertEquals(Money.yuan("7.50"), penalty);

        // 2) 部分还款 600：罚息7.5 + 利息30 + 本金562.5
        PaymentResult partial = paymentService.acceptPayment(new PaymentCommand(
                "E2E-P1", loanId, new BigDecimal("600"), d0));
        log.info("[关键状态] 部分还款 outcome={}, 第1期剩余本金={}",
                partial.outcome(), partial.unpaidPrincipalAfter());
        assertEquals(PaymentOutcome.ACCEPTED_PARTIAL, partial.outcome());
        assertEquals(Money.yuan("1437.50"), partial.unpaidPrincipalAfter());

        // 3) 重放不重复扣款
        PaymentResult replay = paymentService.acceptPayment(new PaymentCommand(
                "E2E-P1", loanId, new BigDecimal("600"), d0));
        assertEquals(PaymentOutcome.REPLAYED, replay.outcome());
        assertEquals(Money.ZERO, replay.appliedAmount());

        // 4) 超额还款：第1期剩余本金437.50，还800 → 入账437.50，挂账362.50
        PaymentResult overpay = paymentService.acceptPayment(new PaymentCommand(
                "E2E-P2", loanId, new BigDecimal("800"), d0));
        log.info("[关键状态] 超额还款 outcome={}, applied={}, 挂账={}",
                overpay.outcome(), overpay.appliedAmount(), overpay.suspenseHeld());
        assertEquals(PaymentOutcome.ACCEPTED_SUSPENSE, overpay.outcome());
        assertEquals(Money.yuan("437.50"), overpay.appliedAmount());
        assertEquals(Money.yuan("362.50"), overpay.suspenseHeld());
        assertEquals(Money.yuan("1000"), overpay.unpaidPrincipalAfter(), "第2期未到期本金保留");

        // 5) 账龄推进到第2期到期日（第1期已 PAID 不翻转；第2期当天到期不逾期）
        Loan advanced = overdueService.assess(loanId, due2);
        log.info("[关键状态] 推进到{}：inst1={}, inst2={}, loanStatus={}",
                due2, advanced.getInstallments().get(0).getStatus(),
                advanced.getInstallments().get(1).getStatus(), advanced.getStatus());
        assertEquals("PAID", advanced.getInstallments().get(0).getStatus().name());
        assertFalse(advanced.getInstallments().get(0).getStatus().name().equals("OVERDUE"),
                "已结清分期不得翻转回逾期");

        // 6) 挂账冲抵第2期：362.50 = 利息30 + 本金332.50
        PaymentResult sweep = paymentService.applySuspense(loanId, "E2E-SWEEP", due2);
        log.info("[关键状态] 挂账冲抵 applied={}, 剩余挂账={}, 未还本金={}",
                sweep.appliedAmount(), sweep.suspenseBalanceAfter(), sweep.unpaidPrincipalAfter());
        assertEquals(Money.yuan("362.50"), sweep.appliedAmount());
        assertEquals(Money.ZERO, sweep.suspenseBalanceAfter());
        assertEquals(Money.yuan("667.50"), sweep.unpaidPrincipalAfter());
        assertEquals(Money.ZERO, sweep.unpaidInterestAfter());

        // 7) 全程对账平衡，现金守恒：600+800 = 科目已还1400
        ReconciliationReport report = reconciliationService.reconcile(loanId, due2);
        log.info("[对账] balanced={}, paid(P/I/N)={}/{}/{}, suspense={}, violations={}",
                report.balanced(), report.paidPrincipal(), report.paidInterest(),
                report.paidPenalty(), report.violations());
        assertTrue(report.balanced());
        assertEquals(Money.yuan("1400.00"),
                report.paidPrincipal().plus(report.paidInterest()).plus(report.paidPenalty())
                        .plus(report.suspenseBalance()),
                "现金恒等式：两次还款合计1400 = 科目已还 + 挂账(0)");

        // 8) 事件流水可追溯：每种关键事件都存在，且罚息事件在逾期事件后可关联依据
        List<DomainEvent> events = eventStore.findByLoan(loanId);
        for (EventType t : List.of(EventType.LOAN_CREATED, EventType.PENALTY_ACCRUED,
                EventType.INSTALLMENT_OVERDUE, EventType.PAYMENT_APPLIED,
                EventType.SUSPENSE_HELD, EventType.SUSPENSE_APPLIED,
                EventType.PAYMENT_REPLAYED)) {
            assertTrue(events.stream().anyMatch(e -> e.type() == t),
                    "事件流缺少 " + t + "，状态变化将无法追溯");
        }
        DomainEvent penaltyEvent = events.stream()
                .filter(e -> e.type() == EventType.PENALTY_ACCRUED).findFirst().orElseThrow();
        log.info("[追溯] PENALTY_ACCRUED 属性={}", penaltyEvent.attributes());
        assertEquals("1800", penaltyEvent.attributes().get("penaltyRateBps"));
        assertTrue(penaltyEvent.attributes().containsKey("days"));
    }
}

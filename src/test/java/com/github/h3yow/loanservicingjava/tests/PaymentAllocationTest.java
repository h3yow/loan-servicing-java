package com.github.h3yow.loanservicingjava.tests;

import com.github.h3yow.loanservicingjava.domain.loan.Installment;
import com.github.h3yow.loanservicingjava.domain.loan.Loan;
import com.github.h3yow.loanservicingjava.domain.money.Money;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentAllocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分摊顺序与部分还款边界（纯领域测试，不起 Spring）。 */
class PaymentAllocationTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentAllocationTest.class);
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 23);

    private Loan demoLoan(LocalDate today) {
        List<Installment> schedule = List.of(
                Installment.of(1, today.minusDays(10), Money.yuan("1000"), Money.yuan("30")),
                Installment.of(2, today.plusDays(20), Money.yuan("1000"), Money.yuan("30")));
        return Loan.create("L-ALLOC", 1800, 3, schedule);
    }

    @Test
    @DisplayName("不足额还款：仅已到期分期参与；利息→本金，shortfall 不静默")
    void partialPayment_followsSubjectOrder() {
        Loan loan = demoLoan(AS_OF);
        loan.drainEvents();

        // 第1期已到期应还 1030，第2期未到期不参与自动分摊
        PaymentAllocation allocation = loan.computeAllocation(Money.yuan("800"), AS_OF);
        log.info("[输入] amount=800, 到期应还={}（第1期1030，第2期未到期不参与）",
                allocation.totalDueBefore());
        log.info("[判定依据] 顺序=罚息(0)→利息(第1期30)→本金(第1期)；未到期分期不提前还本");
        log.info("[关键状态] applied={}, remaining={}, shortfall={}",
                allocation.applied(), allocation.remaining(), allocation.hasShortfall());

        assertTrue(allocation.hasShortfall(), "金额不足必须识别为 shortfall，不得静默");
        assertEquals(Money.yuan("800"), allocation.applied());
        assertEquals(Money.ZERO, allocation.remaining());

        var line1 = allocation.lines().stream().filter(l -> l.installmentNo() == 1).findFirst().orElseThrow();
        assertEquals(Money.ZERO, line1.penalty());
        assertEquals(Money.yuan("30"), line1.interest());
        assertEquals(Money.yuan("770"), line1.principal());
        assertEquals(1, allocation.lines().size(), "未到期的第2期不得被提前冲销");
        assertFalse(allocation.hasSurplus());
    }

    @Test
    @DisplayName("逾期罚息计提后：50 元先冲罚息，余下冲利息，本金按剩余金额受偿")
    void overduePayment_penaltyFirst() {
        Loan loan = demoLoan(AS_OF);
        loan.drainEvents();
        loan.accruePenalty(AS_OF);
        loan.drainEvents();

        Money penalty = loan.getInstallments().get(0).unpaidPenalty();
        log.info("[输入] 计提后首期罚息={}, 到期应还=1030+罚息", penalty);
        assertTrue(penalty.isPositive(), "首期已过宽限期，应产生罚息");

        PaymentAllocation allocation = loan.computeAllocation(Money.yuan("50"), AS_OF);
        var line1 = allocation.lines().get(0);
        log.info("[判定依据] 50 元依次冲第1期 罚息={} → 利息30 → 本金16.50，第1期已到期其本金可受偿",
                penalty);
        log.info("[关键状态] line1: penalty={}, interest={}, principal={}; 方案剩余={}",
                line1.penalty(), line1.interest(), line1.principal(), allocation.remaining());

        assertEquals(penalty, line1.penalty(), "罚息最先受偿");
        assertEquals(Money.yuan("30"), line1.interest());
        assertEquals(Money.yuan("50").minus(penalty).minus(Money.yuan("30")), line1.principal());
        assertEquals(Money.ZERO, allocation.remaining());
    }

    @Test
    @DisplayName("超额还款：超过到期应还的部分形成剩余现金（交应用层挂账），不碰未到期分期")
    void surplus_keptForSuspense() {
        Loan loan = demoLoan(AS_OF);
        loan.drainEvents();
        PaymentAllocation allocation = loan.computeAllocation(Money.yuan("1500"), AS_OF);
        log.info("[输入] 还款1500，到期应还1030，第2期未到期");
        log.info("[关键状态] applied={}, remaining={}", allocation.applied(), allocation.remaining());
        assertEquals(Money.yuan("1030"), allocation.applied());
        assertEquals(Money.yuan("470"), allocation.remaining());
        assertTrue(allocation.hasSurplus());
    }
}

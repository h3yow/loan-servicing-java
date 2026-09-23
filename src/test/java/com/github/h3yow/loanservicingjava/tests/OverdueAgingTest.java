package com.github.h3yow.loanservicingjava.tests;

import com.github.h3yow.loanservicingjava.domain.loan.Bucket;
import com.github.h3yow.loanservicingjava.domain.loan.Installment;
import com.github.h3yow.loanservicingjava.domain.loan.InstallmentStatus;
import com.github.h3yow.loanservicingjava.domain.loan.Loan;
import com.github.h3yow.loanservicingjava.domain.money.Money;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentCommand;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentOutcome;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentResult;
import com.github.h3yow.loanservicingjava.service.LoanService;
import com.github.h3yow.loanservicingjava.service.OverdueService;
import com.github.h3yow.loanservicingjava.service.PaymentService;
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

/** 逾期认定、宽限期、账龄推进、跨计息周期下的幂等与防翻转。 */
@SpringBootTest
class OverdueAgingTest {

    private static final Logger log = LoggerFactory.getLogger(OverdueAgingTest.class);

    @Autowired
    private OverdueService overdueService;
    @Autowired
    private LoanService loanService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private ReconciliationService reconciliationService;

    private String singleInstallmentLoan(LocalDate dueDate, String principal, String interest) {
        String id = "OD-" + UUID.randomUUID();
        loanService.createLoan(id, 1800, 3, List.of(
                new LoanService.InstallmentSpec(1, dueDate,
                        new BigDecimal(principal), new BigDecimal(interest))));
        return id;
    }

    @Test
    @DisplayName("宽限期内不逾期；过宽限日后立即转逾期，罚息从逾期首日起算")
    void gracePeriod_thenOverdue() {
        LocalDate due = LocalDate.of(2026, 9, 10);
        String loanId = singleInstallmentLoan(due, "1000", "30");

        // 到期+2天，仍在3天宽限期内
        Loan inGrace = overdueService.assess(loanId, due.plusDays(2));
        Installment inst = inGrace.getInstallments().get(0);
        log.info("[输入] dueDate={}, grace=3, 判定日={}", due, due.plusDays(2));
        log.info("[判定依据] {} <= dueDate+3({})，不逾期、不计罚息", due.plusDays(2), due.plusDays(3));
        log.info("[关键状态] status={}, bucket={}, penalty={}",
                inst.getStatus(), inst.getBucket(), inst.unpaidPenalty());
        assertEquals(InstallmentStatus.PENDING, inst.getStatus());
        assertEquals(Bucket.CURRENT, inst.getBucket());
        assertEquals(Money.ZERO, inst.getAccruedPenalty());

        // 到期+4天（逾期首日=到期+4）
        Loan overdue = overdueService.assess(loanId, due.plusDays(4));
        inst = overdue.getInstallments().get(0);
        // 罚息天数 = 4 - 3(宽限) = 1 天
        Money expectedPenalty = Money.yuan("1000").prorate(1800L, 360L * 10000L);
        log.info("[判定依据] {} > dueDate+3，逾期成立，罚息计提1天={}", due.plusDays(4), expectedPenalty);
        log.info("[关键状态] status={}, bucket={}, overdueDays={}, penalty={}",
                inst.getStatus(), inst.getBucket(), inst.getOverdueDays(), inst.unpaidPenalty());
        assertEquals(InstallmentStatus.OVERDUE, inst.getStatus());
        assertEquals(Bucket.M1, inst.getBucket());
        assertEquals(4, inst.getOverdueDays(), "账龄天数按自然日，不扣宽限期");
        assertEquals(expectedPenalty, inst.getAccruedPenalty());
    }

    @Test
    @DisplayName("账龄随跨周期推进：M1→M2→M3→M3+，重复执行当日判定不重复计收罚息")
    void agingProgressionAndIdempotentAccrual() {
        LocalDate due = LocalDate.of(2026, 1, 1);
        String loanId = singleInstallmentLoan(due, "1000", "30");

        Loan d31 = overdueService.assess(loanId, due.plusDays(31));
        Money penaltyAt31 = d31.getInstallments().get(0).getAccruedPenalty();
        log.info("[输入] 判定日={}，逾期天数=31，首次计提区间=(due+3, {}]", due.plusDays(31), due.plusDays(31));
        assertEquals(Bucket.M2, d31.getInstallments().get(0).getBucket());

        // 同一天重复跑批：计提必须幂等，罚息不得翻倍
        overdueService.assess(loanId, due.plusDays(31));
        Money penaltyReRun = loanService.get(loanId).getInstallments().get(0).getAccruedPenalty();
        log.info("[判定依据] 同日二次跑批：计提区间为空(anchor={})，罚息保持={}", due.plusDays(31), penaltyAt31);
        assertEquals(penaltyAt31, penaltyReRun, "同日重复判定不得重复计收罚息");

        // 跨到下一个计息周期（+61 天），仅对新增的 30 天补提
        Loan d61 = overdueService.assess(loanId, due.plusDays(61));
        Money penaltyAt61 = d61.getInstallments().get(0).getAccruedPenalty();
        Money delta = penaltyAt61.minus(penaltyAt31);
        Money expectedDelta = Money.yuan("1000").prorate(1800L * 30, 360L * 10000L);
        log.info("[判定依据] 跨周期补提：新增30天罚息={}，累计={}", delta, penaltyAt61);
        log.info("[关键状态] bucket={}, overdueDays={}", d61.getInstallments().get(0).getBucket(),
                d61.getInstallments().get(0).getOverdueDays());
        assertEquals(expectedDelta, delta, "跨周期只补提新增天数，不重复计收");
        assertEquals(Bucket.M3, d61.getInstallments().get(0).getBucket());

        Loan d95 = overdueService.assess(loanId, due.plusDays(95));
        assertEquals(Bucket.M3_PLUS, d95.getInstallments().get(0).getBucket());
        log.info("[关键状态] +95天 bucket={}（91天以上 M3+）",
                d95.getInstallments().get(0).getBucket());
        assertTrue(reconciliationService.reconcile(loanId, due.plusDays(95)).balanced());
    }

    @Test
    @DisplayName("逾期后部分还款再结清：状态 PAID 不回翻逾期，后续跑批不再产生罚息")
    void payOffOverdue_noFlipBackNoMorePenalty() {
        LocalDate due = LocalDate.of(2026, 9, 1);
        String loanId = singleInstallmentLoan(due, "1000", "30");
        LocalDate d1 = LocalDate.of(2026, 9, 23);
        overdueService.assess(loanId, d1);
        Money penaltyBefore = loanService.get(loanId).getInstallments().get(0).getAccruedPenalty();
        log.info("[输入] 逾期后罚息已计提={}", penaltyBefore);

        // 部分还款（只还罚息+利息，本金仍欠）
        PaymentResult partial = paymentService.acceptPayment(new PaymentCommand(
                "REQ-PAY-PARTIAL", loanId, penaltyBefore.plus(Money.yuan("30")).toYuan(), d1));
        Installment afterPartial = loanService.get(loanId).getInstallments().get(0);
        log.info("[判定依据] 部分还款 outcome={}（本金仍欠故整体为 PARTIAL），本金仍欠={}，仍处逾期",
                partial.outcome(), afterPartial.unpaidPrincipal());
        assertEquals(PaymentOutcome.ACCEPTED_PARTIAL, partial.outcome(),
                "本金仍欠，即便本次金额被吃光也必须是 PARTIAL，不得报全额结清");
        assertEquals(InstallmentStatus.OVERDUE, afterPartial.getStatus(),
                "本金未结，部分还款不能解除逾期");

        // 后续跑批：仅对剩余本金继续计提，已结清科目不重复计收
        LocalDate d2 = d1.plusDays(10);
        overdueService.assess(loanId, d2);
        Installment inst2 = loanService.get(loanId).getInstallments().get(0);
        log.info("[判定依据] 继续逾期10天，罚息基数=剩余本金{}，新罚息>0 且利息科目保持已结",
                inst2.unpaidPrincipal());
        assertTrue(inst2.getAccruedPenalty().isPositive());
        assertEquals(Money.ZERO, inst2.unpaidInterest(), "已结清的利息不被重复计收");

        // 全额结清（含新增罚息与剩余本金）
        Money payoff = inst2.unpaidPrincipal().plus(inst2.unpaidPenalty());
        PaymentResult payoffResult = paymentService.acceptPayment(new PaymentCommand(
                "REQ-PAY-OFF", loanId, payoff.toYuan(), d2));
        log.info("[关键状态] 结清 outcome={}, loanStatus={}",
                payoffResult.outcome(), payoffResult.loanStatusAfter());

        // 再过 30 天多次跑批：不得翻转回逾期、不得新增罚息
        overdueService.assess(loanId, d2.plusDays(30));
        Installment finalInst = loanService.get(loanId).getInstallments().get(0);
        log.info("[判定依据] 结清后再跑批：status={} 必须保持 PAID，penalty 保持={} 不翻转",
                finalInst.getStatus(), finalInst.getAccruedPenalty());
        assertEquals(InstallmentStatus.PAID, finalInst.getStatus(), "结清后不得翻转回逾期");
        assertEquals(Money.ZERO, finalInst.unpaidPenalty());
        assertEquals(inst2.getAccruedPenalty(), finalInst.getAccruedPenalty(),
                "结清后不再产生新罚息，避免重复计收");
        assertTrue(reconciliationService.reconcile(loanId, d2.plusDays(30)).balanced());
    }
}

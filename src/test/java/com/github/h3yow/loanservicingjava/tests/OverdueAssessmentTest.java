package com.github.h3yow.loanservicingjava.tests;

import com.github.h3yow.loanservicingjava.domain.AgingBucket;
import com.github.h3yow.loanservicingjava.domain.Loan;
import com.github.h3yow.loanservicingjava.domain.LoanStatus;
import com.github.h3yow.loanservicingjava.domain.Money;
import com.github.h3yow.loanservicingjava.domain.RepaymentSchedule;
import com.github.h3yow.loanservicingjava.dto.OverdueAssessment;
import com.github.h3yow.loanservicingjava.dto.RepaymentRequest;
import com.github.h3yow.loanservicingjava.dto.RepaymentResult;
import com.github.h3yow.loanservicingjava.repo.IdempotencyStore;
import com.github.h3yow.loanservicingjava.repo.InMemoryIdempotencyStore;
import com.github.h3yow.loanservicingjava.repo.InMemoryLoanRepository;
import com.github.h3yow.loanservicingjava.repo.LoanRepository;
import com.github.h3yow.loanservicingjava.service.AllocationService;
import com.github.h3yow.loanservicingjava.service.InterestAccrualService;
import com.github.h3yow.loanservicingjava.service.LoanFactory;
import com.github.h3yow.loanservicingjava.service.RepaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 逾期认定与账龄推进：宽限期、部分还款、跨计息周期组合。
 */
class OverdueAssessmentTest {

    private static final Logger log = LoggerFactory.getLogger(OverdueAssessmentTest.class);
    private static final String LOAN_ID = "LOAN-O-1";
    private static final LocalDate START = LocalDate.of(2026, 6, 30);
    private static final LocalDate PERIOD1_DUE = LocalDate.of(2026, 7, 30);

    private LoanRepository loanRepository;
    private RepaymentService repaymentService;
    private InterestAccrualService accrualService;
    private com.github.h3yow.loanservicingjava.service.OverdueService overdueService;
    private Loan loan;

    @BeforeEach
    void setUp() {
        loanRepository = new InMemoryLoanRepository();
        IdempotencyStore idempotencyStore = new InMemoryIdempotencyStore();
        accrualService = new InterestAccrualService();
        repaymentService = new RepaymentService(loanRepository, idempotencyStore,
                accrualService, new AllocationService());
        overdueService = new com.github.h3yow.loanservicingjava.service.OverdueService(loanRepository);
        loan = new LoanFactory().createAmortizingLoan(LOAN_ID, Money.of("12000.00"),
                new BigDecimal("0.12"), new BigDecimal("0.0005"), 3, START, 6);
        loanRepository.save(loan);
    }

    @Test
    void notDueAndWithinGracePeriodAreCurrent() {
        OverdueAssessment beforeDue = overdueService.assess(LOAN_ID, LocalDate.of(2026, 7, 29));
        OverdueAssessment inGrace = overdueService.assess(LOAN_ID, PERIOD1_DUE.plusDays(3));
        log.info("判定: 到期前 status={} basis={}", beforeDue.status(),
                beforeDue.schedules().get(0).basis());
        log.info("判定: 宽限期内 status={} inGrace={} basis={}", inGrace.status(),
                inGrace.schedules().get(0).inGracePeriod(), inGrace.schedules().get(0).basis());
        assertThat(beforeDue.status()).isEqualTo(LoanStatus.CURRENT);
        assertThat(inGrace.status()).isEqualTo(LoanStatus.CURRENT);
        assertThat(inGrace.schedules().get(0).inGracePeriod()).isTrue();
    }

    @Test
    void overdueStartsAfterGracePeriodAndAgingProgresses() {
        OverdueAssessment day1 = overdueService.assess(LOAN_ID, PERIOD1_DUE.plusDays(4));
        OverdueAssessment day31 = overdueService.assess(LOAN_ID, PERIOD1_DUE.plusDays(34));
        OverdueAssessment day61 = overdueService.assess(LOAN_ID, PERIOD1_DUE.plusDays(64));
        OverdueAssessment day91 = overdueService.assess(LOAN_ID, PERIOD1_DUE.plusDays(94));
        log.info("账龄推进: {} -> {} -> {} -> {}", day1.agingBucket(), day31.agingBucket(),
                day61.agingBucket(), day91.agingBucket());
        assertThat(day1.status()).isEqualTo(LoanStatus.OVERDUE);
        assertThat(day1.maxDaysPastDue()).isEqualTo(1);
        assertThat(day1.agingBucket()).isEqualTo(AgingBucket.DPD_1_30);
        assertThat(day31.agingBucket()).isEqualTo(AgingBucket.DPD_31_60);
        assertThat(day61.agingBucket()).isEqualTo(AgingBucket.DPD_61_90);
        assertThat(day91.agingBucket()).isEqualTo(AgingBucket.DPD_90_PLUS);
    }

    @Test
    void partialPaymentKeepsOverdueUntilFullySettledThenClears() {
        LocalDate asOf = PERIOD1_DUE.plusDays(10);
        RepaymentSchedule period1 = loan.getSchedules().get(0);
        // 部分还款：只覆盖罚息+利息，本金未清
        Money penaltyAndInterest = period1.getPrincipalDue()
                .times(new BigDecimal("0.0005").multiply(BigDecimal.valueOf(7)))
                .plus(period1.getInterestDue());
        log.info("输入: 部分还款 amount={} date={} (覆盖罚息利息, 本金未清)", penaltyAndInterest, asOf);
        RepaymentResult partial = repaymentService.repay(
                new RepaymentRequest("O-PART", LOAN_ID, penaltyAndInterest, true, asOf));

        OverdueAssessment stillOverdue = overdueService.assess(LOAN_ID, asOf);
        log.info("判定依据: 部分还款后 period1 overdue={} basis={}",
                stillOverdue.schedules().get(0).overdue(), stillOverdue.schedules().get(0).basis());
        assertThat(partial.outcome()).isEqualTo(com.github.h3yow.loanservicingjava.domain.RepaymentOutcome.PARTIAL_APPLIED);
        assertThat(stillOverdue.status()).isEqualTo(LoanStatus.OVERDUE);

        // 补齐剩余本金后，同一日重新认定：恢复正常，且结论只依据结清事实
        RepaymentResult settle = repaymentService.repay(
                new RepaymentRequest("O-FULL", LOAN_ID, period1.outstandingPrincipal(), true, asOf));
        OverdueAssessment cleared = overdueService.assess(LOAN_ID, asOf);
        log.info("关键状态变化: 补齐结果={} period1 settledDate={} 认定={} basis={}",
                settle.outcome(), period1.getSettledDate(), cleared.status(),
                cleared.schedules().get(0).basis());
        assertThat(period1.isSettled()).isTrue();
        assertThat(cleared.status()).isEqualTo(LoanStatus.CURRENT);
    }

    @Test
    void repeatedAssessmentIsDeterministicAndDoesNotAccrue() {
        int eventsBefore = loan.getEvents().size();
        OverdueAssessment a = overdueService.assess(LOAN_ID, PERIOD1_DUE.plusDays(10));
        OverdueAssessment b = overdueService.assess(LOAN_ID, PERIOD1_DUE.plusDays(10));
        log.info("重复认定: status={} bucket={} 两次一致={} 事件数变化={}",
                a.status(), a.agingBucket(), a.equals(b), loan.getEvents().size() - eventsBefore);
        assertThat(a).isEqualTo(b);
        assertThat(loan.getEvents()).hasSize(eventsBefore);
        // 逾期认定不触发罚息计提
        assertThat(loan.getSchedules().get(0).getPenaltyAccrued()).isEqualTo(Money.ZERO);
    }

    @Test
    void crossPeriodAccrualNeverDoubleCounts() {
        LocalDate d1 = PERIOD1_DUE.plusDays(7);  // 逾期 4 天（08-03..08-06）
        LocalDate d2 = PERIOD1_DUE.plusDays(14); // 逾期 11 天
        RepaymentSchedule period1 = loan.getSchedules().get(0);
        accrualService.accruePenaltyTo(loan, d1);
        Money p1 = period1.getPenaltyAccrued();
        accrualService.accruePenaltyTo(loan, d2);
        Money p2 = period1.getPenaltyAccrued();
        // 回退日期再计提、同日重复计提，都不得重复计收
        accrualService.accruePenaltyTo(loan, d1);
        Money p3 = period1.getPenaltyAccrued();
        accrualService.accruePenaltyTo(loan, d2);
        Money p4 = period1.getPenaltyAccrued();
        log.info("跨周期计提: 4天={} 11天={} 回退重提={} 同日重提={}", p1, p2, p3, p4);
        Money expectedAtD2 = period1.getPrincipalDue()
                .times(new BigDecimal("0.0005").multiply(BigDecimal.valueOf(11)));
        assertThat(p1).isLessThan(p2);
        assertThat(p3).isEqualTo(expectedAtD2);
        assertThat(p4).isEqualTo(expectedAtD2);
    }

    @Test
    void paidOffLoanNeverFlipsBackToOverdue() {
        LocalDate finalDue = LocalDate.of(2026, 12, 30);
        accrualService.accruePenaltyTo(loan, finalDue);
        Money totalOwed = loan.getSchedules().stream().map(RepaymentSchedule::outstandingTotal)
                .reduce(Money.ZERO, Money::plus);
        log.info("输入: 末期一次性结清 amount={} date={}", totalOwed, finalDue);
        RepaymentResult result = repaymentService.repay(
                new RepaymentRequest("O-PAYOFF", LOAN_ID, totalOwed, false, finalDue));
        assertThat(result.outcome()).isEqualTo(com.github.h3yow.loanservicingjava.domain.RepaymentOutcome.APPLIED);
        assertThat(loan.isPaidOff()).isTrue();

        OverdueAssessment later = overdueService.assess(LOAN_ID, LocalDate.of(2027, 6, 30));
        log.info("关键状态变化: 结清后再认定 status={} bucket={}", later.status(), later.agingBucket());
        assertThat(later.status()).isEqualTo(LoanStatus.PAID_OFF);
        assertThat(later.agingBucket()).isEqualTo(AgingBucket.CURRENT);
        assertThat(later.schedules()).allMatch(s -> !s.overdue());
    }
}

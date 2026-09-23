package com.github.h3yow.loanservicingjava.tests;

import com.github.h3yow.loanservicingjava.domain.Loan;
import com.github.h3yow.loanservicingjava.domain.Money;
import com.github.h3yow.loanservicingjava.domain.RepaymentOutcome;
import com.github.h3yow.loanservicingjava.domain.RepaymentSchedule;
import com.github.h3yow.loanservicingjava.dto.RepaymentRequest;
import com.github.h3yow.loanservicingjava.dto.RepaymentResult;
import com.github.h3yow.loanservicingjava.repo.IdempotencyStore;
import com.github.h3yow.loanservicingjava.repo.InMemoryIdempotencyStore;
import com.github.h3yow.loanservicingjava.repo.InMemoryLoanRepository;
import com.github.h3yow.loanservicingjava.repo.LoanRepository;
import com.github.h3yow.loanservicingjava.service.AllocationService;
import com.github.h3yow.loanservicingjava.service.InterestAccrualService;
import com.github.h3yow.loanservicingjava.service.LoanFactory;
import com.github.h3yow.loanservicingjava.service.ReconciliationService;
import com.github.h3yow.loanservicingjava.service.RepaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 还款分摊与幂等：重复还款、部分还款、挂账与拒绝场景。
 * 日志打印输入、判定依据（应还合计）与关键状态变化。
 */
class RepaymentServiceTest {

    private static final Logger log = LoggerFactory.getLogger(RepaymentServiceTest.class);
    private static final String LOAN_ID = "LOAN-T-1";
    private static final LocalDate START = LocalDate.of(2026, 6, 30);

    private LoanRepository loanRepository;
    private RepaymentService repaymentService;
    private ReconciliationService reconciliationService;
    private Loan loan;

    @BeforeEach
    void setUp() {
        loanRepository = new InMemoryLoanRepository();
        IdempotencyStore idempotencyStore = new InMemoryIdempotencyStore();
        repaymentService = new RepaymentService(loanRepository, idempotencyStore,
                new InterestAccrualService(), new AllocationService());
        reconciliationService = new ReconciliationService(loanRepository);
        loan = new LoanFactory().createAmortizingLoan(LOAN_ID, Money.of("12000.00"),
                new BigDecimal("0.12"), new BigDecimal("0.0005"), 3, START, 6);
        loanRepository.save(loan);
    }

    private RepaymentRequest request(String requestId, String amount, boolean allowPartial,
                                     LocalDate paymentDate) {
        return new RepaymentRequest(requestId, LOAN_ID, Money.of(amount), allowPartial, paymentDate);
    }

    private Money totalDueAt(LocalDate asOf) {
        return loan.getSchedules().stream()
                .filter(s -> !s.isSettled() && !s.getDueDate().isAfter(asOf))
                .map(RepaymentSchedule::outstandingTotal)
                .reduce(Money.ZERO, Money::plus);
    }

    @Test
    void fullRepaymentAppliesAndReconciles() {
        LocalDate payDate = LocalDate.of(2026, 7, 30);
        Money due = totalDueAt(payDate);
        log.info("输入: 全额还款 amount={} date={}; 判定依据: 当期应还={}", due, payDate, due);

        RepaymentResult result = repaymentService.repay(request("R-1", due.toString(), false, payDate));

        log.info("结果: outcome={} reason={} 分摊={}", result.outcome(), result.reason(), result.allocations());
        assertThat(result.outcome()).isEqualTo(RepaymentOutcome.APPLIED);
        assertThat(result.allocations()).hasSize(1);
        assertThat(loan.getSchedules().get(0).isSettled()).isTrue();
        assertThat(loan.getSuspenseBalance()).isEqualTo(Money.ZERO);
        assertThat(reconciliationService.verify(LOAN_ID)).isEmpty();
    }

    @Test
    void duplicateRequestReplaysWithoutDoubleApply() {
        LocalDate payDate = LocalDate.of(2026, 7, 30);
        Money due = totalDueAt(payDate);
        Money principalBefore = loan.outstandingPrincipal();
        log.info("输入: 同一 requestId 重复提交 amount={} date={}", due, payDate);

        RepaymentResult first = repaymentService.repay(request("R-DUP", due.toString(), false, payDate));
        RepaymentResult second = repaymentService.repay(request("R-DUP", due.toString(), false, payDate));

        log.info("结果: first={} second={}; 状态: 剩余本金 {} -> {}",
                first.outcome(), second.outcome(), principalBefore, loan.outstandingPrincipal());
        assertThat(first.outcome()).isEqualTo(RepaymentOutcome.APPLIED);
        assertThat(second.outcome()).isEqualTo(RepaymentOutcome.REPLAYED);
        // 只扣款一次：剩余本金只减少第一期本金
        Money period1Principal = loan.getSchedules().get(0).getPrincipalDue();
        assertThat(loan.outstandingPrincipal()).isEqualTo(principalBefore.minus(period1Principal));
        long appliedEvents = loan.getEvents().stream()
                .filter(e -> e.getType().equals("REPAYMENT_APPLIED")).count();
        assertThat(appliedEvents).isEqualTo(1);
        assertThat(reconciliationService.verify(LOAN_ID)).isEmpty();
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        LocalDate payDate = LocalDate.of(2026, 7, 30);
        Money due = totalDueAt(payDate);
        repaymentService.repay(request("R-MIX", due.toString(), false, payDate));
        Money principalAfterFirst = loan.outstandingPrincipal();

        RepaymentResult mismatched = repaymentService.repay(
                request("R-MIX", "1.00", false, payDate));

        log.info("结果: outcome={} reason={}", mismatched.outcome(), mismatched.reason());
        assertThat(mismatched.outcome()).isEqualTo(RepaymentOutcome.REJECTED_KEY_MISMATCH);
        assertThat(loan.outstandingPrincipal()).isEqualTo(principalAfterFirst);
    }

    @Test
    void underpaymentWithoutPartialFlagIsRejected() {
        LocalDate payDate = LocalDate.of(2026, 7, 30);
        Money due = totalDueAt(payDate);
        Money shortAmount = due.minus(Money.of("0.01"));
        log.info("输入: 不足额还款 amount={} 应还={} allowPartial=false", shortAmount, due);

        RepaymentResult result = repaymentService.repay(
                request("R-SHORT", shortAmount.toString(), false, payDate));

        log.info("结果: outcome={} reason={}", result.outcome(), result.reason());
        assertThat(result.outcome()).isEqualTo(RepaymentOutcome.REJECTED_UNDERPAYMENT);
        assertThat(loan.outstandingPrincipal()).isEqualTo(Money.of("12000.00"));
        assertThat(loan.getSchedules().get(0).getInterestPaid()).isEqualTo(Money.ZERO);
    }

    @Test
    void partialPaymentAppliesInPenaltyInterestPrincipalOrder() {
        LocalDate payDate = LocalDate.of(2026, 7, 30);
        RepaymentSchedule period1 = loan.getSchedules().get(0);
        Money interest = period1.getInterestDue();
        Money halfPrincipal = Money.of(period1.getPrincipalDue().toBigDecimal()
                .divide(new BigDecimal("2")));
        Money amount = interest.plus(halfPrincipal);
        log.info("输入: 部分还款 amount={} (利息={} + 一半本金={}) allowPartial=true",
                amount, interest, halfPrincipal);

        RepaymentResult result = repaymentService.repay(
                request("R-PART", amount.toString(), true, payDate));

        log.info("结果: outcome={} 分摊={}; 状态: 期1已还利息={} 已还本金={} 结清={}",
                result.outcome(), result.allocations(), period1.getInterestPaid(),
                period1.getPrincipalPaid(), period1.isSettled());
        assertThat(result.outcome()).isEqualTo(RepaymentOutcome.PARTIAL_APPLIED);
        assertThat(result.allocations()).hasSize(1);
        assertThat(result.allocations().get(0).interest()).isEqualTo(interest);
        assertThat(result.allocations().get(0).principal()).isEqualTo(halfPrincipal);
        assertThat(period1.isSettled()).isFalse();
        assertThat(reconciliationService.verify(LOAN_ID)).isEmpty();
    }

    @Test
    void overpaymentRemainderGoesToSuspense() {
        LocalDate payDate = LocalDate.of(2026, 7, 30);
        Money due = totalDueAt(payDate);
        Money amount = due.plus(Money.of("100.00"));
        log.info("输入: 超额还款 amount={} 应还={}", amount, due);

        RepaymentResult result = repaymentService.repay(
                request("R-OVER", amount.toString(), false, payDate));

        log.info("结果: outcome={} 挂账={}; 状态: 挂账余额={}",
                result.outcome(), result.suspenseAmount(), loan.getSuspenseBalance());
        assertThat(result.outcome()).isEqualTo(RepaymentOutcome.APPLIED_WITH_SUSPENSE);
        assertThat(result.suspenseAmount()).isEqualTo(Money.of("100.00"));
        assertThat(loan.getSuspenseBalance()).isEqualTo(Money.of("100.00"));
        assertThat(reconciliationService.verify(LOAN_ID)).isEmpty();
    }

    @Test
    void paymentBeforeAnyDueDateIsFullySuspended() {
        LocalDate payDate = LocalDate.of(2026, 7, 15);
        log.info("输入: 无到期应还时还款 amount=500.00 date={}", payDate);

        RepaymentResult result = repaymentService.repay(
                request("R-EARLY", "500.00", false, payDate));

        log.info("结果: outcome={} 挂账={}", result.outcome(), result.suspenseAmount());
        assertThat(result.outcome()).isEqualTo(RepaymentOutcome.SUSPENDED_FULL);
        assertThat(result.allocations()).isEmpty();
        assertThat(loan.getSuspenseBalance()).isEqualTo(Money.of("500.00"));
        assertThat(loan.outstandingPrincipal()).isEqualTo(Money.of("12000.00"));
    }

    @Test
    void invalidAmountIsRejected() {
        RepaymentResult result = repaymentService.repay(
                request("R-ZERO", "0.00", false, LocalDate.of(2026, 7, 30)));
        log.info("结果: outcome={} reason={}", result.outcome(), result.reason());
        assertThat(result.outcome()).isEqualTo(RepaymentOutcome.REJECTED_INVALID_AMOUNT);
    }

    @Test
    void latePaymentAccruesPenaltyAndAllocatesPenaltyFirst() {
        LocalDate payDate = LocalDate.of(2026, 8, 9); // 期1到期 07-30，宽限 3 天，逾期 7 天
        RepaymentSchedule period1 = loan.getSchedules().get(0);
        Money expectedPenalty = period1.getPrincipalDue()
                .times(new BigDecimal("0.0005").multiply(BigDecimal.valueOf(7)));
        log.info("输入: 逾期还款 date={}; 判定依据: 逾期7天 预计罚息={} (基数={})",
                payDate, expectedPenalty, period1.getPrincipalDue());

        // 先触发一次计提以读取应还合计（通过一次足额还款完成）
        Money dueBeforeAccrual = totalDueAt(payDate);
        Money amount = dueBeforeAccrual.plus(expectedPenalty);
        RepaymentResult result = repaymentService.repay(
                request("R-LATE", amount.toString(), false, payDate));

        log.info("结果: outcome={} 分摊={}; 状态: 期1罚息 计提={} 已还={}",
                result.outcome(), result.allocations(),
                period1.getPenaltyAccrued(), period1.getPenaltyPaid());
        assertThat(result.outcome()).isEqualTo(RepaymentOutcome.APPLIED);
        assertThat(period1.getPenaltyAccrued()).isEqualTo(expectedPenalty);
        RepaymentResult.Allocation allocation = result.allocations().get(0);
        assertThat(allocation.penalty()).isEqualTo(expectedPenalty);
        assertThat(allocation.interest()).isEqualTo(period1.getInterestDue());
        assertThat(allocation.principal()).isEqualTo(period1.getPrincipalDue());
        assertThat(period1.isSettled()).isTrue();
        assertThat(reconciliationService.verify(LOAN_ID)).isEmpty();
    }

    @Test
    void repeatedAccrualOnSameDateDoesNotDoubleCount() {
        LocalDate payDate = LocalDate.of(2026, 8, 9);
        RepaymentSchedule period1 = loan.getSchedules().get(0);
        // 第一次：部分还款触发计提
        repaymentService.repay(request("R-A1", "10.00", true, payDate));
        Money penaltyAfterFirst = period1.getPenaltyAccrued();
        // 第二次：同日再还款，计提不得重复
        RepaymentResult second = repaymentService.repay(request("R-A2", "10.00", true, payDate));
        Money penaltyAfterSecond = period1.getPenaltyAccrued();

        log.info("状态: 首次计提={} 同日再次计提后={} 第二次结果={}",
                penaltyAfterFirst, penaltyAfterSecond, second.outcome());
        assertThat(penaltyAfterSecond).isEqualTo(penaltyAfterFirst);
        assertThat(reconciliationService.verify(LOAN_ID)).isEmpty();
    }

    @Test
    void loanNotFoundPropagates() {
        RepaymentRequest bad = new RepaymentRequest("R-404", "NO-SUCH-LOAN",
                Money.of("1.00"), false, LocalDate.of(2026, 7, 30));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repaymentService.repay(bad))
                .isInstanceOf(com.github.h3yow.loanservicingjava.service.LoanNotFoundException.class);
    }

    @Test
    void suspenseAndBalancesStayConsistentAcrossMixedPayments() {
        LocalDate d1 = LocalDate.of(2026, 7, 30);
        LocalDate d2 = LocalDate.of(2026, 8, 30);
        repaymentService.repay(request("R-M1", totalDueAt(d1).plus(Money.of("50")).toString(), false, d1));
        repaymentService.repay(request("R-M2", totalDueAt(d2).toString(), false, d2));
        List<String> discrepancies = reconciliationService.verify(LOAN_ID);
        log.info("状态: 挂账余额={} 剩余本金={} 对账差异={}",
                loan.getSuspenseBalance(), loan.outstandingPrincipal(), discrepancies);
        assertThat(loan.getSuspenseBalance()).isEqualTo(Money.of("50.00"));
        assertThat(discrepancies).isEmpty();
    }
}

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发还款：同一 requestId 并发只扣款一次；不同 requestId 并发不重复扣款、不透支。
 */
class ConcurrentRepaymentTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentRepaymentTest.class);
    private static final String LOAN_ID = "LOAN-C-1";
    private static final LocalDate PAY_DATE = LocalDate.of(2026, 7, 30);
    private static final int THREADS = 16;

    private LoanRepository loanRepository;
    private RepaymentService repaymentService;
    private ReconciliationService reconciliationService;
    private Loan loan;
    private Money due;

    @BeforeEach
    void setUp() {
        loanRepository = new InMemoryLoanRepository();
        IdempotencyStore idempotencyStore = new InMemoryIdempotencyStore();
        repaymentService = new RepaymentService(loanRepository, idempotencyStore,
                new InterestAccrualService(), new AllocationService());
        reconciliationService = new ReconciliationService(loanRepository);
        loan = new LoanFactory().createAmortizingLoan(LOAN_ID, Money.of("12000.00"),
                new BigDecimal("0.12"), new BigDecimal("0.0005"), 3,
                LocalDate.of(2026, 6, 30), 6);
        loanRepository.save(loan);
        due = loan.getSchedules().get(0).outstandingTotal();
    }

    private List<RepaymentResult> runConcurrent(List<RepaymentRequest> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RepaymentResult>> futures = new ArrayList<>();
        for (RepaymentRequest request : requests) {
            futures.add(pool.submit(() -> {
                start.await();
                return repaymentService.repay(request);
            }));
        }
        start.countDown();
        List<RepaymentResult> results = new ArrayList<>();
        for (Future<RepaymentResult> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();
        return results;
    }

    @Test
    void concurrentSameRequestIdAppliesExactlyOnce() throws Exception {
        log.info("输入: {} 个线程并发提交同一 requestId/相同报文 amount={}", THREADS, due);
        List<RepaymentRequest> requests = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            requests.add(new RepaymentRequest("REQ-SAME", LOAN_ID, due, false, PAY_DATE));
        }

        List<RepaymentResult> results = runConcurrent(requests);
        Map<RepaymentOutcome, Long> counts = results.stream()
                .collect(Collectors.groupingBy(RepaymentResult::outcome, Collectors.counting()));
        log.info("判定结果分布: {}", counts);

        assertThat(counts.getOrDefault(RepaymentOutcome.APPLIED, 0L)).isEqualTo(1);
        assertThat(counts.getOrDefault(RepaymentOutcome.REPLAYED, 0L)).isEqualTo(THREADS - 1L);
        Money period1Principal = loan.getSchedules().get(0).getPrincipalDue();
        assertThat(loan.outstandingPrincipal()).isEqualTo(Money.of("12000.00").minus(period1Principal));
        assertThat(loan.getSuspenseBalance()).isEqualTo(Money.ZERO);
        assertThat(reconciliationService.verify(LOAN_ID)).isEmpty();
    }

    @Test
    void concurrentDifferentRequestIdsNeverDoubleChargesOrOverdrafts() throws Exception {
        log.info("输入: {} 个线程并发提交不同 requestId，各付应还金额={}", THREADS, due);
        List<RepaymentRequest> requests = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            requests.add(new RepaymentRequest("REQ-" + i, LOAN_ID, due, false, PAY_DATE));
        }

        List<RepaymentResult> results = runConcurrent(requests);
        Map<RepaymentOutcome, Long> counts = results.stream()
                .collect(Collectors.groupingBy(RepaymentResult::outcome, Collectors.counting()));
        Money appliedPrincipal = results.stream()
                .filter(r -> r.outcome() == RepaymentOutcome.APPLIED)
                .flatMap(r -> r.allocations().stream())
                .map(RepaymentResult.Allocation::principal)
                .reduce(Money.ZERO, Money::plus);
        Money suspended = results.stream()
                .filter(r -> r.outcome() == RepaymentOutcome.SUSPENDED_FULL)
                .map(RepaymentResult::suspenseAmount)
                .reduce(Money.ZERO, Money::plus);

        log.info("判定结果分布: {}; 成功分摊本金={} 挂账={}", counts, appliedPrincipal, suspended);
        // 可区分：每笔要么成功、要么冲突、要么无应还挂账，绝不出现重复扣款
        assertThat(results).extracting(RepaymentResult::outcome)
                .isSubsetOf(RepaymentOutcome.APPLIED, RepaymentOutcome.CONFLICT,
                        RepaymentOutcome.SUSPENDED_FULL);
        assertThat(appliedPrincipal).isEqualTo(loan.getSchedules().get(0).getPrincipalDue());
        assertThat(loan.getSuspenseBalance()).isEqualTo(suspended);
        assertThat(reconciliationService.verify(LOAN_ID)).isEmpty();
    }

    @Test
    void lockHeldByAnotherRequestDeterministicallyReturnsConflict() throws Exception {
        RepaymentRequest request = new RepaymentRequest("REQ-LOCK", LOAN_ID, due, false, PAY_DATE);
        ReentrantLock externalLock = loanRepository.lockFor(LOAN_ID);
        // 必须由另一线程持锁（ReentrantLock 对同一线程可重入）
        ExecutorService holder = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holderTask = holder.submit(() -> {
            externalLock.lock();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                externalLock.unlock();
            }
        });
        while (!externalLock.isLocked()) {
            Thread.onSpinWait();
        }
        try {
            log.info("输入: 贷款锁被其他线程占用时提交 requestId={}", "REQ-LOCK");
            RepaymentResult result = repaymentService.repay(request);
            log.info("判定结果: outcome={} reason={}", result.outcome(), result.reason());
            assertThat(result.outcome()).isEqualTo(RepaymentOutcome.CONFLICT);
        } finally {
            release.countDown();
            holderTask.get(); // 等待持锁线程真正释放锁
            holder.shutdown();
        }
        // 锁释放后同请求可正常处理（冲突结果可恢复，不残留状态）
        RepaymentResult afterRelease = repaymentService.repay(request);
        assertThat(afterRelease.outcome()).isEqualTo(RepaymentOutcome.APPLIED);
        RepaymentSchedule period1 = loan.getSchedules().get(0);
        assertThat(period1.isSettled()).isTrue();
        assertThat(reconciliationService.verify(LOAN_ID)).isEmpty();
    }
}

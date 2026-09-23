package com.github.h3yow.loanservicingjava.service;

import com.github.h3yow.loanservicingjava.domain.Loan;
import com.github.h3yow.loanservicingjava.domain.Money;
import com.github.h3yow.loanservicingjava.domain.RepaymentOutcome;
import com.github.h3yow.loanservicingjava.domain.RepaymentSchedule;
import com.github.h3yow.loanservicingjava.dto.RepaymentRequest;
import com.github.h3yow.loanservicingjava.dto.RepaymentResult;
import com.github.h3yow.loanservicingjava.repo.IdempotencyStore;
import com.github.h3yow.loanservicingjava.repo.LoanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 还款应用服务：编排幂等校验、贷款级并发控制、罚息计提、分摊与挂账。
 * 保证：
 * - 同一 requestId 重复提交返回首次结果（REPLAYED），不重复扣款；
 * - 同一贷款并发请求以 CONFLICT 区分返回，不透支、不漏记；
 * - 金额不足且未声明允许部分还款时拒绝（REJECTED_UNDERPAYMENT），不静默处理；
 * - 超出应还部分挂账（*_SUSPENSE 结果），余额可核对。
 */
@Service
public class RepaymentService {

    private static final Logger log = LoggerFactory.getLogger(RepaymentService.class);

    private final LoanRepository loanRepository;
    private final IdempotencyStore idempotencyStore;
    private final InterestAccrualService accrualService;
    private final AllocationService allocationService;

    public RepaymentService(LoanRepository loanRepository,
                            IdempotencyStore idempotencyStore,
                            InterestAccrualService accrualService,
                            AllocationService allocationService) {
        this.loanRepository = loanRepository;
        this.idempotencyStore = idempotencyStore;
        this.accrualService = accrualService;
        this.allocationService = allocationService;
    }

    public RepaymentResult repay(RepaymentRequest request) {
        log.info("repay input: requestId={} loan={} amount={} allowPartial={} paymentDate={}",
                request.requestId(), request.loanId(), request.amount(),
                request.allowPartial(), request.paymentDate());
        if (request.amount() == null || !request.amount().isPositive()) {
            return RepaymentResult.rejected(RepaymentOutcome.REJECTED_INVALID_AMOUNT,
                    "还款金额必须为正数: " + request.amount());
        }
        IdempotencyStore.BeginResult begin =
                idempotencyStore.begin(request.requestId(), fingerprint(request));
        switch (begin.status()) {
            case MISMATCH -> {
                log.info("repay key mismatch: requestId={}", request.requestId());
                return RepaymentResult.rejected(RepaymentOutcome.REJECTED_KEY_MISMATCH,
                        "同一幂等键提交了不同报文: " + request.requestId());
            }
            case REPLAY -> {
                log.info("repay replayed: requestId={}", request.requestId());
                return begin.replayedResult().asReplayed();
            }
            default -> {
                // FIRST：继续执行
            }
        }
        try {
            RepaymentResult result = doRepay(request);
            if (result.outcome() == RepaymentOutcome.CONFLICT) {
                // 冲突是临时性失败（本请求未执行），释放幂等键，允许同键重试成为首个请求
                idempotencyStore.abort(request.requestId(),
                        new IllegalStateException("temporary conflict"));
            } else {
                idempotencyStore.commit(request.requestId(), result);
            }
            log.info("repay done: requestId={} outcome={} reason={}",
                    request.requestId(), result.outcome(), result.reason());
            return result;
        } catch (RuntimeException e) {
            idempotencyStore.abort(request.requestId(), e);
            throw e;
        }
    }

    private RepaymentResult doRepay(RepaymentRequest request) {
        ReentrantLock lock = loanRepository.lockFor(request.loanId());
        if (!lock.tryLock()) {
            log.info("repay conflict: loan={} requestId={}", request.loanId(), request.requestId());
            return RepaymentResult.rejected(RepaymentOutcome.CONFLICT,
                    "贷款存在进行中的其他还款请求，请稍后重试");
        }
        try {
            Loan loan = loanRepository.findById(request.loanId())
                    .orElseThrow(() -> new LoanNotFoundException(request.loanId()));
            LocalDate asOf = request.paymentDate();

            // 1) 罚息计提到还款日（幂等，重复还款日不会重复计收）
            accrualService.accruePenaltyTo(loan, asOf);

            // 2) 当日已到期应还合计（判定依据）
            Money totalDue = totalDue(loan, asOf);
            Money amount = request.amount();
            log.info("repay basis: loan={} totalDue={} amount={} allowPartial={}",
                    loan.getLoanId(), totalDue, amount, request.allowPartial());

            if (totalDue.isZero()) {
                loan.creditSuspense(amount);
                loan.addEvent("REPAYMENT_SUSPENDED",
                        "请求=%s 当日无到期应还，全额挂账 %s".formatted(request.requestId(), amount));
                loan.bumpVersion();
                return RepaymentResult.of(RepaymentOutcome.SUSPENDED_FULL,
                        "当日无到期应还，全额挂账", List.of(), amount, loan.getVersion());
            }
            if (amount.isLessThan(totalDue) && !request.allowPartial()) {
                loan.addEvent("REPAYMENT_REJECTED",
                        "请求=%s 金额=%s 小于当期应还=%s 且未声明允许部分还款，拒绝"
                                .formatted(request.requestId(), amount, totalDue));
                loan.bumpVersion();
                return RepaymentResult.of(RepaymentOutcome.REJECTED_UNDERPAYMENT,
                        "金额不足且未声明允许部分还款: 应还=" + totalDue + " 实付=" + amount,
                        List.of(), Money.ZERO, loan.getVersion());
            }

            // 3) 分摊；结余挂账
            AllocationService.AllocationOutcome outcome = allocationService.allocate(loan, amount, asOf);
            Money remainder = outcome.remainder();
            if (remainder.isPositive()) {
                loan.creditSuspense(remainder);
            }
            boolean partial = amount.isLessThan(totalDue);
            RepaymentOutcome resultCode = partial
                    ? RepaymentOutcome.PARTIAL_APPLIED
                    : (remainder.isPositive() ? RepaymentOutcome.APPLIED_WITH_SUSPENSE
                                              : RepaymentOutcome.APPLIED);
            String reason = switch (resultCode) {
                case PARTIAL_APPLIED -> "部分还款: 应还=" + totalDue + " 实付=" + amount;
                case APPLIED_WITH_SUSPENSE -> "覆盖全部应还，结余挂账: " + remainder;
                default -> "全额覆盖当期应还";
            };
            loan.addEvent("REPAYMENT_APPLIED",
                    "请求=%s 结果=%s 金额=%s 应还=%s 分摊=%s 挂账=%s"
                            .formatted(request.requestId(), resultCode, amount, totalDue,
                                    outcome.allocations(), remainder));
            loan.bumpVersion();
            return RepaymentResult.of(resultCode, reason, outcome.allocations(), remainder,
                    loan.getVersion());
        } finally {
            lock.unlock();
        }
    }

    private Money totalDue(Loan loan, LocalDate asOf) {
        return loan.getSchedules().stream()
                .filter(s -> !s.isSettled() && !s.getDueDate().isAfter(asOf))
                .map(RepaymentSchedule::outstandingTotal)
                .reduce(Money.ZERO, Money::plus);
    }

    private String fingerprint(RepaymentRequest request) {
        return request.loanId() + "|" + request.amount() + "|" + request.allowPartial()
                + "|" + request.paymentDate();
    }
}

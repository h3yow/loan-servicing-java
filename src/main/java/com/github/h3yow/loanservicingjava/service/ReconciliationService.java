package com.github.h3yow.loanservicingjava.service;

import com.github.h3yow.loanservicingjava.domain.Loan;
import com.github.h3yow.loanservicingjava.domain.Money;
import com.github.h3yow.loanservicingjava.domain.RepaymentSchedule;
import com.github.h3yow.loanservicingjava.repo.LoanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 对账服务：核对贷款科目余额与还款计划的对应关系。
 * 核对项：
 * - 各期已还本金/利息不得超过应还，已还罚息不得超过已计提；
 * - 各期本金合计 = 放款本金；
 * - 结清标记与零余额状态一致；
 * - 挂账余额非负；
 * - 贷款级"剩余本金合计"等于各期未还本金之和。
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final LoanRepository loanRepository;

    public ReconciliationService(LoanRepository loanRepository) {
        this.loanRepository = loanRepository;
    }

    public List<String> verify(String loanId) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new LoanNotFoundException(loanId));
        List<String> discrepancies = new ArrayList<>();

        Money sumPrincipalDue = Money.ZERO;
        Money sumOutstanding = Money.ZERO;
        for (RepaymentSchedule schedule : loan.getSchedules()) {
            int period = schedule.getPeriod();
            sumPrincipalDue = sumPrincipalDue.plus(schedule.getPrincipalDue());
            sumOutstanding = sumOutstanding.plus(schedule.outstandingPrincipal());
            if (schedule.getPrincipalPaid().isGreaterThan(schedule.getPrincipalDue())) {
                discrepancies.add("期次%d 已还本金超过应还".formatted(period));
            }
            if (schedule.getInterestPaid().isGreaterThan(schedule.getInterestDue())) {
                discrepancies.add("期次%d 已还利息超过应还".formatted(period));
            }
            if (schedule.getPenaltyPaid().isGreaterThan(schedule.getPenaltyAccrued())) {
                discrepancies.add("期次%d 已还罚息超过已计提".formatted(period));
            }
            boolean zeroBalance = schedule.outstandingTotal().isZero();
            if (schedule.isSettled() != zeroBalance) {
                discrepancies.add("期次%d 结清标记与余额状态不一致（结清=%s 余额=%s）"
                        .formatted(period, schedule.isSettled(), schedule.outstandingTotal()));
            }
            if (schedule.isSettled() && schedule.getSettledDate() == null) {
                discrepancies.add("期次%d 已标记结清但缺少结清日期".formatted(period));
            }
        }
        if (!sumPrincipalDue.equals(loan.getPrincipal())) {
            discrepancies.add("各期本金合计 %s ≠ 放款本金 %s"
                    .formatted(sumPrincipalDue, loan.getPrincipal()));
        }
        if (loan.getSuspenseBalance().isNegative()) {
            discrepancies.add("挂账余额为负: " + loan.getSuspenseBalance());
        }
        if (!loan.outstandingPrincipal().equals(sumOutstanding)) {
            discrepancies.add("贷款剩余本金 %s 与各期未还本金之和 %s 不一致"
                    .formatted(loan.outstandingPrincipal(), sumOutstanding));
        }
        log.info("reconciliation: loan={} discrepancies={}", loanId, discrepancies);
        return discrepancies;
    }
}

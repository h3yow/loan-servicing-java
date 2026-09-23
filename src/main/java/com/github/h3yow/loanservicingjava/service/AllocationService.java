package com.github.h3yow.loanservicingjava.service;

import com.github.h3yow.loanservicingjava.domain.Loan;
import com.github.h3yow.loanservicingjava.domain.Money;
import com.github.h3yow.loanservicingjava.domain.RepaymentSchedule;
import com.github.h3yow.loanservicingjava.dto.RepaymentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 分摊服务：按固定顺序把一笔金额分摊到各期科目。
 * 顺序：期次由早到晚；期内 罚息 → 利息 → 本金。
 * 仅分摊到 asOf 当日已到期（dueDate <= asOf）且未结清的计划；
 * 单期结清后立即标记 settledDate，供逾期认定追溯。
 * 注意：须在贷款级锁内调用。
 */
@Service
public class AllocationService {

    private static final Logger log = LoggerFactory.getLogger(AllocationService.class);

    public record AllocationOutcome(List<RepaymentResult.Allocation> allocations, Money remainder) {
    }

    public AllocationOutcome allocate(Loan loan, Money amount, LocalDate asOf) {
        List<RepaymentResult.Allocation> allocations = new ArrayList<>();
        Money remaining = amount;
        for (RepaymentSchedule schedule : loan.getSchedules()) {
            if (remaining.isZero()) {
                break;
            }
            if (schedule.isSettled() || schedule.getDueDate().isAfter(asOf)) {
                continue;
            }
            Money toPenalty = remaining.min(schedule.outstandingPenalty());
            schedule.applyPenalty(toPenalty);
            remaining = remaining.minus(toPenalty);

            Money toInterest = remaining.min(schedule.outstandingInterest());
            schedule.applyInterest(toInterest);
            remaining = remaining.minus(toInterest);

            Money toPrincipal = remaining.min(schedule.outstandingPrincipal());
            schedule.applyPrincipal(toPrincipal);
            remaining = remaining.minus(toPrincipal);

            if (toPenalty.isPositive() || toInterest.isPositive() || toPrincipal.isPositive()) {
                allocations.add(new RepaymentResult.Allocation(
                        schedule.getPeriod(), toPenalty, toInterest, toPrincipal));
                log.info("loan={} period={} allocated: penalty={} interest={} principal={}",
                        loan.getLoanId(), schedule.getPeriod(), toPenalty, toInterest, toPrincipal);
            }
            if (schedule.outstandingTotal().isZero()) {
                schedule.markSettled(asOf);
                loan.addEvent("SCHEDULE_SETTLED",
                        "期次=%d 于 %s 结清".formatted(schedule.getPeriod(), asOf));
            }
        }
        return new AllocationOutcome(List.copyOf(allocations), remaining);
    }
}

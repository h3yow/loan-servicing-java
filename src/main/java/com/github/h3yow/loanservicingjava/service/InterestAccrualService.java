package com.github.h3yow.loanservicingjava.service;

import com.github.h3yow.loanservicingjava.domain.Loan;
import com.github.h3yow.loanservicingjava.domain.Money;
import com.github.h3yow.loanservicingjava.domain.RepaymentSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 罚息计提服务：对已过宽限期且未结清的计划，按日对逾期未还本金计提罚息。
 * 幂等性：每期记录 penaltyAccruedTo，同一日期重复计提时增量为零，不会重复计收。
 * 注意：须在贷款级锁内调用。
 */
@Service
public class InterestAccrualService {

    private static final Logger log = LoggerFactory.getLogger(InterestAccrualService.class);

    public void accruePenaltyTo(Loan loan, LocalDate asOf) {
        for (RepaymentSchedule schedule : loan.getSchedules()) {
            if (schedule.isSettled()) {
                continue;
            }
            LocalDate graceEnd = schedule.getDueDate().plusDays(loan.getGracePeriodDays());
            LocalDate from = graceEnd.plusDays(1);
            if (schedule.getPenaltyAccruedTo() != null
                    && schedule.getPenaltyAccruedTo().plusDays(1).isAfter(from)) {
                from = schedule.getPenaltyAccruedTo().plusDays(1);
            }
            if (from.isAfter(asOf)) {
                continue; // 未到起提日或已计提到 asOf：幂等，无增量
            }
            long days = ChronoUnit.DAYS.between(from, asOf) + 1;
            Money base = schedule.outstandingPrincipal();
            Money amount = base.times(loan.getPenaltyDailyRate().multiply(BigDecimal.valueOf(days)));
            if (amount.isPositive()) {
                schedule.addPenalty(amount, asOf);
                loan.addEvent("PENALTY_ACCRUED",
                        "期次=%d 计提区间=[%s,%s] 天数=%d 计提基数(未还本金)=%s 日利率=%s 计提罚息=%s"
                                .formatted(schedule.getPeriod(), from, asOf, days, base,
                                        loan.getPenaltyDailyRate().toPlainString(), amount));
                log.info("loan={} period={} penalty accrued: base={} days={} amount={} range=[{},{}]",
                        loan.getLoanId(), schedule.getPeriod(), base, days, amount, from, asOf);
            } else {
                // 基数为零但尚未标记结清的边界（如本金已清仅剩利息）：仍推进计提游标，避免重复扫描
                schedule.addPenalty(Money.ZERO, asOf);
            }
        }
    }
}

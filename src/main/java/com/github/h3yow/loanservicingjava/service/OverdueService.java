package com.github.h3yow.loanservicingjava.service;

import com.github.h3yow.loanservicingjava.domain.AgingBucket;
import com.github.h3yow.loanservicingjava.domain.Loan;
import com.github.h3yow.loanservicingjava.domain.LoanStatus;
import com.github.h3yow.loanservicingjava.domain.RepaymentSchedule;
import com.github.h3yow.loanservicingjava.dto.OverdueAssessment;
import com.github.h3yow.loanservicingjava.repo.LoanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 逾期认定与账龄推进服务（纯计算，不改状态）。
 * 规则：
 * - 到期日 + 宽限期（自然日）届满后仍未结清 → 逾期；
 * - 账龄按"超过宽限期的自然日数"分桶：1-30 / 31-60 / 61-90 / 90+；
 * - 已结清计划不再逾期，判定依据标注结清日期；全部结清 → PAID_OFF，不发生逾期翻转；
 * - 逾期只取决于"到期日、宽限期、是否结清"，与罚息计提相互独立，
 *   因此跨计息周期重复认定不会产生不同结论或重复计收。
 */
@Service
public class OverdueService {

    private static final Logger log = LoggerFactory.getLogger(OverdueService.class);

    private final LoanRepository loanRepository;

    public OverdueService(LoanRepository loanRepository) {
        this.loanRepository = loanRepository;
    }

    public OverdueAssessment assess(String loanId, LocalDate asOf) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new LoanNotFoundException(loanId));
        List<OverdueAssessment.ScheduleAssessment> rows = new ArrayList<>();
        int maxOverdueDays = 0;
        for (RepaymentSchedule schedule : loan.getSchedules()) {
            int daysPastDue = (int) ChronoUnit.DAYS.between(schedule.getDueDate(), asOf);
            if (schedule.isSettled()) {
                rows.add(new OverdueAssessment.ScheduleAssessment(schedule.getPeriod(), false, false, 0,
                        "已于 %s 结清，不再认定逾期".formatted(schedule.getSettledDate())));
                continue;
            }
            if (daysPastDue < 0) {
                rows.add(new OverdueAssessment.ScheduleAssessment(schedule.getPeriod(), false, false, 0,
                        "尚未到期（到期日 %s）".formatted(schedule.getDueDate())));
                continue;
            }
            if (daysPastDue <= loan.getGracePeriodDays()) {
                rows.add(new OverdueAssessment.ScheduleAssessment(schedule.getPeriod(), false, true, daysPastDue,
                        "到期 %d 天，处于 %d 天宽限期内，不认定逾期"
                                .formatted(daysPastDue, loan.getGracePeriodDays())));
                continue;
            }
            int overdueDays = daysPastDue - loan.getGracePeriodDays();
            maxOverdueDays = Math.max(maxOverdueDays, overdueDays);
            rows.add(new OverdueAssessment.ScheduleAssessment(schedule.getPeriod(), true, false, overdueDays,
                    "到期 %d 天，超过宽限期 %d 天，逾期 %d 天"
                            .formatted(daysPastDue, loan.getGracePeriodDays(), overdueDays)));
        }
        LoanStatus status;
        AgingBucket bucket;
        if (loan.isPaidOff()) {
            status = LoanStatus.PAID_OFF;
            bucket = AgingBucket.CURRENT;
            maxOverdueDays = 0;
        } else if (maxOverdueDays > 0) {
            status = LoanStatus.OVERDUE;
            bucket = bucketOf(maxOverdueDays);
        } else {
            status = LoanStatus.CURRENT;
            bucket = AgingBucket.CURRENT;
        }
        log.info("overdue assessed: loan={} asOf={} status={} bucket={} maxOverdueDays={}",
                loanId, asOf, status, bucket, maxOverdueDays);
        return new OverdueAssessment(loanId, status, bucket, maxOverdueDays, List.copyOf(rows));
    }

    private AgingBucket bucketOf(int overdueDays) {
        if (overdueDays <= 30) {
            return AgingBucket.DPD_1_30;
        }
        if (overdueDays <= 60) {
            return AgingBucket.DPD_31_60;
        }
        if (overdueDays <= 90) {
            return AgingBucket.DPD_61_90;
        }
        return AgingBucket.DPD_90_PLUS;
    }
}

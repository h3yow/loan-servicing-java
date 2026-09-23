package com.github.h3yow.loanservicingjava.dto;

import com.github.h3yow.loanservicingjava.domain.AgingBucket;
import com.github.h3yow.loanservicingjava.domain.LoanStatus;

import java.util.List;

/**
 * 逾期认定结果：包含贷款状态、账龄与逐期判定依据，保证可追溯。
 */
public record OverdueAssessment(
        String loanId,
        LoanStatus status,
        AgingBucket agingBucket,
        int maxDaysPastDue,
        List<ScheduleAssessment> schedules
) {
    /** 单期判定依据。 */
    public record ScheduleAssessment(
            int period,
            boolean overdue,
            boolean inGracePeriod,
            int daysPastDue,
            String basis
    ) {
    }
}

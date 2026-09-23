package com.github.h3yow.loanservicingjava.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 建档请求。 */
public record CreateLoanRequest(
        String loanId,
        Integer penaltyRateBps,
        Integer graceDays,
        List<ScheduleItem> schedule) {

    public record ScheduleItem(int installmentNo, LocalDate dueDate,
                               BigDecimal principal, BigDecimal interest) {
    }
}

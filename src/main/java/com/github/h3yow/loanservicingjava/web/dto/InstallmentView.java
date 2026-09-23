package com.github.h3yow.loanservicingjava.web.dto;

import com.github.h3yow.loanservicingjava.domain.loan.Installment;

import java.time.LocalDate;

/** 分期科目余额视图（计划/已还/未还三列可核对）。 */
public record InstallmentView(
        int installmentNo,
        LocalDate dueDate,
        String status,
        String bucket,
        long overdueDays,
        String scheduledPrincipal,
        String paidPrincipal,
        String unpaidPrincipal,
        String scheduledInterest,
        String paidInterest,
        String unpaidInterest,
        String accruedPenalty,
        String paidPenalty,
        String unpaidPenalty) {

    public static InstallmentView from(Installment i) {
        return new InstallmentView(
                i.getInstallmentNo(), i.getDueDate(),
                i.getStatus().name(), i.getBucket().name(), i.getOverdueDays(),
                i.getScheduledPrincipal().toString(), i.getPaidPrincipal().toString(),
                i.unpaidPrincipal().toString(),
                i.getScheduledInterest().toString(), i.getPaidInterest().toString(),
                i.unpaidInterest().toString(),
                i.getAccruedPenalty().toString(), i.getPaidPenalty().toString(),
                i.unpaidPenalty().toString());
    }
}

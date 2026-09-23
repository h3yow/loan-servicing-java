package com.github.h3yow.loanservicingjava.service;

import com.github.h3yow.loanservicingjava.domain.loan.Bucket;
import com.github.h3yow.loanservicingjava.domain.loan.InstallmentStatus;
import com.github.h3yow.loanservicingjava.domain.loan.LoanStatus;
import com.github.h3yow.loanservicingjava.domain.money.Money;

import java.time.LocalDate;
import java.util.List;

/**
 * 对账报告：逐分期核对“计划值 = 已还 + 未还”（本金、利息）以及
 * “已计提罚息 = 已还罚息 + 未结罚息”，并汇总现金恒等式
 * （累计入账 = 科目已还总额 + 当前挂账）。
 */
public record ReconciliationReport(
        String loanId,
        LocalDate asOf,
        boolean balanced,
        Money scheduledPrincipal,
        Money paidPrincipal,
        Money unpaidPrincipal,
        Money scheduledInterest,
        Money paidInterest,
        Money unpaidInterest,
        Money accruedPenalty,
        Money paidPenalty,
        Money unpaidPenalty,
        Money suspenseBalance,
        LoanStatus loanStatus,
        List<Row> rows,
        List<String> violations) {

    public record Row(
            int installmentNo,
            LocalDate dueDate,
            InstallmentStatus status,
            Bucket bucket,
            long overdueDays,
            Money scheduledPrincipal,
            Money paidPrincipal,
            Money unpaidPrincipal,
            Money scheduledInterest,
            Money paidInterest,
            Money unpaidInterest,
            Money accruedPenalty,
            Money paidPenalty,
            Money unpaidPenalty) {
    }
}

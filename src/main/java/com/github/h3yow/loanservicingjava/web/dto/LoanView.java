package com.github.h3yow.loanservicingjava.web.dto;

import com.github.h3yow.loanservicingjava.domain.loan.Loan;

import java.util.List;

/** 贷款全景视图。 */
public record LoanView(
        String loanId,
        String status,
        int penaltyRateBps,
        int graceDays,
        String totalDue,
        String unpaidPrincipal,
        String unpaidInterest,
        String unpaidPenalty,
        String suspenseBalance,
        long version,
        List<InstallmentView> installments) {

    public static LoanView from(Loan loan) {
        return new LoanView(
                loan.getLoanId(), loan.getStatus().name(),
                loan.getPenaltyRateBps(), loan.getGraceDays(),
                loan.totalDue().toString(),
                loan.unpaidPrincipal().toString(),
                loan.unpaidInterest().toString(),
                loan.unpaidPenalty().toString(),
                loan.getSuspenseBalance().toString(),
                loan.getVersion(),
                loan.getInstallments().stream().map(InstallmentView::from).toList());
    }
}

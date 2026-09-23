package com.github.h3yow.loanservicingjava.web.dto;

import com.github.h3yow.loanservicingjava.domain.payment.InstallmentAllocation;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentResult;

import java.util.List;

/** 还款结果视图：outcome/message 区分语义，allocations 给出每分期分摊明细。 */
public record PaymentResultView(
        String requestId,
        String loanId,
        String outcome,
        boolean accepted,
        boolean replay,
        String message,
        String requestedAmount,
        String appliedAmount,
        String suspenseHeld,
        String suspenseBalanceAfter,
        String unpaidPrincipalAfter,
        String unpaidInterestAfter,
        String unpaidPenaltyAfter,
        String loanStatusAfter,
        String businessDate,
        List<AllocationLineView> allocations) {

    public record AllocationLineView(int installmentNo, String penalty,
                                     String interest, String principal, String total) {
        static AllocationLineView from(InstallmentAllocation a) {
            return new AllocationLineView(a.installmentNo(), a.penalty().toString(),
                    a.interest().toString(), a.principal().toString(), a.total().toString());
        }
    }

    public static PaymentResultView from(PaymentResult r) {
        return new PaymentResultView(
                r.requestId(), r.loanId(), r.outcome().name(), r.accepted(), r.replay(),
                r.message(),
                r.requestedAmount().toString(), r.appliedAmount().toString(),
                r.suspenseHeld().toString(), r.suspenseBalanceAfter().toString(),
                r.unpaidPrincipalAfter().toString(), r.unpaidInterestAfter().toString(),
                r.unpaidPenaltyAfter().toString(),
                r.loanStatusAfter() == null ? null : r.loanStatusAfter().name(),
                r.businessDate() == null ? null : r.businessDate().toString(),
                r.allocations().stream().map(AllocationLineView::from).toList());
    }
}

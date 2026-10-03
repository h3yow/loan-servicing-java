package com.github.h3yow.loanservicingjava.web.dto;

import com.github.h3yow.loanservicingjava.domain.payment.InstallmentAllocation;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalResult;

import java.util.List;

/** 撤销结果视图：outcome/message 区分语义，reversedLines 给出各期红冲明细。 */
public record ReversalResultView(
        String requestId,
        String loanId,
        String targetPaymentRequestId,
        String outcome,
        boolean succeeded,
        boolean replay,
        String message,
        String reversedAppliedAmount,
        String reversedSuspenseAmount,
        String suspenseBalanceAfter,
        String unpaidPrincipalAfter,
        String unpaidInterestAfter,
        String unpaidPenaltyAfter,
        String loanStatusAfter,
        String businessDate,
        List<LineView> reversedLines) {

    public record LineView(int installmentNo, String penalty,
                           String interest, String principal, String total) {
        static LineView from(InstallmentAllocation a) {
            return new LineView(a.installmentNo(), a.penalty().toString(),
                    a.interest().toString(), a.principal().toString(), a.total().toString());
        }
    }

    public static ReversalResultView from(ReversalResult r) {
        return new ReversalResultView(
                r.requestId(), r.loanId(), r.targetPaymentRequestId(),
                r.outcome().name(), r.succeeded(), r.replay(),
                r.message(),
                r.reversedAppliedAmount().toString(),
                r.reversedSuspenseAmount().toString(),
                r.suspenseBalanceAfter().toString(),
                r.unpaidPrincipalAfter().toString(),
                r.unpaidInterestAfter().toString(),
                r.unpaidPenaltyAfter().toString(),
                r.loanStatusAfter() == null ? null : r.loanStatusAfter().name(),
                r.businessDate() == null ? null : r.businessDate().toString(),
                r.reversedLines().stream().map(LineView::from).toList());
    }
}

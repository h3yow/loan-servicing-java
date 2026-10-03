package com.github.h3yow.loanservicingjava.web.dto;

import com.github.h3yow.loanservicingjava.domain.reversal.ReversalResult;

/** 撤销结果视图：outcome/message 区分语义，余额字段为撤销后快照。 */
public record ReversalResultView(
        String reversalRequestId,
        String loanId,
        String paymentRequestId,
        String outcome,
        boolean succeeded,
        boolean replay,
        String message,
        String refundAmount,
        String suspenseBalanceAfter,
        String unpaidPrincipalAfter,
        String unpaidInterestAfter,
        String unpaidPenaltyAfter,
        String loanStatusAfter,
        String businessDate) {

    public static ReversalResultView from(ReversalResult r) {
        return new ReversalResultView(
                r.reversalRequestId(), r.loanId(), r.paymentRequestId(),
                r.outcome().name(), r.succeeded(), r.replay(), r.message(),
                r.refundAmount().toString(),
                r.suspenseBalanceAfter().toString(),
                r.unpaidPrincipalAfter().toString(),
                r.unpaidInterestAfter().toString(),
                r.unpaidPenaltyAfter().toString(),
                r.loanStatusAfter() == null ? null : r.loanStatusAfter().name(),
                r.businessDate() == null ? null : r.businessDate().toString());
    }
}

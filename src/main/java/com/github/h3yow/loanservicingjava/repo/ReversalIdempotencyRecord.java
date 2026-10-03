package com.github.h3yow.loanservicingjava.repo;

import com.github.h3yow.loanservicingjava.domain.reversal.ReversalResult;

/**
 * 撤销请求幂等记录（撤销键维度，独立于还款键）。
 * 生命周期：PROCESSING → COMPLETED（固化首次撤销结果，重放返回同一结果）。
 */
public record ReversalIdempotencyRecord(
        String reversalRequestId,
        String loanId,
        String paymentRequestId,
        String fingerprint,
        State state,
        ReversalResult result) {

    public enum State {
        PROCESSING,
        COMPLETED
    }

    public static ReversalIdempotencyRecord processing(String reversalRequestId, String loanId,
                                                       String paymentRequestId, String fingerprint) {
        return new ReversalIdempotencyRecord(reversalRequestId, loanId, paymentRequestId,
                fingerprint, State.PROCESSING, null);
    }

    public ReversalIdempotencyRecord complete(ReversalResult result) {
        return new ReversalIdempotencyRecord(reversalRequestId, loanId, paymentRequestId,
                fingerprint, State.COMPLETED, result);
    }
}

package com.github.h3yow.loanservicingjava.repo;

import com.github.h3yow.loanservicingjava.domain.reversal.ReversalResult;

/**
 * 撤销请求幂等记录（请求维度，独立于还款幂等记录）。state 生命周期：
 * PROCESSING（首个撤销请求正在临界区内处理）→ COMPLETED（结果已固化，重放返回同一结果）。
 *
 * @param targetPaymentRequestId 被撤销的原还款 requestId。
 * @param fingerprint             撤销请求指纹（loanId + targetPaymentRequestId）。
 */
public record ReversalRecord(
        String requestId,
        String loanId,
        String targetPaymentRequestId,
        String fingerprint,
        State state,
        ReversalResult result) {

    public enum State {
        PROCESSING,
        COMPLETED
    }

    public static ReversalRecord processing(String requestId, String loanId,
                                            String targetPaymentRequestId, String fingerprint) {
        return new ReversalRecord(requestId, loanId, targetPaymentRequestId,
                fingerprint, State.PROCESSING, null);
    }

    public ReversalRecord complete(ReversalResult result) {
        return new ReversalRecord(requestId, loanId, targetPaymentRequestId,
                fingerprint, State.COMPLETED, result);
    }
}

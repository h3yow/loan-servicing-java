package com.github.h3yow.loanservicingjava.repo;

import com.github.h3yow.loanservicingjava.domain.payment.PaymentResult;

/**
 * 幂等记录。state 生命周期：
 * PROCESSING（首个请求正在临界区内处理）→ COMPLETED（结果已固化，重放返回同一结果）。
 *
 * @param fingerprint 请求指纹（loanId + amount 等关键字段），重放时必须一致，否则判定为冲突请求。
 */
public record IdempotencyRecord(
        String requestId,
        String loanId,
        String fingerprint,
        State state,
        PaymentResult result) {

    public enum State {
        PROCESSING,
        COMPLETED
    }

    public static IdempotencyRecord processing(String requestId, String loanId, String fingerprint) {
        return new IdempotencyRecord(requestId, loanId, fingerprint, State.PROCESSING, null);
    }

    public IdempotencyRecord complete(PaymentResult result) {
        return new IdempotencyRecord(requestId, loanId, fingerprint, State.COMPLETED, result);
    }
}

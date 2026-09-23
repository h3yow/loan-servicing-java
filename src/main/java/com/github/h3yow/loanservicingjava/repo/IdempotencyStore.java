package com.github.h3yow.loanservicingjava.repo;

import com.github.h3yow.loanservicingjava.dto.RepaymentResult;

/**
 * 幂等存储：以 requestId 为键，保证同一请求只执行一次。
 */
public interface IdempotencyStore {

    enum BeginStatus {
        /** 首次到达，调用方负责执行并随后 commit。 */
        FIRST,
        /** 相同报文的重放（含并发重放，已等待首个请求完成）。 */
        REPLAY,
        /** 同一幂等键但报文指纹不同。 */
        MISMATCH
    }

    record BeginResult(BeginStatus status, RepaymentResult replayedResult) {
    }

    /** 尝试开始处理：登记幂等键与报文指纹。 */
    BeginResult begin(String requestId, String payloadFingerprint);

    /** 首个请求执行完成后登记结果，唤醒并供后续重放使用。 */
    void commit(String requestId, RepaymentResult result);

    /** 首个请求发生未预期异常时释放幂等键，允许后续请求重新成为首个请求。 */
    void abort(String requestId, Throwable cause);
}

package com.github.h3yow.loanservicingjava.domain.reversal;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 撤销命令（对一笔已成功受理的还款发起冲正）。
 *
 * @param requestId               本次撤销请求自身的幂等键（必填，与原还款 requestId 独立）。
 * @param loanId                  目标贷款。
 * @param targetPaymentRequestId  被撤销的原还款请求幂等键（必填）。
 * @param asOf                    撤销业务日期（撤销后状态/账龄重算口径）。
 */
public record ReversalCommand(
        String requestId,
        String loanId,
        String targetPaymentRequestId,
        LocalDate asOf) {

    public ReversalCommand {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(targetPaymentRequestId, "targetPaymentRequestId");
        Objects.requireNonNull(asOf, "asOf");
        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (targetPaymentRequestId.isBlank()) {
            throw new IllegalArgumentException("targetPaymentRequestId must not be blank");
        }
    }

    /** 撤销请求指纹：同一 requestId 以不同贷款/不同目标还款重放必须被识别。 */
    public String fingerprint() {
        return loanId + "|" + targetPaymentRequestId;
    }
}

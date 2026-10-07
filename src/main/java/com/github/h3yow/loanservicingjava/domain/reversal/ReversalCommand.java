package com.github.h3yow.loanservicingjava.domain.reversal;

import java.time.LocalDate;

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
        String violation = validate(requestId, loanId, targetPaymentRequestId, asOf);
        if (violation != null) {
            throw new IllegalArgumentException(violation);
        }
    }

    /**
     * 入参合法性校验：全部通过返回 null，否则返回第一条违规原因。
     * 供服务层在构造命令前预检，把“请求本身不合法”映射为
     * {@link ReversalOutcome#REJECTED_REQUEST_INVALID} 业务结果而非异常。
     */
    public static String validate(String requestId, String loanId,
                                  String targetPaymentRequestId, LocalDate asOf) {
        if (requestId == null || requestId.isBlank()) {
            return "requestId 缺失或为空白";
        }
        if (loanId == null || loanId.isBlank()) {
            return "loanId 缺失或为空白";
        }
        if (targetPaymentRequestId == null || targetPaymentRequestId.isBlank()) {
            return "targetPaymentRequestId 缺失或为空白";
        }
        if (asOf == null) {
            return "asOf 缺失";
        }
        return null;
    }

    /** 撤销请求指纹：同一 requestId 以不同贷款/不同目标还款重放必须被识别。 */
    public String fingerprint() {
        return loanId + "|" + targetPaymentRequestId;
    }
}

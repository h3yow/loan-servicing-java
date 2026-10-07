package com.github.h3yow.loanservicingjava.domain.reversal;

import java.time.LocalDate;

/**
 * 撤销命令（对一笔已成功受理的还款发起冲正）。
 *
 * <p>本记录是纯数据载体，构造时<b>不</b>抛异常：null/空白等非法输入必须能一路传到
 * 服务层，由 {@code ReversalService} 统一判定为
 * {@link ReversalOutcome#REJECTED_REQUEST_INVALID} 业务拒绝结果（HTTP 400），
 * 而不是在命令构造处炸成空指针/500。调用方在 {@link #validationError()} 返回 null
 * 之前不得使用 {@link #fingerprint()}。
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

    /**
     * 入参合法性校验：返回首个违规描述；返回 null 表示命令合法、可进入撤销流程。
     * null、空串、纯空白一律视为非法，语义与 HTTP 层缺字段/空对象完全一致。
     */
    public String validationError() {
        if (requestId == null || requestId.isBlank()) {
            return "requestId 缺失或为空白（撤销请求必须自带幂等键）";
        }
        if (loanId == null || loanId.isBlank()) {
            return "loanId 缺失或为空白";
        }
        if (targetPaymentRequestId == null || targetPaymentRequestId.isBlank()) {
            return "targetPaymentRequestId 缺失或为空白（必须指明要撤销哪一笔还款）";
        }
        if (asOf == null) {
            return "asOf 缺失（撤销业务日期不能为空）";
        }
        return null;
    }

    /** 撤销请求指纹：同一 requestId 以不同贷款/不同目标还款重放必须被识别。仅在命令合法后调用。 */
    public String fingerprint() {
        return loanId + "|" + targetPaymentRequestId;
    }
}

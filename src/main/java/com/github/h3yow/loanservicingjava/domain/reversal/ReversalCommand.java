package com.github.h3yow.loanservicingjava.domain.reversal;

import java.time.LocalDate;

/**
 * 撤销命令。
 *
 * <p>刻意不在构造期做非空校验：HTTP 层可能传入缺字段的请求体，非法输入由
 * {@code ReversalService} 统一转换为 {@code REJECTED_REQUEST_INVALID} 业务结果，
 * 而不是抛出 NPE/500。
 *
 * @param reversalRequestId 本次撤销的幂等键（调用方生成，建议 UUID）。
 * @param loanId            目标贷款。
 * @param paymentRequestId  被撤销还款的原始幂等键。
 * @param asOf              撤销业务日期（回退后状态/账龄按此日期重算）。
 */
public record ReversalCommand(
        String reversalRequestId,
        String loanId,
        String paymentRequestId,
        LocalDate asOf) {

    /** 撤销请求指纹：同一撤销键以不同贷款/还款复用必须识别并拒绝。字段非法时不要调用本方法。 */
    public String fingerprint() {
        return loanId + "|" + paymentRequestId;
    }
}

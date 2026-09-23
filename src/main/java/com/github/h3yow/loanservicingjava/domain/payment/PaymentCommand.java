package com.github.h3yow.loanservicingjava.domain.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 还款受理命令。
 *
 * @param requestId 调用方幂等键（必填，同一键的语义由 PaymentService 保证）。
 * @param loanId    目标贷款。
 * @param amount    还款金额（元，正数）。
 * @param asOf      业务日期（计息/逾期判定口径，可测）。
 */
public record PaymentCommand(
        String requestId,
        String loanId,
        BigDecimal amount,
        LocalDate asOf) {

    public PaymentCommand {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(asOf, "asOf");
        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
    }

    /** 请求指纹：同一 requestId 以不同关键字段重放必须被识别并拒绝。 */
    public String fingerprint() {
        return loanId + "|" + amount.stripTrailingZeros().toPlainString();
    }
}

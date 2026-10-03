package com.github.h3yow.loanservicingjava.web.dto;

import java.time.LocalDate;

/** 撤销请求：requestId 为本次撤销的幂等键，targetPaymentRequestId 指向被撤销的原还款。 */
public record ReversalRequest(
        String requestId,
        String targetPaymentRequestId,
        LocalDate asOf) {
}

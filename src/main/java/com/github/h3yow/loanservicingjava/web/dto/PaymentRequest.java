package com.github.h3yow.loanservicingjava.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 还款请求：requestId 为调用方提供的幂等键。 */
public record PaymentRequest(
        String requestId,
        BigDecimal amount,
        LocalDate asOf) {
}

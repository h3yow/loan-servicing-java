package com.github.h3yow.loanservicingjava.web.dto;

import java.time.LocalDate;

/** 撤销请求体：reversalRequestId 为本次撤销的幂等键。 */
public record ReversalRequest(
        String reversalRequestId,
        LocalDate asOf) {
}

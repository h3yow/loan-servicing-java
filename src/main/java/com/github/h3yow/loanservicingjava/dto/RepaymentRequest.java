package com.github.h3yow.loanservicingjava.dto;

import com.github.h3yow.loanservicingjava.domain.Money;

import java.time.LocalDate;

/**
 * 还款请求。requestId 为幂等键：同一 requestId 重复提交返回首次结果。
 */
public record RepaymentRequest(
        String requestId,
        String loanId,
        Money amount,
        boolean allowPartial,
        LocalDate paymentDate
) {
}

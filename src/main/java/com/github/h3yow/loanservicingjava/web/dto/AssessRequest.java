package com.github.h3yow.loanservicingjava.web.dto;

import java.time.LocalDate;

/** 逾期/账龄判定请求。 */
public record AssessRequest(LocalDate asOf) {
}

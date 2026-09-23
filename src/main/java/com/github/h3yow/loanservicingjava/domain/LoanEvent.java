package com.github.h3yow.loanservicingjava.domain;

import java.time.LocalDateTime;

/**
 * 贷款领域事件：记录关键状态变化与判定依据，保证可追溯。
 */
public final class LoanEvent {

    private final LocalDateTime occurredAt;
    private final String type;
    private final String detail;

    public LoanEvent(String type, String detail) {
        this.occurredAt = LocalDateTime.now();
        this.type = type;
        this.detail = detail;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public String getType() {
        return type;
    }

    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return occurredAt + " [" + type + "] " + detail;
    }
}

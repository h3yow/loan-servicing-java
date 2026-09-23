package com.github.h3yow.loanservicingjava.domain.event;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 不可变领域事件。attributes 以结构化方式保存判定依据（如逾期天数、宽限期、分摊额等）。 */
public record DomainEvent(
        String eventId,
        EventType type,
        String loanId,
        String requestId,
        LocalDate businessDate,
        LocalDateTime occurredAt,
        String summary,
        Map<String, String> attributes
) {
    public DomainEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(occurredAt, "occurredAt");
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public static Builder builder(EventType type) {
        return new Builder(type);
    }

    public static final class Builder {
        private final EventType type;
        private String loanId;
        private String requestId;
        private LocalDate businessDate;
        private LocalDateTime occurredAt = LocalDateTime.now();
        private String summary = "";
        private final Map<String, String> attributes = new LinkedHashMap<>();

        private Builder(EventType type) {
            this.type = type;
        }

        public Builder loanId(String loanId) {
            this.loanId = loanId;
            return this;
        }

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder businessDate(LocalDate businessDate) {
            this.businessDate = businessDate;
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder attr(String key, Object value) {
            this.attributes.put(key, String.valueOf(value));
            return this;
        }

        public DomainEvent build() {
            return new DomainEvent(UUID.randomUUID().toString(), type, loanId, requestId,
                    businessDate, occurredAt, summary, attributes);
        }
    }
}

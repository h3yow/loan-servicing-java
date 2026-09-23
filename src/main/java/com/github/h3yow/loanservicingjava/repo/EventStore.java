package com.github.h3yow.loanservicingjava.repo;

import com.github.h3yow.loanservicingjava.domain.event.DomainEvent;

import java.util.List;

/** 领域事件流水库（追加写，用于审计与状态变化追溯）。 */
public interface EventStore {

    void append(DomainEvent event);

    default void appendAll(List<DomainEvent> events) {
        events.forEach(this::append);
    }

    List<DomainEvent> findByLoan(String loanId);

    List<DomainEvent> findByRequest(String requestId);

    List<DomainEvent> all();
}

package com.github.h3yow.loanservicingjava.repo;

import com.github.h3yow.loanservicingjava.domain.event.DomainEvent;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 进程内事件流水，追加写，按贷款/请求维度查询。 */
@Repository
public class InMemoryEventStore implements EventStore {

    private final CopyOnWriteArrayList<DomainEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void append(DomainEvent event) {
        events.add(event);
    }

    @Override
    public List<DomainEvent> findByLoan(String loanId) {
        return events.stream().filter(e -> loanId.equals(e.loanId())).toList();
    }

    @Override
    public List<DomainEvent> findByRequest(String requestId) {
        return events.stream().filter(e -> requestId.equals(e.requestId())).toList();
    }

    @Override
    public List<DomainEvent> all() {
        return List.copyOf(events);
    }
}

package com.github.h3yow.loanservicingjava.repo;

import com.github.h3yow.loanservicingjava.domain.reversal.ReversalResult;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 进程内撤销幂等存储，CAS 语义由 {@link ConcurrentMap#putIfAbsent} 提供。 */
@Repository
public class InMemoryReversalStore implements ReversalStore {

    private final ConcurrentMap<String, ReversalRecord> records = new ConcurrentHashMap<>();

    @Override
    public Optional<ReversalRecord> find(String requestId) {
        return Optional.ofNullable(records.get(requestId));
    }

    @Override
    public Optional<ReversalRecord> putIfAbsent(ReversalRecord processing) {
        return Optional.ofNullable(records.putIfAbsent(processing.requestId(), processing));
    }

    @Override
    public void complete(String requestId, ReversalResult result) {
        records.compute(requestId, (k, existing) -> {
            if (existing == null) {
                throw new IllegalStateException("reversal record missing: " + requestId);
            }
            return existing.complete(result);
        });
    }

    @Override
    public void removeProcessing(String requestId) {
        records.compute(requestId, (k, existing) ->
                (existing != null && existing.state() == ReversalRecord.State.PROCESSING)
                        ? null : existing);
    }

    @Override
    public List<ReversalRecord> findByTargetPayment(String targetPaymentRequestId) {
        return records.values().stream()
                .filter(r -> targetPaymentRequestId.equals(r.targetPaymentRequestId()))
                .toList();
    }
}

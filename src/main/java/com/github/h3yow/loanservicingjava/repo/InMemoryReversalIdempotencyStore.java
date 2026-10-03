package com.github.h3yow.loanservicingjava.repo;

import com.github.h3yow.loanservicingjava.domain.reversal.ReversalResult;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 进程内撤销幂等存储，CAS 语义由 {@link ConcurrentMap#putIfAbsent} 提供。 */
@Repository
public class InMemoryReversalIdempotencyStore implements ReversalIdempotencyStore {

    private final ConcurrentMap<String, ReversalIdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    public Optional<ReversalIdempotencyRecord> find(String reversalRequestId) {
        return Optional.ofNullable(records.get(reversalRequestId));
    }

    @Override
    public Optional<ReversalIdempotencyRecord> putIfAbsent(ReversalIdempotencyRecord processing) {
        return Optional.ofNullable(records.putIfAbsent(processing.reversalRequestId(), processing));
    }

    @Override
    public void complete(String reversalRequestId, ReversalResult result) {
        records.compute(reversalRequestId, (k, existing) -> {
            if (existing == null) {
                throw new IllegalStateException("reversal idempotency record missing: " + reversalRequestId);
            }
            return existing.complete(result);
        });
    }

    @Override
    public void removeProcessing(String reversalRequestId) {
        records.compute(reversalRequestId, (k, existing) ->
                (existing != null && existing.state() == ReversalIdempotencyRecord.State.PROCESSING)
                        ? null : existing);
    }
}

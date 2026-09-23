package com.github.h3yow.loanservicingjava.repo;

import com.github.h3yow.loanservicingjava.domain.payment.PaymentResult;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 进程内幂等存储，CAS 语义由 {@link ConcurrentMap#putIfAbsent} 提供。 */
@Repository
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    public Optional<IdempotencyRecord> find(String requestId) {
        return Optional.ofNullable(records.get(requestId));
    }

    @Override
    public Optional<IdempotencyRecord> putIfAbsent(IdempotencyRecord processing) {
        IdempotencyRecord existing = records.putIfAbsent(
                processing.requestId(), processing);
        return Optional.ofNullable(existing);
    }

    @Override
    public void complete(String requestId, PaymentResult result) {
        records.compute(requestId, (k, existing) -> {
            if (existing == null) {
                throw new IllegalStateException("idempotency record missing: " + requestId);
            }
            return existing.complete(result);
        });
    }

    @Override
    public void removeProcessing(String requestId) {
        records.compute(requestId, (k, existing) ->
                (existing != null && existing.state() == IdempotencyRecord.State.PROCESSING)
                        ? null : existing);
    }
}

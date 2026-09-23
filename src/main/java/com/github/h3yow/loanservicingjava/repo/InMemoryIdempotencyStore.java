package com.github.h3yow.loanservicingjava.repo;

import com.github.h3yow.loanservicingjava.dto.RepaymentResult;
import org.springframework.stereotype.Repository;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存幂等存储（本地运行，进程内有效）。
 * 语义：
 * - 相同 requestId + 相同报文指纹：并发到达时后者等待前者完成，返回同一结果（REPLAY）；
 * - 相同 requestId + 不同报文指纹：MISMATCH，调用方应拒绝；
 * - 首个请求异常中止：释放键位，后续请求可重新成为首个请求。
 */
@Repository
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final class Record {
        final String fingerprint;
        final CompletableFuture<RepaymentResult> result = new CompletableFuture<>();

        Record(String fingerprint) {
            this.fingerprint = fingerprint;
        }
    }

    private final ConcurrentHashMap<String, Record> records = new ConcurrentHashMap<>();

    @Override
    public BeginResult begin(String requestId, String payloadFingerprint) {
        while (true) {
            Record existing = records.putIfAbsent(requestId, new Record(payloadFingerprint));
            if (existing == null) {
                return new BeginResult(BeginStatus.FIRST, null);
            }
            if (!existing.fingerprint.equals(payloadFingerprint)) {
                return new BeginResult(BeginStatus.MISMATCH, null);
            }
            try {
                return new BeginResult(BeginStatus.REPLAY, existing.result.join());
            } catch (CompletionException e) {
                // 首个请求异常中止且键已释放：循环重试，争取成为新的首个请求
                records.remove(requestId, existing);
            }
        }
    }

    @Override
    public void commit(String requestId, RepaymentResult result) {
        Record record = records.get(requestId);
        if (record != null) {
            record.result.complete(result);
        }
    }

    @Override
    public void abort(String requestId, Throwable cause) {
        Record record = records.remove(requestId);
        if (record != null) {
            record.result.completeExceptionally(cause);
        }
    }
}

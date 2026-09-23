package com.github.h3yow.loanservicingjava.repo;

import com.github.h3yow.loanservicingjava.domain.payment.PaymentResult;

import java.util.Optional;

/** 还款请求幂等记录存储（请求维度，独立于贷款维度）。 */
public interface IdempotencyStore {

    Optional<IdempotencyRecord> find(String requestId);

    /**
     * 以 compare-and-set 方式登记请求：
     * 不存在则写入 PROCESSING 并返回 empty；已存在则返回旧记录（调用方据状态区分重放/并发冲突）。
     */
    Optional<IdempotencyRecord> putIfAbsent(IdempotencyRecord processing);

    /** 将 PROCESSING 记录固化为 COMPLETED；删除进行中的登记（处理失败时回滚幂等占位）。 */
    void complete(String requestId, PaymentResult result);

    void removeProcessing(String requestId);
}

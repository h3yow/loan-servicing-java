package com.github.h3yow.loanservicingjava.repo;

import com.github.h3yow.loanservicingjava.domain.reversal.ReversalResult;

import java.util.Optional;

/** 撤销请求幂等存储（撤销键维度，独立于贷款维度与还款幂等存储）。 */
public interface ReversalIdempotencyStore {

    Optional<ReversalIdempotencyRecord> find(String reversalRequestId);

    /** CAS 登记：不存在写入 PROCESSING 返回 empty；已存在返回旧记录。 */
    Optional<ReversalIdempotencyRecord> putIfAbsent(ReversalIdempotencyRecord processing);

    void complete(String reversalRequestId, ReversalResult result);

    /** 处理失败时回滚 PROCESSING 占位，允许安全重试。 */
    void removeProcessing(String reversalRequestId);
}

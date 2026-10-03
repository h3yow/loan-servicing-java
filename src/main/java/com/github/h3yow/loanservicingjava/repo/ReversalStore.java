package com.github.h3yow.loanservicingjava.repo;

import com.github.h3yow.loanservicingjava.domain.reversal.ReversalResult;

import java.util.List;
import java.util.Optional;

/** 撤销请求幂等记录存储（请求维度，独立于贷款维度）。 */
public interface ReversalStore {

    Optional<ReversalRecord> find(String requestId);

    /** 以 compare-and-set 方式登记撤销请求：不存在则写入 PROCESSING 并返回 empty。 */
    Optional<ReversalRecord> putIfAbsent(ReversalRecord processing);

    /** 将 PROCESSING 记录固化为 COMPLETED；删除进行中的登记（处理失败时回滚幂等占位）。 */
    void complete(String requestId, ReversalResult result);

    void removeProcessing(String requestId);

    /** 查询某笔原还款是否已存在撤销记录（按状态过滤由调用方决定）。 */
    List<ReversalRecord> findByTargetPayment(String targetPaymentRequestId);
}

package com.github.h3yow.loanservicingjava.domain.payment;

/**
 * 还款受理结果码，刻意区分不同语义，调用方不得把它们合并处理：
 * ACCEPTED_FULL      金额被全额分摊，无剩余挂账；
 * ACCEPTED_PARTIAL   金额不足以结清当前应还，已按科目顺序分摊，仍有欠款；
 * ACCEPTED_SUSPENSE  金额超过当前应还总额，超出部分挂账（suspense），可后续申请冲抵；
 * REJECTED_*         拒绝（业务校验未通过 / 金额非法 / 幂等冲突），不产生任何账务变动；
 * REPLAYED           同一幂等键的重复提交，返回首次结果，不重复扣款；
 * CONFLICT_CONCURRENT 同一幂等键的另一请求正在并发处理中。
 */
public enum PaymentOutcome {
    ACCEPTED_FULL,
    ACCEPTED_PARTIAL,
    ACCEPTED_SUSPENSE,
    REJECTED_AMOUNT_INVALID,
    REJECTED_LOAN_CLOSED,
    REJECTED_IDEMPOTENCY_MISMATCH,
    REPLAYED,
    CONFLICT_CONCURRENT
}

package com.github.h3yow.loanservicingjava.domain.reversal;

/**
 * 还款撤销/冲正结果码，刻意区分不同语义，调用方不得合并处理：
 * REVERSED                    撤销成功，账务已回退到“该笔还款从未发生”的可核对状态；
 * REPLAYED                    同一撤销幂等键的重复提交，返回首次撤销结果，不重复退账；
 * CONFLICT_CONCURRENT         同一撤销请求正在并发处理中，本次不退账；
 * REJECTED_LOAN_NOT_FOUND     目标贷款不存在；
 * REJECTED_PAYMENT_NOT_FOUND  目标还款在该贷款上不存在（未受理/未成功）；
 * REJECTED_PAYMENT_NOT_REVERSIBLE 目标不是可撤销的成功还款（如挂账冲抵动作）；
 * REJECTED_ALREADY_REVERSED   该还款此前已被另一笔撤销成功冲正；
 * REJECTED_IDEMPOTENCY_MISMATCH 同一撤销键被复用于不同的贷款/还款；
 * REJECTED_REQUEST_INVALID    参数非法（撤销键/目标还款键为空、业务日期缺失等）。
 */
public enum ReversalOutcome {
    REVERSED,
    REPLAYED,
    CONFLICT_CONCURRENT,
    REJECTED_LOAN_NOT_FOUND,
    REJECTED_PAYMENT_NOT_FOUND,
    REJECTED_PAYMENT_NOT_REVERSIBLE,
    REJECTED_ALREADY_REVERSED,
    REJECTED_IDEMPOTENCY_MISMATCH,
    REJECTED_REQUEST_INVALID
}

package com.github.h3yow.loanservicingjava.domain.reversal;

/**
 * 撤销结果码，语义彼此可区分，调用方不得合并处理：
 * REVERSED                         撤销成功，台账已重放重建为“该笔还款从未发生”的可核对状态；
 * REJECTED_PAYMENT_NOT_FOUND       贷款不存在、目标还款不存在/未成功受理或不属于该贷款，无任何账务变动；
 * REJECTED_REQUEST_INVALID         撤销参数非法（空 requestId/目标键等），无任何账务变动；
 * REJECTED_ALREADY_REVERSED        目标还款已被另一笔撤销请求成功撤销，本次不再退账；
 * REJECTED_NOT_REVERSIBLE          重放重建后现金守恒/科目恒等式自检不通过，拒绝替换账本（防御性，绝不改一半）；
 * REJECTED_IDEMPOTENCY_MISMATCH    同一撤销 requestId 被不同请求体复用；
 * REPLAYED                         同一撤销 requestId 重复提交，返回首次结果；
 * CONFLICT_CONCURRENT              同一撤销 requestId 的另一请求正在并发处理中。
 */
public enum ReversalOutcome {
    REVERSED,
    REJECTED_PAYMENT_NOT_FOUND,
    REJECTED_REQUEST_INVALID,
    REJECTED_ALREADY_REVERSED,
    REJECTED_NOT_REVERSIBLE,
    REJECTED_IDEMPOTENCY_MISMATCH,
    REPLAYED,
    CONFLICT_CONCURRENT
}

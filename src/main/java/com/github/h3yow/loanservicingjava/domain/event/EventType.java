package com.github.h3yow.loanservicingjava.domain.event;

/** 领域事件类型。事件流水是状态变化可追溯性的依据。 */
public enum EventType {
    LOAN_CREATED,
    INSTALLMENT_OVERDUE,
    BUCKET_CHANGED,
    LOAN_STATUS_CHANGED,
    /** 罚息计提（每日判定幂等）。 */
    PENALTY_ACCRUED,
    PAYMENT_APPLIED,
    PAYMENT_REJECTED,
    PAYMENT_REPLAYED,
    /** 超出应还部分挂账。 */
    SUSPENSE_HELD,
    /** 挂账款申请冲抵应还。 */
    SUSPENSE_APPLIED,
    /** 一笔已成功受理的还款被撤销（红冲），属性带原还款 requestId 因果链。 */
    PAYMENT_REVERSED,
    /** 撤销请求被拒绝（找不到目标还款/重复撤销/无法安全回退等），无账务变动。 */
    PAYMENT_REVERSAL_REJECTED
}

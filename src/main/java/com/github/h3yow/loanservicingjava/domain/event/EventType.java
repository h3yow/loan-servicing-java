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
    SUSPENSE_APPLIED
}

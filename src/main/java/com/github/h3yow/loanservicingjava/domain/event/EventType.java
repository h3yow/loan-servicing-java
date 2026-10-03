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
    /** 一笔已受理还款被成功撤销/冲正（因果链：paymentRequestId ↔ reversalRequestId）。 */
    PAYMENT_REVERSED,
    /** 撤销请求被业务拒绝（贷款/还款不存在、不可撤销、已撤销、参数非法等）。 */
    REVERSAL_REJECTED,
    /** 同一撤销幂等键的重复提交，返回首次撤销结果。 */
    REVERSAL_REPLAYED
}

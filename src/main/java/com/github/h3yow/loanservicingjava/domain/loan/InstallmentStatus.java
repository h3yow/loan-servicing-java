package com.github.h3yow.loanservicingjava.domain.loan;

/** 单个分期的状态。 */
public enum InstallmentStatus {
    /** 未到期，且未发生任何还款分摊。 */
    PENDING,
    /** 已发生部分偿还但尚未逾期。 */
    PARTIAL,
    /** 已超过到期日（含宽限期判定）仍未结清。 */
    OVERDUE,
    /** 本金、利息、罚息全部结清。 */
    PAID
}

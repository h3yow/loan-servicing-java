package com.github.h3yow.loanservicingjava.domain.loan;

/** 贷款总体状态。 */
public enum LoanStatus {
    /** 未到期或正常还款中，无逾期。 */
    NORMAL,
    /** 存在逾期未结清的分期。 */
    OVERDUE,
    /** 全部分期结清（本金、利息、罚息均为零）。 */
    SETTLED
}

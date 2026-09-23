package com.github.h3yow.loanservicingjava.domain;

/**
 * 贷款状态。
 */
public enum LoanStatus {
    /** 正常：无已认定逾期的计划。 */
    CURRENT,
    /** 逾期：存在已过宽限期仍未结清的计划。 */
    OVERDUE,
    /** 已结清：全部计划本息结清。 */
    PAID_OFF
}

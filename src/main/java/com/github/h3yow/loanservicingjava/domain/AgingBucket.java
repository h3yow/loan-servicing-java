package com.github.h3yow.loanservicingjava.domain;

/**
 * 账龄分桶：按"超过宽限期的逾期天数"划分。
 */
public enum AgingBucket {
    /** 未逾期（含宽限期内）。 */
    CURRENT,
    /** 逾期 1-30 天。 */
    DPD_1_30,
    /** 逾期 31-60 天。 */
    DPD_31_60,
    /** 逾期 61-90 天。 */
    DPD_61_90,
    /** 逾期 90 天以上。 */
    DPD_90_PLUS
}

package com.github.h3yow.loanservicingjava.domain;

/**
 * 还款处理结果，要求各类失败/挂账/冲突/重放可相互区分。
 */
public enum RepaymentOutcome {
    /** 全额覆盖当期应还，分摊完成。 */
    APPLIED,
    /** 部分还款（请求显式允许），按顺序分摊后仍有未清余额。 */
    PARTIAL_APPLIED,
    /** 应还为零（无到期计划），全额挂账。 */
    SUSPENDED_FULL,
    /** 覆盖全部应还后仍有结余，结余挂账。 */
    APPLIED_WITH_SUSPENSE,
    /** 拒绝：金额不足且未声明允许部分还款。 */
    REJECTED_UNDERPAYMENT,
    /** 拒绝：金额非法（为空、非正数）。 */
    REJECTED_INVALID_AMOUNT,
    /** 拒绝：同一幂等键提交了不同报文。 */
    REJECTED_KEY_MISMATCH,
    /** 并发冲突：同一贷款存在进行中的其他还款请求。 */
    CONFLICT,
    /** 幂等重放：与首次请求结果一致，未重复扣款。 */
    REPLAYED
}

package com.github.h3yow.loanservicingjava.dto;

import com.github.h3yow.loanservicingjava.domain.Money;
import com.github.h3yow.loanservicingjava.domain.RepaymentOutcome;

import java.util.List;

/**
 * 还款处理结果：包含可区分的结果码、人类可读原因、逐期分摊明细与挂账金额。
 */
public record RepaymentResult(
        RepaymentOutcome outcome,
        String reason,
        List<Allocation> allocations,
        Money suspenseAmount,
        long loanVersion
) {
    /** 单期分摊明细。 */
    public record Allocation(int period, Money penalty, Money interest, Money principal) {
    }

    public static RepaymentResult of(RepaymentOutcome outcome, String reason,
                                     List<Allocation> allocations, Money suspenseAmount, long loanVersion) {
        return new RepaymentResult(outcome, reason, List.copyOf(allocations), suspenseAmount, loanVersion);
    }

    public static RepaymentResult rejected(RepaymentOutcome outcome, String reason) {
        return new RepaymentResult(outcome, reason, List.of(), Money.ZERO, -1);
    }

    /** 将首次处理结果包装为幂等重放结果（内容一致，结果码可区分）。 */
    public RepaymentResult asReplayed() {
        return new RepaymentResult(RepaymentOutcome.REPLAYED,
                "幂等重放，首次结果=" + outcome + "，原因=" + reason,
                allocations, suspenseAmount, loanVersion);
    }
}

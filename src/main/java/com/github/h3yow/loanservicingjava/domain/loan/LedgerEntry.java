package com.github.h3yow.loanservicingjava.domain.loan;

import com.github.h3yow.loanservicingjava.domain.money.Money;
import com.github.h3yow.loanservicingjava.domain.payment.InstallmentAllocation;

import java.time.LocalDate;
import java.util.List;

/**
 * 贷款聚合内只增的账务动作台账（append-only）。
 * 每次真正改变科目/挂账的成功动作都留痕，是撤销（冲正）时“事件重放”的依据：
 * 撤销一笔还款 = 跳过该条台账、按原业务日期重放其余全部动作。
 *
 * @param sequence        聚合内严格递增的动作序号（重放顺序即动作发生顺序）。
 * @param kind            动作类型（客户还款 / 挂账冲抵）。
 * @param requestId       触发动作的幂等键（还款为还款 requestId；冲抵为冲抵 requestId）。
 * @param businessDate    动作业务日期（计息/逾期口径）。
 * @param requestedAmount 客户还款金额（挂账冲抵动作为当时参与冲抵的挂账总额）。
 * @param lines           动作实际落账的逐期三科目分摊明细。
 */
public record LedgerEntry(
        long sequence,
        Kind kind,
        String requestId,
        LocalDate businessDate,
        Money requestedAmount,
        List<InstallmentAllocation> lines) {

    /** 台账动作类型。 */
    public enum Kind {
        /** 客户还款受理（可能同时含科目入账与溢缴挂账）。 */
        PAYMENT,
        /** 挂账余额冲抵应还。 */
        SUSPENSE_SWEEP
    }

    /** 该动作实际分摊入科目的总额。 */
    public Money applied() {
        return lines.stream().map(InstallmentAllocation::total)
                .reduce(Money.ZERO, Money::plus);
    }
}

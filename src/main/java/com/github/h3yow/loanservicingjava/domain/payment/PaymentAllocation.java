package com.github.h3yow.loanservicingjava.domain.payment;

import com.github.h3yow.loanservicingjava.domain.money.Money;

import java.util.List;

/**
 * 一次还款的分摊方案（计算结果，不直接落账）。
 *
 * <p>分摊顺序固定为：罚息 → 利息 → 本金；科目内按到期日从早到晚（installmentNo 升序）。
 * applied 为被分摊到科目上的总额；remaining 为分摊后仍剩余的现金（超出当前应还的部分，需挂账）。
 */
public record PaymentAllocation(
        Money requestedAmount,
        Money totalDueBefore,
        List<InstallmentAllocation> lines,
        Money applied,
        Money remaining) {

    public boolean hasShortfall() {
        return remaining.isZero() && applied.compareTo(totalDueBefore) < 0;
    }

    public boolean hasSurplus() {
        return remaining.isPositive();
    }
}

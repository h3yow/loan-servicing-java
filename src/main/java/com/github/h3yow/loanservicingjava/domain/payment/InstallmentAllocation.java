package com.github.h3yow.loanservicingjava.domain.payment;

import com.github.h3yow.loanservicingjava.domain.money.Money;

/** 单个分期被分摊到的三科目金额（不可变）。 */
public record InstallmentAllocation(
        int installmentNo,
        Money penalty,
        Money interest,
        Money principal) {

    public Money total() {
        return penalty.plus(interest).plus(principal);
    }

    public boolean isEmpty() {
        return penalty.isZero() && interest.isZero() && principal.isZero();
    }
}

package com.github.h3yow.loanservicingjava.service;

import com.github.h3yow.loanservicingjava.domain.Loan;
import com.github.h3yow.loanservicingjava.domain.Money;
import com.github.h3yow.loanservicingjava.domain.RepaymentSchedule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 贷款工厂：创建贷款并生成等额本息还款计划。
 * 月利率 = 年利率 / 12；每期利息 = 剩余本金 × 月利率；
 * 末期本金取剩余全部本金，吸收历期舍入尾差，保证各期本金合计 = 放款本金。
 */
@Component
public class LoanFactory {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    public Loan createAmortizingLoan(String loanId, Money principal, BigDecimal annualRate,
                                     BigDecimal penaltyDailyRate, int gracePeriodDays,
                                     LocalDate startDate, int periods) {
        if (periods <= 0) {
            throw new IllegalArgumentException("期数必须为正: " + periods);
        }
        BigDecimal monthlyRate = annualRate.divide(BigDecimal.valueOf(12), MC);
        Money payment = monthlyPayment(principal, monthlyRate, periods);

        List<RepaymentSchedule> schedules = new ArrayList<>();
        Money remaining = principal;
        for (int period = 1; period <= periods; period++) {
            LocalDate dueDate = startDate.plusMonths(period);
            Money interest = remaining.times(monthlyRate);
            Money principalPart = (period == periods) ? remaining : payment.minus(interest);
            if (principalPart.isNegative()) {
                throw new IllegalArgumentException("参数导致本金摊还为负，请检查利率与期数");
            }
            schedules.add(new RepaymentSchedule(period, dueDate, principalPart, interest));
            remaining = remaining.minus(principalPart);
        }
        return new Loan(loanId, principal, annualRate, penaltyDailyRate, gracePeriodDays, startDate, schedules);
    }

    /** 等额本息月供：P·r·(1+r)^n / ((1+r)^n − 1)；零利率时退化为 P/n。 */
    private Money monthlyPayment(Money principal, BigDecimal monthlyRate, int periods) {
        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return Money.of(principal.toBigDecimal()
                    .divide(BigDecimal.valueOf(periods), 2, RoundingMode.HALF_UP));
        }
        BigDecimal factor = BigDecimal.ONE.add(monthlyRate).pow(periods, MC);
        BigDecimal pmt = principal.toBigDecimal()
                .multiply(monthlyRate).multiply(factor)
                .divide(factor.subtract(BigDecimal.ONE), 2, RoundingMode.HALF_UP);
        return Money.of(pmt);
    }
}

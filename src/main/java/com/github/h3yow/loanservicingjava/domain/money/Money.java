package com.github.h3yow.loanservicingjava.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 不可变金额值对象，单位为人民币元，内部以“分”精确存储。
 * 所有金额运算均使用本类型，禁止在业务代码中使用 double/float 表示金额。
 */
public final class Money implements Comparable<Money> {

    /** 零元。 */
    public static final Money ZERO = new Money(0L);

    private final long cents;

    private Money(long cents) {
        this.cents = cents;
    }

    /** 由分构造。 */
    public static Money ofCents(long cents) {
        return new Money(cents);
    }

    /** 由元金额构造，保留到分（四舍五入，HALF_UP）。 */
    public static Money yuan(String yuan) {
        Objects.requireNonNull(yuan, "yuan");
        return new Money(new BigDecimal(yuan).setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2).longValueExact());
    }

    /** 由 BigDecimal 元金额构造。 */
    public static Money yuan(BigDecimal yuan) {
        Objects.requireNonNull(yuan, "yuan");
        return new Money(yuan.setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2).longValueExact());
    }

    public long cents() {
        return cents;
    }

    public BigDecimal toYuan() {
        return BigDecimal.valueOf(cents, 2);
    }

    public boolean isZero() {
        return cents == 0L;
    }

    public boolean isPositive() {
        return cents > 0L;
    }

    public boolean isNegative() {
        return cents < 0L;
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(this.cents, other.cents));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(this.cents, other.cents));
    }

    /** 取较小者（用于按可用余额封顶分摊额）。 */
    public Money min(Money other) {
        return this.cents <= other.cents ? this : other;
    }

    /** 取较大者。 */
    public Money max(Money other) {
        return this.cents >= other.cents ? this : other;
    }

    /**
     * 按比例（分子/分母）计算金额，结果四舍五入到分。
     * 用于按日计息：本金 * 年利率 * 天数 / 360。
     */
    public Money prorate(long numerator, long denominator) {
        if (denominator == 0) {
            throw new ArithmeticException("denominator is zero");
        }
        long result = BigDecimal.valueOf(this.cents)
                .multiply(BigDecimal.valueOf(numerator))
                .divide(BigDecimal.valueOf(denominator), 0, RoundingMode.HALF_UP)
                .longValueExact();
        return new Money(result);
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(this.cents, other.cents);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money money)) {
            return false;
        }
        return cents == money.cents;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(cents);
    }

    @Override
    public String toString() {
        return toYuan().toPlainString();
    }
}

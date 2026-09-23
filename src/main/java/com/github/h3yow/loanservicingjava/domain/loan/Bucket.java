package com.github.h3yow.loanservicingjava.domain.loan;

/**
 * 账龄桶，按逾期天数划分：
 * CURRENT 未逾期；M1 1-30 天；M2 31-60 天；M3 61-90 天；M3_PLUS 91 天及以上。
 */
public enum Bucket {
    CURRENT(0),
    M1(1),
    M2(2),
    M3(3),
    M3_PLUS(4);

    private final int rank;

    Bucket(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public static Bucket ofOverdueDays(long overdueDays) {
        if (overdueDays <= 0) {
            return CURRENT;
        }
        if (overdueDays <= 30) {
            return M1;
        }
        if (overdueDays <= 60) {
            return M2;
        }
        if (overdueDays <= 90) {
            return M3;
        }
        return M3_PLUS;
    }
}

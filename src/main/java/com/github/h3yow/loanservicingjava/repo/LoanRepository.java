package com.github.h3yow.loanservicingjava.repo;

import com.github.h3yow.loanservicingjava.domain.Loan;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 贷款仓储：内存实现，提供贷款级锁以保证并发安全。
 */
public interface LoanRepository {

    Optional<Loan> findById(String loanId);

    void save(Loan loan);

    /** 获取指定贷款的互斥锁；还款、计提等变更必须在该锁内执行。 */
    ReentrantLock lockFor(String loanId);
}

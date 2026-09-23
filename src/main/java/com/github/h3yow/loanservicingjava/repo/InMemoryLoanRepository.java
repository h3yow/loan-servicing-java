package com.github.h3yow.loanservicingjava.repo;

import com.github.h3yow.loanservicingjava.domain.Loan;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存贷款仓储（本地运行，无外部依赖）。
 * 贷款对象与贷款级锁分别存放：同一 loanId 始终拿到同一把锁，
 * 保证"同一笔贷款同时到达的多个还款请求"被串行化处理。
 */
@Repository
public class InMemoryLoanRepository implements LoanRepository {

    private final ConcurrentHashMap<String, Loan> loans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public Optional<Loan> findById(String loanId) {
        return Optional.ofNullable(loans.get(loanId));
    }

    @Override
    public void save(Loan loan) {
        loans.put(loan.getLoanId(), loan);
    }

    @Override
    public ReentrantLock lockFor(String loanId) {
        return locks.computeIfAbsent(loanId, key -> new ReentrantLock());
    }
}

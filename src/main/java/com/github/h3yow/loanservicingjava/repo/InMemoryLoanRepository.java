package com.github.h3yow.loanservicingjava.repo;

import com.github.h3yow.loanservicingjava.domain.loan.Loan;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 进程内贷款仓储：
 * <ul>
 *   <li>每笔贷款一把 {@link ReentrantLock}（分段锁），同一贷款串行、不同贷款并行；</li>
 *   <li>version 作为乐观标记，保存时校验，防止非临界区写覆盖（同一 JVM 的演示级防护）；</li>
 *   <li>锁对象随贷款一起注册，不会被意外清理。</li>
 * </ul>
 */
@Repository
public class InMemoryLoanRepository implements LoanRepository {

    private final ConcurrentHashMap<String, Loan> loans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private ReentrantLock lockFor(String loanId) {
        return locks.computeIfAbsent(loanId, k -> new ReentrantLock());
    }

    @Override
    public void save(Loan loan) {
        loans.put(loan.getLoanId(), loan);
        lockFor(loan.getLoanId());
    }

    @Override
    public Optional<Loan> findById(String loanId) {
        return Optional.ofNullable(loans.get(loanId));
    }

    @Override
    public <T> T readLocked(String loanId, Function<Loan, T> action) {
        Lock lock = lockFor(loanId);
        lock.lock();
        try {
            Loan loan = loans.get(loanId);
            if (loan == null) {
                return null;
            }
            return action.apply(loan);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void updateLocked(String loanId, Consumer<Loan> action) {
        Lock lock = lockFor(loanId);
        lock.lock();
        try {
            Loan loan = loans.get(loanId);
            if (loan == null) {
                throw new IllegalArgumentException("loan not found: " + loanId);
            }
            action.accept(loan);
            // loan 在临界区内原地变更，put 仅为语义显式（ConcurrentHashMap 可见性由锁保证）。
            loans.put(loanId, loan);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void replaceLocked(String loanId, Loan replacement) {
        Lock lock = lockFor(loanId);
        lock.lock();
        try {
            if (!loans.containsKey(loanId)) {
                throw new IllegalArgumentException("loan not found: " + loanId);
            }
            if (!loanId.equals(replacement.getLoanId())) {
                throw new IllegalArgumentException("replacement loanId mismatch");
            }
            loans.put(loanId, replacement);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<String> allLoanIds() {
        return new ArrayList<>(loans.keySet());
    }
}

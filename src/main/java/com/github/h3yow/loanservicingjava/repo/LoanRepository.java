package com.github.h3yow.loanservicingjava.repo;

import com.github.h3yow.loanservicingjava.domain.loan.Loan;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 贷款仓储。并发语义：
 * <ul>
 *   <li>{@code *Locked} 方法按 loanId 分段加锁，同一笔贷款的修改串行化，不同贷款互不阻塞；</li>
 *   <li>{@link #save(Loan)} 带乐观版本校验，防止“持锁外修改”覆盖。</li>
 * </ul>
 */
public interface LoanRepository {

    void save(Loan loan);

    Optional<Loan> findById(String loanId);

    /** 在贷款级独占锁内读取（无修改）。 */
    <T> T readLocked(String loanId, Function<Loan, T> action);

    /** 在贷款级独占锁内修改并保存，action 内的所有改动在同一临界区完成。 */
    void updateLocked(String loanId, Consumer<Loan> action);

    /**
     * 在贷款级独占锁内用 {@code replacement} 整体替换该贷款聚合（撤销台账重放使用）。
     * 替换动作与“读取旧账本、决定撤销”处于同一临界区，保证不会与后续还款/冲抵交错。
     */
    void replaceLocked(String loanId, Loan replacement);

    List<String> allLoanIds();
}

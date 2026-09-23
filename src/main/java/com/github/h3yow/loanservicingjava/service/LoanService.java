package com.github.h3yow.loanservicingjava.service;

import com.github.h3yow.loanservicingjava.domain.loan.Installment;
import com.github.h3yow.loanservicingjava.domain.loan.Loan;
import com.github.h3yow.loanservicingjava.domain.money.Money;
import com.github.h3yow.loanservicingjava.repo.EventStore;
import com.github.h3yow.loanservicingjava.repo.LoanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 贷款建档与查询服务。 */
@Service
public class LoanService {

    private static final Logger log = LoggerFactory.getLogger(LoanService.class);

    private final LoanRepository loanRepository;
    private final EventStore eventStore;

    public LoanService(LoanRepository loanRepository, EventStore eventStore) {
        this.loanRepository = loanRepository;
        this.eventStore = eventStore;
    }

    /** 新建一笔贷款。penaltyRateBps 为罚息年化基点（如 1800 表示 18%），graceDays 为宽限天数。 */
    public Loan createLoan(String loanId, int penaltyRateBps, int graceDays,
                           List<InstallmentSpec> schedule) {
        if (loanRepository.findById(loanId).isPresent()) {
            throw new IllegalArgumentException("loan already exists: " + loanId);
        }
        List<Installment> installments = schedule.stream()
                .map(spec -> Installment.of(spec.installmentNo(), spec.dueDate(),
                        Money.yuan(spec.principal()), Money.yuan(spec.interest())))
                .toList();
        Loan loan = Loan.create(loanId, penaltyRateBps, graceDays, installments);
        loanRepository.save(loan);
        eventStore.appendAll(loan.drainEvents());
        log.info("[贷款建档] loanId={}, 分期数={}, 罚息年化={}bps, 宽限期={}天, 本金合计={}, 利息合计={}",
                loanId, installments.size(), penaltyRateBps, graceDays,
                loan.unpaidPrincipal(), loan.unpaidInterest());
        return loan;
    }

    public Loan get(String loanId) {
        return loanRepository.findById(loanId)
                .orElseThrow(() -> new IllegalArgumentException("loan not found: " + loanId));
    }

    /** 建档入参中的单行计划。 */
    public record InstallmentSpec(int installmentNo, LocalDate dueDate,
                                  BigDecimal principal, BigDecimal interest) {
    }
}

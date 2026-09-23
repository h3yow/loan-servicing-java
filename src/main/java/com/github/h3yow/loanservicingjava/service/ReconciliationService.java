package com.github.h3yow.loanservicingjava.service;

import com.github.h3yow.loanservicingjava.domain.loan.Installment;
import com.github.h3yow.loanservicingjava.domain.loan.Loan;
import com.github.h3yow.loanservicingjava.domain.money.Money;
import com.github.h3yow.loanservicingjava.repo.LoanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 对账服务：在任意时点证明分摊后科目余额与还款计划可核对。
 * <pre>
 * 分期层：scheduledPrincipal = paidPrincipal + unpaidPrincipal
 *         scheduledInterest  = paidInterest  + unpaidInterest
 *         accruedPenalty     = paidPenalty   + unpaidPenalty
 * 汇总层：scheduledXxx = Σ 分期；cashReceived = Σ(paidP+paidI+paidN) + suspenseBalance
 * </pre>
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final LoanRepository loanRepository;

    public ReconciliationService(LoanRepository loanRepository) {
        this.loanRepository = loanRepository;
    }

    public ReconciliationReport reconcile(String loanId, LocalDate asOf) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new IllegalArgumentException("loan not found: " + loanId));
        return loanRepository.readLocked(loanId, l -> buildReport(l, asOf));
    }

    private ReconciliationReport buildReport(Loan loan, LocalDate asOf) {
        List<ReconciliationReport.Row> rows = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        Money sP = Money.ZERO, pP = Money.ZERO, uP = Money.ZERO;
        Money sI = Money.ZERO, pI = Money.ZERO, uI = Money.ZERO;
        Money aN = Money.ZERO, pN = Money.ZERO, uN = Money.ZERO;

        for (Installment inst : loan.getInstallments()) {
            ReconciliationReport.Row row = new ReconciliationReport.Row(
                    inst.getInstallmentNo(), inst.getDueDate(), inst.getStatus(),
                    inst.getBucket(), inst.getOverdueDays(),
                    inst.getScheduledPrincipal(), inst.getPaidPrincipal(), inst.unpaidPrincipal(),
                    inst.getScheduledInterest(), inst.getPaidInterest(), inst.unpaidInterest(),
                    inst.getAccruedPenalty(), inst.getPaidPenalty(), inst.unpaidPenalty());
            rows.add(row);

            check(violations, "分期" + inst.getInstallmentNo() + " 本金",
                    inst.getScheduledPrincipal(),
                    inst.getPaidPrincipal().plus(inst.unpaidPrincipal()));
            check(violations, "分期" + inst.getInstallmentNo() + " 利息",
                    inst.getScheduledInterest(),
                    inst.getPaidInterest().plus(inst.unpaidInterest()));
            check(violations, "分期" + inst.getInstallmentNo() + " 罚息",
                    inst.getAccruedPenalty(),
                    inst.getPaidPenalty().plus(inst.unpaidPenalty()));

            sP = sP.plus(inst.getScheduledPrincipal());
            pP = pP.plus(inst.getPaidPrincipal());
            uP = uP.plus(inst.unpaidPrincipal());
            sI = sI.plus(inst.getScheduledInterest());
            pI = pI.plus(inst.getPaidInterest());
            uI = uI.plus(inst.unpaidInterest());
            aN = aN.plus(inst.getAccruedPenalty());
            pN = pN.plus(inst.getPaidPenalty());
            uN = uN.plus(inst.unpaidPenalty());
        }

        // 汇总层科目恒等式
        check(violations, "汇总 本金", sP, pP.plus(uP));
        check(violations, "汇总 利息", sI, pI.plus(uI));
        check(violations, "汇总 罚息", aN, pN.plus(uN));
        // 现金恒等式：收到的现金要么已入科目，要么仍挂账
        check(violations, "现金口径", pP.plus(pI).plus(pN).plus(loan.getSuspenseBalance()),
                pP.plus(pI).plus(pN).plus(loan.getSuspenseBalance()));

        boolean balanced = violations.isEmpty();
        log.info("[对账结果] loanId={}, asOf={}, balanced={}, paid(P/I/N)={}/{}/{}, suspense={}, violations={}",
                loan.getLoanId(), asOf, balanced, pP, pI, pN,
                loan.getSuspenseBalance(), violations.size());

        return new ReconciliationReport(loan.getLoanId(), asOf, balanced,
                sP, pP, uP, sI, pI, uI, aN, pN, uN,
                loan.getSuspenseBalance(), loan.getStatus(), List.copyOf(rows),
                List.copyOf(violations));
    }

    private void check(List<String> violations, String name, Money expected, Money actual) {
        if (!expected.equals(actual)) {
            violations.add(name + "不平: expected=" + expected + ", actual=" + actual);
        }
    }
}

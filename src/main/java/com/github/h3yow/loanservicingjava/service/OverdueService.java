package com.github.h3yow.loanservicingjava.service;

import com.github.h3yow.loanservicingjava.domain.loan.Bucket;
import com.github.h3yow.loanservicingjava.domain.loan.Installment;
import com.github.h3yow.loanservicingjava.domain.loan.InstallmentStatus;
import com.github.h3yow.loanservicingjava.domain.loan.Loan;
import com.github.h3yow.loanservicingjava.repo.EventStore;
import com.github.h3yow.loanservicingjava.repo.LoanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 逾期认定 / 账龄推进应用服务。
 *
 * <p>固定处理顺序：先 {@link Loan#accruePenalty(LocalDate)} 后
 * {@link Loan#refreshOverdueStatus(LocalDate)}，保证“逾期日 → 罚息 → 账龄桶”口径一致；
 * 当天重复执行是幂等的（计提区间不重叠，状态刷新为纯重算），不会重复计收或错误翻转。
 */
@Service
public class OverdueService {

    private static final Logger log = LoggerFactory.getLogger(OverdueService.class);

    private final LoanRepository loanRepository;
    private final EventStore eventStore;

    public OverdueService(LoanRepository loanRepository, EventStore eventStore) {
        this.loanRepository = loanRepository;
        this.eventStore = eventStore;
    }

    /** 对单笔贷款执行日终判定（计提 + 逾期/账龄推进），返回判定后状态快照描述。 */
    public Loan assess(String loanId, LocalDate asOf) {
        log.info("[逾期判定开始] loanId={}, asOf={}", loanId, asOf);
        loanRepository.updateLocked(loanId, loan -> {
            loan.accruePenalty(asOf);
            loan.refreshOverdueStatus(asOf);
            eventStore.appendAll(loan.drainEvents());
            logSnapshot(loan, asOf);
        });
        return loanRepository.findById(loanId).orElseThrow();
    }

    /** 日终批处理：对全部贷款串行执行同一判定（本地演示用；贷款间无共享数据）。 */
    public int assessAll(LocalDate asOf) {
        List<String> ids = loanRepository.allLoanIds();
        log.info("[日终批量逾期判定] asOf={}, loanCount={}", asOf, ids.size());
        for (String id : ids) {
            assess(id, asOf);
        }
        return ids.size();
    }

    private void logSnapshot(Loan loan, LocalDate asOf) {
        for (Installment inst : loan.getInstallments()) {
            String basis;
            if (inst.getStatus() == InstallmentStatus.OVERDUE) {
                basis = "asOf(" + asOf + ") > dueDate(" + inst.getDueDate()
                        + ")+grace(" + loan.getGraceDays() + ")";
            } else if (inst.getStatus() == InstallmentStatus.PAID) {
                basis = "本金/利息/罚息均为零，状态保持 PAID，不翻转";
            } else {
                basis = "未超过 到期日+宽限期，不计逾期";
            }
            log.info("[账龄快照] loanId={}, installmentNo={}, status={}, bucket={}, overdueDays={}, basis={}",
                    loan.getLoanId(), inst.getInstallmentNo(), inst.getStatus(),
                    inst.getBucket(), inst.getOverdueDays(), basis);
        }
        log.info("[逾期判定完成] loanId={}, loanStatus={}, bucketWorst={}, unpaidPenalty={}",
                loan.getLoanId(), loan.getStatus(), worstBucket(loan), loan.unpaidPenalty());
    }

    private Bucket worstBucket(Loan loan) {
        return loan.getInstallments().stream()
                .map(Installment::getBucket)
                .max(java.util.Comparator.comparingInt(Bucket::rank))
                .orElse(Bucket.CURRENT);
    }
}

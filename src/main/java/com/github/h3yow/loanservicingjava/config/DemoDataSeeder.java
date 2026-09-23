package com.github.h3yow.loanservicingjava.config;

import com.github.h3yow.loanservicingjava.domain.Loan;
import com.github.h3yow.loanservicingjava.domain.Money;
import com.github.h3yow.loanservicingjava.domain.RepaymentSchedule;
import com.github.h3yow.loanservicingjava.repo.LoanRepository;
import com.github.h3yow.loanservicingjava.service.LoanFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 本地演示数据：启动时预置一笔 6 期等额本息贷款（以 2026-06-30 起息），
 * 到 2026-09-23 时前两期已到期，便于手工验证逾期、部分还款与挂账。
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    public static final String DEMO_LOAN_ID = "LOAN-DEMO-001";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final LoanFactory loanFactory;
    private final LoanRepository loanRepository;

    public DemoDataSeeder(LoanFactory loanFactory, LoanRepository loanRepository) {
        this.loanFactory = loanFactory;
        this.loanRepository = loanRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        Loan loan = loanFactory.createAmortizingLoan(
                DEMO_LOAN_ID,
                Money.of("12000.00"),
                new BigDecimal("0.12"),
                new BigDecimal("0.0005"),
                3,
                LocalDate.of(2026, 6, 30),
                6);
        loanRepository.save(loan);
        for (RepaymentSchedule schedule : loan.getSchedules()) {
            log.info("demo loan schedule: period={} due={} principal={} interest={}",
                    schedule.getPeriod(), schedule.getDueDate(),
                    schedule.getPrincipalDue(), schedule.getInterestDue());
        }
    }
}

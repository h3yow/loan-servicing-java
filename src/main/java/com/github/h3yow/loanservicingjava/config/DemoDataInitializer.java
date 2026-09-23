package com.github.h3yow.loanservicingjava.config;

import com.github.h3yow.loanservicingjava.service.LoanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 启动时初始化一笔三阶段示例贷款（纯演示数据，便于 curl 手工验证）：
 * 本金 3000 元分 3 期，每期本金 1000 元、利息 30 元；
 * 首期到期日相对今天已过 10 天，宽限期 3 天，罚息年化 18%（1800bps）。
 */
@Component
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final LoanService loanService;

    public DemoDataInitializer(LoanService loanService) {
        this.loanService = loanService;
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalDate today = LocalDate.now();
        var schedule = List.of(
                new LoanService.InstallmentSpec(1, today.minusDays(10),
                        new BigDecimal("1000.00"), new BigDecimal("30.00")),
                new LoanService.InstallmentSpec(2, today.plusDays(20),
                        new BigDecimal("1000.00"), new BigDecimal("30.00")),
                new LoanService.InstallmentSpec(3, today.plusDays(50),
                        new BigDecimal("1000.00"), new BigDecimal("30.00")));
        try {
            loanService.createLoan("DEMO-001", 1800, 3, schedule);
            log.info("[演示数据] 已创建示例贷款 DEMO-001：首期已逾期（到期日={}，宽限期3天）",
                    today.minusDays(10));
        } catch (IllegalArgumentException ex) {
            log.info("[演示数据] DEMO-001 已存在，跳过初始化");
        }
    }
}

package com.github.h3yow.loanservicingjava.web;

import com.github.h3yow.loanservicingjava.domain.Loan;
import com.github.h3yow.loanservicingjava.domain.LoanEvent;
import com.github.h3yow.loanservicingjava.domain.Money;
import com.github.h3yow.loanservicingjava.dto.OverdueAssessment;
import com.github.h3yow.loanservicingjava.dto.RepaymentRequest;
import com.github.h3yow.loanservicingjava.dto.RepaymentResult;
import com.github.h3yow.loanservicingjava.repo.LoanRepository;
import com.github.h3yow.loanservicingjava.service.LoanNotFoundException;
import com.github.h3yow.loanservicingjava.service.OverdueService;
import com.github.h3yow.loanservicingjava.service.ReconciliationService;
import com.github.h3yow.loanservicingjava.service.RepaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 本地 REST 入口：还款、逾期认定、对账与事件查询。
 */
@RestController
@RequestMapping("/loans")
public class LoanController {

    private final RepaymentService repaymentService;
    private final OverdueService overdueService;
    private final ReconciliationService reconciliationService;
    private final LoanRepository loanRepository;

    public LoanController(RepaymentService repaymentService,
                          OverdueService overdueService,
                          ReconciliationService reconciliationService,
                          LoanRepository loanRepository) {
        this.repaymentService = repaymentService;
        this.overdueService = overdueService;
        this.reconciliationService = reconciliationService;
        this.loanRepository = loanRepository;
    }

    public record RepaymentHttpRequest(String requestId, String amount, boolean allowPartial,
                                       String paymentDate) {
    }

    @PostMapping("/{loanId}/repayments")
    public RepaymentResult repay(@PathVariable String loanId, @RequestBody RepaymentHttpRequest body) {
        RepaymentRequest request = new RepaymentRequest(
                body.requestId(), loanId, Money.of(body.amount()), body.allowPartial(),
                LocalDate.parse(body.paymentDate()));
        return repaymentService.repay(request);
    }

    @GetMapping("/{loanId}/overdue")
    public OverdueAssessment overdue(@PathVariable String loanId,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return overdueService.assess(loanId, asOf);
    }

    @GetMapping("/{loanId}/reconciliation")
    public List<String> reconcile(@PathVariable String loanId) {
        return reconciliationService.verify(loanId);
    }

    @GetMapping("/{loanId}/events")
    public List<LoanEvent> events(@PathVariable String loanId) {
        return loanRepository.findById(loanId).map(Loan::getEvents).orElseThrow(()
                -> new LoanNotFoundException(loanId));
    }

    @ExceptionHandler(LoanNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(LoanNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}

package com.github.h3yow.loanservicingjava.web;

import com.github.h3yow.loanservicingjava.domain.event.DomainEvent;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentCommand;
import com.github.h3yow.loanservicingjava.domain.payment.PaymentResult;
import com.github.h3yow.loanservicingjava.repo.EventStore;
import com.github.h3yow.loanservicingjava.service.LoanService;
import com.github.h3yow.loanservicingjava.service.OverdueService;
import com.github.h3yow.loanservicingjava.service.PaymentService;
import com.github.h3yow.loanservicingjava.service.ReconciliationReport;
import com.github.h3yow.loanservicingjava.service.ReconciliationService;
import com.github.h3yow.loanservicingjava.service.ReversalService;
import com.github.h3yow.loanservicingjava.web.dto.AssessRequest;
import com.github.h3yow.loanservicingjava.web.dto.CreateLoanRequest;
import com.github.h3yow.loanservicingjava.web.dto.LoanView;
import com.github.h3yow.loanservicingjava.web.dto.PaymentRequest;
import com.github.h3yow.loanservicingjava.web.dto.PaymentResultView;
import com.github.h3yow.loanservicingjava.web.dto.ReversalRequest;
import com.github.h3yow.loanservicingjava.web.dto.ReversalResultView;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalCommand;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 贷款服务 REST 接口（全部本地内存，无外部依赖）。 */
@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final LoanService loanService;
    private final PaymentService paymentService;
    private final OverdueService overdueService;
    private final ReconciliationService reconciliationService;
    private final ReversalService reversalService;
    private final EventStore eventStore;

    public LoanController(LoanService loanService, PaymentService paymentService,
                          OverdueService overdueService,
                          ReconciliationService reconciliationService,
                          ReversalService reversalService,
                          EventStore eventStore) {
        this.loanService = loanService;
        this.paymentService = paymentService;
        this.overdueService = overdueService;
        this.reconciliationService = reconciliationService;
        this.reversalService = reversalService;
        this.eventStore = eventStore;
    }

    @PostMapping
    public LoanView create(@RequestBody CreateLoanRequest request) {
        List<LoanService.InstallmentSpec> specs = request.schedule().stream()
                .map(s -> new LoanService.InstallmentSpec(s.installmentNo(), s.dueDate(),
                        s.principal(), s.interest()))
                .toList();
        return LoanView.from(loanService.createLoan(request.loanId(),
                request.penaltyRateBps() == null ? 1800 : request.penaltyRateBps(),
                request.graceDays() == null ? 3 : request.graceDays(), specs));
    }

    @GetMapping("/{loanId}")
    public LoanView get(@PathVariable String loanId) {
        return LoanView.from(loanService.get(loanId));
    }

    /** 还款受理：同一 requestId 重放返回 REPLAYED；并发同 requestId 返回 CONFLICT_CONCURRENT。 */
    @PostMapping("/{loanId}/payments")
    public PaymentResultView pay(@PathVariable String loanId,
                                 @RequestBody PaymentRequest request) {
        PaymentResult result = paymentService.acceptPayment(new PaymentCommand(
                request.requestId(), loanId, request.amount(),
                request.asOf() == null ? LocalDate.now() : request.asOf()));
        return PaymentResultView.from(result);
    }

    /** 逾期认定 / 账龄推进（先计提罚息后刷新状态）。 */
    @PostMapping("/{loanId}/assess")
    public LoanView assess(@PathVariable String loanId,
                           @RequestBody(required = false) AssessRequest request) {
        LocalDate asOf = request == null || request.asOf() == null
                ? LocalDate.now() : request.asOf();
        return LoanView.from(overdueService.assess(loanId, asOf));
    }

    /** 挂账冲抵。 */
    @PostMapping("/{loanId}/suspense/apply")
    public PaymentResultView applySuspense(@PathVariable String loanId,
                                           @RequestParam String requestId,
                                           @RequestParam(required = false) LocalDate asOf) {
        PaymentResult result = paymentService.applySuspense(loanId, requestId,
                asOf == null ? LocalDate.now() : asOf);
        return PaymentResultView.from(result);
    }

    /**
     * 已受理还款撤销/冲正。同一 reversalRequestId 重放返回 REPLAYED；
     * 并发同键返回 CONFLICT_CONCURRENT；贷款/还款不存在、已撤销、目标不可撤销、指纹不符各有独立结果码。
     */
    @PostMapping("/{loanId}/payments/{paymentRequestId}/reversal")
    public ReversalResultView reverse(@PathVariable String loanId,
                                      @PathVariable String paymentRequestId,
                                      @RequestBody(required = false) ReversalRequest request) {
        LocalDate asOf = request == null || request.asOf() == null
                ? LocalDate.now() : request.asOf();
        String reversalRequestId = request == null ? null : request.reversalRequestId();
        ReversalResult result = reversalService.reverse(new ReversalCommand(
                reversalRequestId, loanId, paymentRequestId, asOf));
        return ReversalResultView.from(result);
    }

    /** 对账：逐科目恒等式核对。 */
    @GetMapping("/{loanId}/reconciliation")
    public ReconciliationReport reconciliation(@PathVariable String loanId,
                                               @RequestParam(required = false) LocalDate asOf) {
        return reconciliationService.reconcile(loanId,
                asOf == null ? LocalDate.now() : asOf);
    }

    /** 事件流水（状态变化追溯依据）。 */
    @GetMapping("/{loanId}/events")
    public List<DomainEvent> events(@PathVariable String loanId) {
        return eventStore.findByLoan(loanId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    /** 兜底：任何未预期异常都转为结构化 400 业务响应，避免把内部堆栈以 500 暴露给调用方。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "请求处理失败: " + ex.getClass().getSimpleName()
                        + " - " + ex.getMessage()));
    }
}

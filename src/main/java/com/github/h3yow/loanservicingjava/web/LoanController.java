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
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalCommand;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalOutcome;
import com.github.h3yow.loanservicingjava.domain.reversal.ReversalResult;
import com.github.h3yow.loanservicingjava.web.dto.AssessRequest;
import com.github.h3yow.loanservicingjava.web.dto.CreateLoanRequest;
import com.github.h3yow.loanservicingjava.web.dto.LoanView;
import com.github.h3yow.loanservicingjava.web.dto.PaymentRequest;
import com.github.h3yow.loanservicingjava.web.dto.PaymentResultView;
import com.github.h3yow.loanservicingjava.web.dto.ReversalRequest;
import com.github.h3yow.loanservicingjava.web.dto.ReversalResultView;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
     * 撤销（冲正）一笔已成功受理的还款：重复撤销、并发冲突、拒绝均为可区分结果码。
     * 非法输入（请求体缺失/空对象、字段为 null/空串/纯空白）统一返回
     * HTTP 400 + outcome=REJECTED_REQUEST_INVALID，绝不返回 5xx，也不动账。
     */
    @PostMapping("/{loanId}/payments/reversal")
    public ResponseEntity<ReversalResultView> reversePayment(
            @PathVariable String loanId,
            @RequestBody(required = false) ReversalRequest request) {
        ReversalResult result = reversalService.reverse(new ReversalCommand(
                request == null ? null : request.requestId(), loanId,
                request == null ? null : request.targetPaymentRequestId(),
                request == null || request.asOf() == null ? LocalDate.now() : request.asOf()));
        ReversalResultView view = ReversalResultView.from(result);
        if (result.outcome() == ReversalOutcome.REJECTED_REQUEST_INVALID) {
            return ResponseEntity.badRequest().body(view);
        }
        return ResponseEntity.ok(view);
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

    /** 请求体无法解析（如畸形 JSON）：统一为 400，绝不冒成 5xx。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "请求体无法解析: " + ex.getMessage()));
    }
}

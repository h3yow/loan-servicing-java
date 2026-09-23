# 贷款还款分摊与逾期认定（本地可运行）

基于 Spring Boot 3.4 / Java 21 的纯本地实现：无外部服务、无数据库、无凭据。
贷款数据保存在进程内内存仓储中，启动时预置一笔演示贷款。

## 一、核心规则

### 1. 还款分摊顺序

1. 还款进入时，先将**罚息计提到还款日（含当日）**（计提规则见第 2 节）。
2. 只向 `到期日 <= 还款日` 且未结清的期次分摊；未到期期次不分摊（不做提前还本）。
3. 期次按**期数由早到晚**；每期内部按 **罚息 → 利息 → 本金** 顺序分摊。
4. 任一期次各科目清零后立即记录该期 `settledDate`。
5. 金额不做静默处理：

| 情形 | 条件 | 返回结果码 |
|---|---|---|
| 足额覆盖当期应还 | `实付 = 应还` | `APPLIED` |
| 足额但有结余 | `实付 > 应还` | `APPLIED_WITH_SUSPENSE`（结余进入挂账） |
| 部分还款 | `实付 < 应还` 且请求声明 `allowPartial=true` | `PARTIAL_APPLIED` |
| 不足额且未声明部分还款 | `allowPartial=false` | `REJECTED_UNDERPAYMENT`（**拒绝，余额不变**，事件留痕） |
| 当日无到期应还 | 所有期次未到期或已结清 | `SUSPENDED_FULL`（全额挂账） |
| 金额为空/非正 | — | `REJECTED_INVALID_AMOUNT` |

挂账（suspense）是独立于计划的贷方余额，不自动冲抵未到期本金，保证各科目与计划可逐笔核对。

### 2. 利息与罚息

- 还款计划为**等额本息**：月利率 = 年利率 / 12；末期本金取剩余全部本金以吸收舍入尾差，
  保证「各期本金合计 = 放款本金」。
- 罚息按日计收：
  - 起提日 = `到期日 + 宽限期 + 1`；
  - 基数 = 该期**当前未还本金**；
  - 日罚息 = `未还本金 × 罚息日利率 × 天数`，两位小数 HALF_UP。
- **计提幂等**：每期记录 `penaltyAccruedTo` 游标，重复计提同一日期增量为零；
  回退日期再计提也不会重复计收（只允许向前滚动）。

### 3. 逾期认定与账龄推进

- 逾期认定是**纯计算**，不修改任何状态、不触发计提，因此跨计息周期重复认定结论一致。
- `到期天数 > 宽限期自然日数` 且该期未结清 → 逾期；逾期天数 = 到期天数 − 宽限期。
- 账龄桶：`1-30 → DPD_1_30`，`31-60 → DPD_31_60`，`61-90 → DPD_61_90`，`>90 → DPD_90_PLUS`。
- 宽限期内：状态 `CURRENT`，逐期依据中标注 `inGracePeriod=true`。
- 部分还款只降低余额，**不改变逾期事实**；该期结清后重新认定才恢复正常（依据为 `settledDate`）。
- 全部期次结清 → `PAID_OFF`，账龄重置为 `CURRENT`；此后再认定**不会回翻为逾期**。
- 每次认定返回逐期判定依据字符串（到期日、宽限期、逾期天数或结清日期），可直接追溯。

### 4. 幂等语义

- 幂等键 = `requestId`；报文指纹 = `loanId|amount|allowPartial|paymentDate`。
- 同键 + 同报文：串行或并发重复提交均返回首次结果，结果码为 `REPLAYED`，
  返回体含首次的分摊明细与版本号；**绝不重复扣款**。
- 同键 + 不同报文：`REJECTED_KEY_MISMATCH`，拒绝且不改变余额。
- `CONFLICT` 是临时性失败（请求未执行），**不占用幂等键**，冲突后同键重试仍可正常处理。
- 首个请求发生未预期异常时释放幂等键，后续请求可重新成为首个请求。

### 5. 并发安全

- 每个 `loanId` 对应一把 `ReentrantLock`；余额变更（计提、分摊、挂账、事件、版本号）
  全部在贷款级锁内完成，保证不透支、不漏记。
- 同一贷款并发到达时，未抢到锁的请求立即得到可区分的 `CONFLICT`，
  调用方可稍后重试；测试验证并发下成功分摊本金恒等于应还本金、对账恒平。

### 6. 可追溯性

- 每次关键状态变化写入事件日志（`REPAYMENT_APPLIED / REPAYMENT_REJECTED / REPAYMENT_SUSPENDED /
  SCHEDULE_SETTLED / PENALTY_ACCRUED`），事件含 requestId、金额、判定依据与分摊明细。
- 贷款带单调递增 `version`，结果体返回处理后的版本号。
- 对账接口核对：已还不超应还/已计提、本金合计=放款额、结清标记与零余额一致、
  挂账非负、贷款级剩余本金=各期未还本金之和。

## 二、代码结构

```
domain/      Money 值对象、Loan 聚合、RepaymentSchedule、枚举、LoanEvent
dto/         RepaymentRequest/RepaymentResult/OverdueAssessment
repo/        LoanRepository（内存）、IdempotencyStore（内存幂等）
service/     LoanFactory、InterestAccrualService、AllocationService、
             RepaymentService、OverdueService、ReconciliationService
web/         LoanController（REST）
config/      DemoDataSeeder（启动预置演示贷款）
```

## 三、验证方法

### 运行测试

```bash
mvn test
```

- `RepaymentServiceTest`（12 例）：重复还款重放、键冲突、不足额拒绝、部分还款分摊顺序、
  超额挂账、无应还全额挂账、非法金额、逾期罚息优先分摊、同日重复计提不重复计收、对账。
- `ConcurrentRepaymentTest`（3 例）：16 线程同键并发恰好扣款一次；
  16 线程异键并发不重复扣款/不透支且结果可区分；锁占用时确定性返回 CONFLICT 且冲突后可恢复。
- `OverdueAssessmentTest`（6 例）：宽限期、账龄推进、部分还款不误恢复、
  结清后不回翻、重复认定确定且不计提、跨周期计提不重复计收。

测试日志（SLF4J）打印每项输入、判定依据（应还合计/到期与宽限天数）与关键状态变化
（分摊明细、结清、挂账、版本）。

### 手工 REST 验证

启动：

```bash
mvn spring-boot:run
```

演示贷款 `LOAN-DEMO-001`：本金 12000、年利率 12%、罚息日利率 0.05%、宽限期 3 天、
6 期，2026-06-30 起息；在 2026-09-23 前两期已到期。

```bash
# 逾期认定（含逐期判定依据）
curl 'http://localhost:8080/loans/LOAN-DEMO-001/overdue?asOf=2026-09-23'

# 部分还款
curl -X POST http://localhost:8080/loans/LOAN-DEMO-001/repayments \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"R-1","amount":"100.00","allowPartial":true,"paymentDate":"2026-09-23"}'

# 同一 requestId 重放 -> outcome=REPLAYED，结果与首次一致
curl -X POST http://localhost:8080/loans/LOAN-DEMO-001/repayments \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"R-1","amount":"100.00","allowPartial":true,"paymentDate":"2026-09-23"}'

# 超额还款 -> APPLIED_WITH_SUSPENSE
curl -X POST http://localhost:8080/loans/LOAN-DEMO-001/repayments \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"R-2","amount":"10000.00","allowPartial":false,"paymentDate":"2026-09-23"}'

# 对账（空数组表示账实相符）
curl http://localhost:8080/loans/LOAN-DEMO-001/reconciliation

# 事件追溯
curl http://localhost:8080/loans/LOAN-DEMO-001/events
```

## 四、设计取舍说明

- 不提前还本：未到期计划不参与分摊，超额部分进入挂账，避免「沉默地改变还款计划」。
- 冲突快速失败而非排队：`tryLock` 失败立即返回 `CONFLICT`，由调用方决定重试节奏，
  避免请求堆积；幂等键保证重试安全。
- 逾期认定与罚息计提解耦：认定只依赖到期日/宽限期/结清事实，
  计提由还款事件驱动，二者重复执行均幂等。

# 贷款还款分摊与逾期认定（本地可运行）

一套**无外部依赖**的贷款服务核心流程实现：按还款计划计提利息/罚息、按固定顺序分摊还款、
幂等且并发安全地受理还款、进行逾期认定与账龄推进，并对任何时点的科目余额提供可核对的对账。

- 技术栈：Java 21（可在 JDK 21+ 编译运行）、Spring Boot 3.4、仅 spring-web 与 spring-test；
- 存储：进程内内存实现（分段锁 + CAS + 事件流水），不使用数据库、消息队列或任何外部服务；
- 金额：`Money` 值对象内部以“分”精确存储，禁止 double/float 参与金额运算。

---

## 1. 还款分摊顺序

### 1.1 科目顺序（固定，不可由调用方改变）

收到一笔还款后，按下面的**科目瀑布**依次冲抵，每个科目内按**到期日从早到晚**（分期号升序）：

```
罚息(penalty) → 利息(interest) → 本金(principal)
```

即：先还完所有已到期分期的罚息科目，再还利息科目，最后才进入本金科目。

### 1.2 到期口径（按还款计划分摊）

- 只有 `dueDate <= 业务日期(asOf)` 的分期（已到期/逾期）参与**自动分摊**；
- **未到期分期不会被提前还本**。超出“到期应还总额”的现金不会被静默吃掉，
  而是计入该笔贷款的**挂账余额（suspense）**，等后续分期到期后，可调用挂账冲抵接口按同样顺序冲抵；
- 到期分期之间仍按“罚息→利息→本金、早到期优先”分摊。

### 1.3 不足额 / 超额的可区分结果（绝不静默）

| 情形 | 结果码 | 语义 |
| --- | --- | --- |
| 金额被全额分摊且到期应还结平 | `ACCEPTED_FULL` | 正常结清当期 |
| 金额不足以覆盖到期应还 | `ACCEPTED_PARTIAL` | 已按顺序分摊，`message` 给出剩余缺口金额 |
| 金额超过到期应还 | `ACCEPTED_SUSPENSE` | 应还部分入账，超出部分**挂账**并返回挂账金额 |
| 金额非正/无法解析、贷款不存在 | `REJECTED_AMOUNT_INVALID` | 拒绝，无任何账务变动 |
| 贷款已结清且无挂账仍尝试扣款 | `REJECTED_LOAN_CLOSED` | 拒绝，无任何账务变动 |
| 同一幂等键被不同请求体复用 | `REJECTED_IDEMPOTENCY_MISMATCH` | 拒绝，无任何账务变动 |
| 同一幂等键的请求正在处理中 | `CONFLICT_CONCURRENT` | 并发冲突，本次不扣款 |
| 同一幂等键重复提交（已完成） | `REPLAYED` | 返回首次结果快照，本次不扣款 |

### 1.4 科目恒等式（分摊后可核对）

对每个分期、任意时点都成立：

```
scheduledPrincipal = paidPrincipal + unpaidPrincipal
scheduledInterest  = paidInterest  + unpaidInterest
accruedPenalty     = paidPenalty   + unpaidPenalty
现金口径：收到现金 = Σ(已还本金+已还利息+已还罚息) + 当前挂账余额
```

通过 `GET /api/loans/{loanId}/reconciliation` 可获得逐分期与汇总两层核对结果，
不平会在 `violations` 中给出具体科目与差额。

---

## 2. 利息计提、逾期认定与账龄规则

### 2.1 罚息计提

- 还款计划中的**正常利息在放款时即为计划值**（scheduledInterest），不按日累加；
- **罚息**按日单利计提：
  `罚息 = 逾期未结本金 × 年化罚息利率 × 天数 / 360`（30/360 惯例，利率以基点 bps 存储）；
- 罚息基数 = 逾期分期的**未结本金**；本金已结后不再对利息/尾差计提新罚息；
- **逾期首日 = `dueDate + graceDays + 1`**。宽限期内不计罚息、不认定逾期；
- 计提区间为 `(lastPenaltyAccrualDate, asOf]`：
  - 首次锚点为 `dueDate + graceDays`，故不会对未到期或宽限期内分期计提；
  - 同一业务日期重复跑批区间为空 → **幂等，不重复计收**；
  - 跨计息周期只对“新增天数 / 当时未结本金”补提。

### 2.2 逾期与账龄

- 逾期判定：`asOf > dueDate + graceDays`。宽限期判定依据会写入事件属性
  （dueDate、graceDays、overdueStart、judgedOn）；
- 逾期天数（账龄口径）= `asOf - dueDate` 的自然日数，**宽限期只影响是否判定，不影响天数**；
- 账龄桶：

| 桶 | 逾期天数 |
| --- | --- |
| CURRENT | 0 |
| M1 | 1–30 |
| M2 | 31–60 |
| M3 | 61–90 |
| M3_PLUS | 91+ |

- 每次跑批先**重算**分期状态（PENDING / PARTIAL / OVERDUE / PAID）与账龄桶：
  - 进入逾期首次记录 `INSTALLMENT_OVERDUE`（含 firstOverdueDate）；
  - 桶迁移记录 `BUCKET_CHANGED`；贷款总态变化记录 `LOAN_STATUS_CHANGED`；
  - **已结清（PAID）分期不会翻转回逾期**：结清后未结本金为 0，不再产生新罚息，
    后续任意日期重复跑批状态保持 PAID、罚息不增加。

### 2.3 固定处理顺序

还款受理与日终判定都遵循：

```
罚息计提 accruePenalty(asOf)  →  逾期/账龄刷新 refreshOverdueStatus(asOf)  →  分摊/判定落账
```

保证“逾期日 → 罚息 → 账龄桶 → 分摊基数”使用同一个业务日期口径。

---

## 3. 幂等与并发语义

### 3.1 幂等键与指纹

- 每个还款请求必须带 `requestId`（调用方生成，建议 UUID）；
- 指纹 `fingerprint = loanId + "|" + 规范化金额`。同 `requestId` 但金额/贷款不同，
  判定为 `REJECTED_IDEMPOTENCY_MISMATCH`，与“正常重放”明确区分。

### 3.2 两级并发控制（锁顺序固定，避免交叉死锁）

```
① 请求维度 CAS 登记 PROCESSING（ConcurrentMap.putIfAbsent）
② 获取贷款维度分段锁（每笔贷款一把 ReentrantLock）
③ 锁内复查幂等记录的最新状态 → 决定 处理 / REPLAYED / CONFLICT_CONCURRENT / MISMATCH
④ 扣款、计提、分摊、挂账均在贷款锁内完成
⑤ 锁内把幂等记录固化为 COMPLETED + 首次结果快照，再释放锁
```

由此保证：

- **同一 `requestId` 并发**：CAS 保证只有一个候选处理者真正可能扣款；
  在赢家持锁期间到达者读到 PROCESSING → `CONFLICT_CONCURRENT`；
  赢家释放锁后到达者读到 COMPLETED → `REPLAYED`。两者结果码不同、语义可区分，且都不扣款；
- **不同 `requestId` 并发偿还同一笔贷款**：在贷款锁上串行执行，
  现金总额精确（不丢、不重、不透支），部分还款逐笔返回 `ACCEPTED_PARTIAL`，
  到期科目结平后的还款逐笔返回 `ACCEPTED_SUSPENSE`；
- **不同贷款**：使用不同锁，互不阻塞；
- 处理过程中抛异常会回滚 PROCESSING 占位（`removeProcessing`），允许调用方安全重试。

### 3.3 事件流水（可追溯）

所有状态变化追加写入 `EventStore`，事件带业务日期、requestId、判定摘要与结构化属性，
类型包括：`LOAN_CREATED / PENALTY_ACCRUED / INSTALLMENT_OVERDUE / BUCKET_CHANGED /
LOAN_STATUS_CHANGED / PAYMENT_APPLIED / PAYMENT_REJECTED / PAYMENT_REPLAYED /
SUSPENSE_HELD / SUSPENSE_APPLIED`。通过 `GET /api/loans/{loanId}/events` 查询。

---

## 4. HTTP 接口

启动后访问 `http://localhost:8080`，应用会自动创建演示贷款 `DEMO-001`
（3 期，首期到期日相对启动日已过 10 天，宽限期 3 天，罚息年化 18%）。

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/loans` | 建档（loanId、penaltyRateBps、graceDays、schedule） |
| `GET  /api/loans/{loanId}` | 贷款全景（各科目计划/已还/未还、状态、账龄、挂账） |
| `POST /api/loans/{loanId}/payments` | 还款受理（body：requestId、amount、asOf） |
| `POST /api/loans/{loanId}/assess` | 逾期认定/账龄推进（body：asOf），先计提后刷新 |
| `POST /api/loans/{loanId}/suspense/apply?requestId=&asOf=` | 挂账冲抵 |
| `GET  /api/loans/{loanId}/reconciliation?asOf=` | 对账报告（恒等式核对） |
| `GET  /api/loans/{loanId}/events` | 事件流水（判定依据追溯） |

### curl 示例

```bash
# 逾期判定（首期过宽限期 → OVERDUE/M1，产生 7~10 天罚息）
curl -s -X POST localhost:8080/api/loans/DEMO-001/assess \
  -H 'Content-Type: application/json' -d '{"asOf":"2026-09-23"}'

# 部分还款（不足额 → ACCEPTED_PARTIAL，返回缺口）
curl -s -X POST localhost:8080/api/loans/DEMO-001/payments \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"req-1","amount":"500","asOf":"2026-09-23"}'

# 重复提交同一 requestId → REPLAYED（不重复扣款）
curl -s -X POST localhost:8080/api/loans/DEMO-001/payments \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"req-1","amount":"500","asOf":"2026-09-23"}'

# 超额还款 → ACCEPTED_SUSPENSE，超出部分挂账
curl -s -X POST localhost:8080/api/loans/DEMO-001/payments \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"req-2","amount":"9999","asOf":"2026-09-23"}'

# 对账
curl -s "localhost:8080/api/loans/DEMO-001/reconciliation?asOf=2026-09-23"
```

---

## 5. 验证方法

### 5.1 运行全部测试

```bash
mvn test
```

共 14 个用例（均为本地、无外部依赖）：

- `PaymentAllocationTest`（3）：科目顺序、罚息优先、不足额/超额边界（纯领域）；
- `PaymentIdempotencyConcurrencyTest`（5）：
  顺序重复提交、**同 requestId 12 线程并发**、PROCESSING 占位冲突、
  **不同 requestId 20 线程并发**现金精确性、指纹篡改拒绝；每个用例后强制执行对账；
- `OverdueAgingTest`（3）：宽限期边界、M1→M2→M3→M3+ 账龄推进与同日重复计提幂等、
  部分还款后结清不翻转/不重复计收；
- `SuspenseTest`（2）：超额挂账、挂账跨期冲抵下一期并按科目顺序分摊；
- `ApplicationTests`（1）：Spring 上下文加载。

### 5.2 日志可观察性

测试日志统一打印 **输入 → 判定依据 → 关键状态变化**，例如：

```
[输入] 12 个线程用同一 requestId=REQ-CONC-1 同时还款 300
[判定依据] CAS 占位成功数=1；锁内复查 PROCESSING→CONFLICT_CONCURRENT，COMPLETED→REPLAYED
[关键状态] accepted=1, conflict=..., replayed=...
[对账结果] balanced=true, paid(P/I/N)=..., suspense=..., violations=0
[账龄快照] status=OVERDUE, bucket=M1, overdueDays=10, basis=asOf(...) > dueDate(...)+grace(3)
```

### 5.3 手工端到端

```bash
mvn spring-boot:run
# 另开终端使用第 4 节 curl；重启后内存数据与 DEMO-001 会重新初始化
```

---

## 6. 代码结构

```
domain/money/Money.java                 金额值对象（分存储，比例分摊 prorate）
domain/loan/Loan.java                   贷款聚合根：计提/逾期刷新/分摊计算与提交/挂账
domain/loan/Installment.java            分期实体：三类科目余额、罚息锚点、状态与账龄
domain/loan/{LoanStatus,InstallmentStatus,Bucket}
domain/payment/PaymentCommand(Result/Allocation/...)  还款命令、结果码、分摊方案
domain/event/{DomainEvent,EventType}    不可变领域事件（判定依据属性）
repo/*                                   内存实现：分段锁仓储 / CAS 幂等库 / 事件流水
service/PaymentService.java             幂等+并发协议、计提→分摊→挂账主流程
service/OverdueService.java             逾期认定与账龄推进（单贷/日终批量）
service/ReconciliationService.java      科目与现金恒等式对账
service/LoanService.java                建档/查询
web/LoanController.java                 REST 接口
config/                                  Jackson 金额序列化、演示数据初始化
```

## 7. 范围与约束（有意的简化）

- 存储为单进程内存实现，重启数据重置；接口已抽象为 Repository，可替换为数据库/分布式锁实现；
- 罚息采用 30/360 单利、基数为逾期未结本金；正常利息为计划值，不随日计提；
- 未实现费用（fee）科目、提前还本指令与退款流程；超额现金一律挂账，不自动提前还本；
- 不包含任何外部服务、真实凭据或不可访问资源。

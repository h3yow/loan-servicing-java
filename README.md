# 贷款还款分摊与逾期认定（本地可运行）

一套**无外部依赖**的贷款服务核心流程实现：按还款计划计提利息/罚息、按固定顺序分摊还款、
幂等且并发安全地受理还款、进行逾期认定与账龄推进、对任何时点的科目余额提供可核对的对账，
并支持对已受理还款做**幂等、并发安全的撤销/冲正**（按事件重放安全回退到该笔还款从未发生）。

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

## 1A. 已受理还款的撤销 / 冲正

针对“客户退错款、付款被撤销”的场景，可对一笔**已成功受理的还款**发起撤销
（不管它当时是正常入账还是走了挂账）。撤销完成后，这笔还款冲抵过的各期
本金/利息/罚息、挂账余额、分期状态与账龄，都回到“**这笔还款从没发生过**”时
可核对的状态。

### 1A.1 回退方式：基于账务台账的事件重放（天然不透支）

聚合内部维护一条**只增账务台账（LedgerEntry）**：每次真正改变科目/挂账的成功动作
（客户还款、挂账冲抵）都留痕，记录动作类型、幂等键、业务日期、请求金额与逐期分摊明细。
撤销不是“做一笔反向分录去扣当前余额”，而是：

```
① 把该贷款全部科目（本金/利息/罚息已还与已计提）、挂账、罚息锚点、账龄清零（计划值不变）
② 按台账原顺序重放“除目标还款及此前已撤销动作以外”的所有账务动作：
   - 每个动作在其【原始业务日期】重新计提罚息、重新刷新逾期/账龄；
   - 客户还款：按重放后“当时的应还”重新计算分摊（目标还款消失后，后续还款的分摊自动重排）；
   - 挂账冲抵：按重放后的挂账余额重新冲抵；若原挂账来自被撤销还款，冲抵时无钱可冲，
     它当时冲掉的后期科目自动恢复未还，绝不产生负挂账。
③ 登记“该还款已被谁撤销”的因果标记，并按撤销业务日期再计提/刷新一次
```

由于每条重放动作都是“在合法的此前状态上、对真实应还的冲抵”，因此：

- **绝不出现负余额 / 透支**；科目恒等式与现金守恒在重放每一步都成立；
- 目标还款之后发生的逾期推进、后续还款、挂账冲抵（哪怕动到更靠后的期）都会被
  **安全地重新演算**，而不是只改一半——因此在本模型内撤销总能安全回退干净，
  不存在“改了一半再拒绝”的中间态；
- 目标还款曾把某期结清、撤销前区间内的罚息，会按“该期本金一直未结”**重新补提**，
  账龄按撤销日重算，结论与平行重放（只做其余动作）**一致、可复现**（见
  `ReversalTest.reverseEarlierPayment_reallocatesLaterPayment_reproducible`）。

### 1A.2 撤销结果码（彼此可区分，绝不静默改账）

| 结果码 | 语义 |
| --- | --- |
| `REVERSED` | 撤销成功，已按事件重放回退，`refundAmount` 为退回现金（原入账+原挂账） |
| `REPLAYED` | 同一 `reversalRequestId` 重复提交，返回首次撤销结果快照，**不重复退账** |
| `CONFLICT_CONCURRENT` | 同一撤销请求正在并发处理中，本次不退账 |
| `REJECTED_LOAN_NOT_FOUND` | 目标贷款不存在 |
| `REJECTED_PAYMENT_NOT_FOUND` | 该贷款上不存在此 `paymentRequestId`（未受理/未成功；消息会提示是否属于别的贷款） |
| `REJECTED_PAYMENT_NOT_REVERSIBLE` | 目标是挂账冲抵动作而非客户还款，不能按“还款”撤销 |
| `REJECTED_ALREADY_REVERSED` | 该还款此前已被另一笔撤销成功冲正（不同撤销键重复/并发撤销时得到） |
| `REJECTED_IDEMPOTENCY_MISMATCH` | 同一撤销键被复用于不同贷款/还款 |
| `REJECTED_REQUEST_INVALID` | 参数非法（撤销键/目标还款键为空、业务日期缺失等） |

所有结果都是结构化业务响应（HTTP 200 + `outcome` 字段区分），不会把内部异常
变成 500，也不会在失败时看起来像成功；任何拒绝都**不产生账务变动**。

### 1A.3 撤销的幂等与并发

- 撤销有**独立于还款的幂等键** `reversalRequestId`，指纹 = `loanId|paymentRequestId`；
- 协议与还款一致，锁顺序固定“撤销请求维度 CAS → 贷款锁”：
  同键并发只有一个候选处理者真正退账，持锁期间到达者得 `CONFLICT_CONCURRENT`，
  锁释放后到达者得 `REPLAYED`；
- “同一笔还款被**不同撤销键**并发撤销”由台账中的 `reversedBy` 标记拦截：
  恰有一笔 `REVERSED`，其余得 `REJECTED_ALREADY_REVERSED`，现金只退一次；
- 撤销成功后，原还款可被重新发起（新 requestId 的正常受理）；
  “撤销 A → 新还款 B → 撤销 B”的撤销链同样一致（重放会跳过所有已撤销动作）。

### 1A.4 因果链与可追溯

成功撤销写入 `PAYMENT_REVERSED` 事件，其 `requestId` 为撤销键，属性中带
`paymentRequestId / reversalRequestId / paymentBusinessDate / refundAmount /
撤销后各科目余额 / replayedEntryCount / basis`；重复提交写 `REVERSAL_REPLAYED`，
拒绝写 `REVERSAL_REJECTED`。从 `GET /api/loans/{loanId}/events` 可清楚看到
“哪笔还款、在什么时候、被哪笔撤销退了多少钱”。

### 1A.5 对现有语义的影响范围与兼容性

- **还款分摊 / 逾期账龄 / 还款幂等语义不变**：唯一实现差异是成功还款改为经聚合的
  `acceptPaymentEntry(...)` 落账（=原 `commitAllocation` + 必要时 `holdSuspense`），
  并在聚合台账多留一条痕；拒绝 / 重放 / 并发冲突不产生台账，行为与此前完全一致。
- 挂账冲抵（SUSPENSE_APPLIED）也会留痕，以便撤销重放；挂账冲抵本身不是“客户还款”，
  不能作为撤销目标（返回 `REJECTED_PAYMENT_NOT_REVERSIBLE`），其影响通过撤销
  关联的客户还款、在重放中自动回退。
- 回退只重算**该贷款内**科目；罚息/利息仍遵循 30/360 单利与既有计提锚点规则，
  不引入任何外部服务或新存储依赖。

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
SUSPENSE_HELD / SUSPENSE_APPLIED / PAYMENT_REVERSED / REVERSAL_REJECTED /
REVERSAL_REPLAYED`。通过 `GET /api/loans/{loanId}/events` 查询。

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
| `POST /api/loans/{loanId}/payments/{paymentRequestId}/reversal` | 撤销/冲正已受理还款（body：reversalRequestId、asOf） |
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

# 撤销上面的 req-1 还款（body 带本次撤销的幂等键）
curl -s -X POST localhost:8080/api/loans/DEMO-001/payments/req-1/reversal \
  -H 'Content-Type: application/json' \
  -d '{"reversalRequestId":"rev-1","asOf":"2026-09-23"}'

# 同一撤销键重复提交 → REPLAYED（不重复退账）；换键再撤销 → REJECTED_ALREADY_REVERSED
curl -s -X POST localhost:8080/api/loans/DEMO-001/payments/req-1/reversal \
  -H 'Content-Type: application/json' \
  -d '{"reversalRequestId":"rev-1","asOf":"2026-09-23"}'

# 事件流水中查看因果：paymentRequestId ↔ reversalRequestId
curl -s localhost:8080/api/loans/DEMO-001/events
```

---

## 5. 验证方法

### 5.1 运行全部测试

```bash
mvn test
```

共 26 个用例（均为本地、无外部依赖）：

- `PaymentAllocationTest`（3）：科目顺序、罚息优先、不足额/超额边界（纯领域）；
- `PaymentIdempotencyConcurrencyTest`（5）：
  顺序重复提交、**同 requestId 12 线程并发**、PROCESSING 占位冲突、
  **不同 requestId 20 线程并发**现金精确性、指纹篡改拒绝；每个用例后强制执行对账；
- `OverdueAgingTest`（3）：宽限期边界、M1→M2→M3→M3+ 账龄推进与同日重复计提幂等、
  部分还款后结清不翻转/不重复计收；
- `SuspenseTest`（2）：超额挂账、挂账跨期冲抵下一期并按科目顺序分摊；
- `ReversalTest`（11）：撤销/冲正专项——
  部分还款撤销且区间罚息按本金未结补提、同键重放不二次退账、换键重复撤销被拒、
  **同撤销键 12 线程并发只生效一次**、**不同撤销键并发撤销同一还款只退一次**、
  结清后账龄再推进再撤销、**挂账已冲抵到更靠后期后撤销的安全回退（无负余额）**、
  撤销首笔后后续还款重新分摊且与平行对照**可复现**、各类异常输入结果可区分、
  撤销键指纹复用拒绝、全额挂账退款、撤销→重新还款→再撤销链；
  **每个撤销场景结束都强制核对科目恒等式与现金守恒**；
- `ApplicationTests`（1）：Spring 上下文加载。

### 5.2 日志可观察性

测试日志统一打印 **输入 → 判定依据 → 关键状态变化**，例如：

```
[输入] 12 个线程用同一 requestId=REQ-CONC-1 同时还款 300
[判定依据] CAS 占位成功数=1；锁内复查 PROCESSING→CONFLICT_CONCURRENT，COMPLETED→REPLAYED
[关键状态] accepted=1, conflict=..., replayed=...
[对账结果] balanced=true, paid(P/I/N)=..., suspense=..., violations=0
[账龄快照] status=OVERDUE, bucket=M1, overdueDays=10, basis=asOf(...) > dueDate(...)+grace(3)
[撤销请求] reversalRequestId=rev-1, paymentRequestId=req-1, asOf=...
[撤销完成] refund=200.00, unpaidAfter(P/I/N)=.../.../..., status=OVERDUE
[判定依据] 同键并发 CAS 占位成功数=1；PROCESSING→CONFLICT_CONCURRENT，COMPLETED→REPLAYED
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
domain/loan/Loan.java                   贷款聚合根：计提/逾期刷新/分摊计算与提交/挂账/
                                        账务台账留痕 acceptPaymentEntry / 事件重放撤销 reversePayment
domain/loan/Installment.java            分期实体：三类科目余额、罚息锚点、状态与账龄、replayReset
domain/loan/LedgerEntry.java            只增账务动作台账条目（还款/挂账冲抵），撤销重放依据
domain/loan/{LoanStatus,InstallmentStatus,Bucket}
domain/payment/PaymentCommand(Result/Allocation/...)  还款命令、结果码、分摊方案
domain/reversal/ReversalCommand(Result/Outcome)       撤销命令、可区分结果码、撤销结果快照
domain/event/{DomainEvent,EventType}    不可变领域事件（判定依据属性）
repo/*                                   内存实现：分段锁仓储 / CAS 幂等库（还款+撤销）/ 事件流水
service/PaymentService.java             幂等+并发协议、计提→分摊→挂账主流程
service/ReversalService.java            撤销/冲正：CAS+贷款锁、可区分结果、调用聚合事件重放回退
service/OverdueService.java             逾期认定与账龄推进（单贷/日终批量）
service/ReconciliationService.java      科目与现金恒等式对账
service/LoanService.java                建档/查询
web/LoanController.java                 REST 接口（含还款撤销端点与异常→业务结果兜底）
config/                                  Jackson 金额序列化、演示数据初始化
```

## 7. 范围与约束（有意的简化）

- 存储为单进程内存实现，重启数据重置；接口已抽象为 Repository，可替换为数据库/分布式锁实现；
- 罚息采用 30/360 单利、基数为逾期未结本金；正常利息为计划值，不随日计提；
- 未实现费用（fee）科目与提前还本指令；超额现金一律挂账，不自动提前还本；
- 已支持对**成功客户还款**的撤销/冲正（事件重放回退，见第 1A 节）；挂账冲抵动作本身
  不作为独立撤销目标，其影响随关联还款撤销在重放中回退；
- 不包含任何外部服务、真实凭据或不可访问资源。

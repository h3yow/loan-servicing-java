# 贷款还款分摊与逾期认定（本地可运行）

一套**无外部依赖**的贷款服务核心流程实现：按还款计划计提利息/罚息、按固定顺序分摊还款、
幂等且并发安全地受理还款、进行逾期认定与账龄推进、对任何时点的科目余额提供可核对的对账，
并支持对已受理还款做**幂等、并发安全、可审计的撤销（冲正）**。

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
SUSPENSE_HELD / SUSPENSE_APPLIED / PAYMENT_REVERSED / PAYMENT_REVERSAL_REJECTED`。
通过 `GET /api/loans/{loanId}/events` 查询。撤销与被撤销还款的因果链通过
`PAYMENT_REVERSED.reversedPaymentRequestId` 串联（详见第 4 节）。

---

## 4. 还款撤销（冲正）链路

适用于“客户退错款 / 付款被撤销”等需要让一笔已成功受理还款**视同从未发生**的场景。
撤销只针对**已成功受理**（`ACCEPTED_FULL / ACCEPTED_PARTIAL / ACCEPTED_SUSPENSE`）的还款；
被拒绝的还款本来就没有账，不允许也不需要撤销。

### 4.1 撤销语义：确定性台账重放（不是在原账上做减法）

直接把原分摊额从 `paid*` 里减回去，在“这笔还款之后又发生过别的账务变动”时会算错：
后续还款当时在科目瀑布（罚息→利息→本金）里坐的“座位”不会重排。例如 P1 先冲了
罚息 3.50 + 利息 30 + 本金 166.50，P2 的 100 全冲本金；只把 P1 的贡献减掉后，
账面是 P100，而“P1 从未发生”的正确结果应是 罚息 3.50 + 利息 30 + 本金 66.50。

因此撤销采用**台账重放（ledger replay）**：

```
① 从事件流水还原该笔贷款全部“真实落账”的现金动作（外部还款、挂账冲抵），按发生时间排序；
② 剔除“本次被撤销还款”以及“历史上已撤销的还款”；
③ 用同一份合同计划（分期、利率、宽限期）构建全新账本，按原顺序重放保留下来的动作——
   每一步仍走 accruePenalty → refreshOverdueStatus → 固定瀑布分摊/挂账/冲抵；
④ 以撤销业务日期 asOf 补提尾部罚息、重算分期状态与账龄桶；
⑤ 现金守恒自检通过后，在贷款锁内用重放账本整体替换旧聚合，并追加 PAYMENT_REVERSED 事件。
```

由此保证：

- **结果与“这笔还款从没发生过”逐字段一致**：后续还款/挂账冲抵在瀑布中自动重新就座；
- **数学上不可能出现负余额或透支**：重放的每一步都由同一套只增不破恒等式的分摊纯函数产生，
  不是手工回退数字；原还款的挂账即使已被后续冲抵“用掉”，重放后那笔冲抵会在新的挂账池上
  自然表现为“无款可冲、自动跳过”，无需特殊拒绝；
- **现金守恒**：重放后 `Σ(paidP+paidI+paidN) + suspense` 恰好等于“仍有效的历史现金流入之和”；
  替换账本前强制自检，不平则返回 `REJECTED_NOT_REVERSIBLE`，绝不落库改一半；
- **历史流水不被改写**：重放账本不回填历史事件，只在末尾追加一条带因果链的撤销事件，
  因此“撤销之前的流水长什么样”事后仍可完整审计。

### 4.2 罚息/账龄在撤销后的口径

- 撤销本身**不抹掉**还款发生前已计提的罚息（那是该期间真实的计息结果），恢复的未结本金
  会按 `(lastPenaltyAccrualDate, asOf]` 区间在重放中重新成为计提基数；
- 撤销日重算分期状态（PENDING/PARTIAL/OVERDUE/PAID）与账龄桶（CURRENT/M1/M2/M3/M3+），
  规则与第 2 节完全一致；撤销后再次跑批仍然幂等；
- 典型效果：一笔把逾期分期结清的还款被撤销后，该分期在撤销日回到 OVERDUE/对应账龄桶，
  并带按未结本金补提的罚息（测试 `paidOffThenAged_reversalRestoresOverdueState` 覆盖）。

### 4.3 撤销结果码（彼此可区分，禁止合并处理）

| 结果码 | 语义 | 是否改账 |
| --- | --- | --- |
| `REVERSED` | 撤销成功，台账已重放为“该笔还款从未发生” | 是（一次性原子替换） |
| `REPLAYED` | 同一撤销 `requestId` 重复提交，返回首次结果快照 | 否 |
| `CONFLICT_CONCURRENT` | 同一撤销 `requestId` 的另一请求正在处理中 | 否 |
| `REJECTED_ALREADY_REVERSED` | 目标还款已被**另一个**撤销 requestId 成功撤销 | 否 |
| `REJECTED_PAYMENT_NOT_FOUND` | 贷款不存在 / 目标还款不存在 / 未成功受理 / 不属于该贷款 | 否 |
| `REJECTED_REQUEST_INVALID` | 请求本身不合法：请求体为空/空对象、必填字段（`requestId`、`targetPaymentRequestId`）缺失、为 null、空串或纯空白 | 否（不触碰任何存储） |
| `REJECTED_IDEMPOTENCY_MISMATCH` | 同一撤销 requestId 被不同请求体（别的贷款/别的目标还款）复用 | 否 |
| `REJECTED_NOT_REVERSIBLE` | 重放后现金守恒/科目恒等式自检不通过的防御性拒绝 | 否 |

注意区分三类“没撤成”：**重放**（同一个撤销请求的副本）、**并发冲突**（同一撤销请求正在处理）、
**已被撤销**（换了一个撤销请求再来），分别对应三个不同结果码；所有拒绝都只产出
`PAYMENT_REVERSAL_REJECTED` 事件与业务结果，**不抛服务端错误、不静默改账**。

### 4.3.1 非法与异常输入的统一行为（`REJECTED_REQUEST_INVALID`）

撤销接口对“请求本身不合法”的输入一律返回 `REJECTED_REQUEST_INVALID` 业务结果
（HTTP 200，与其它拒绝场景同构），**绝不返回 5xx**，也绝不会看起来像撤销成功或已处理过：

- 覆盖范围：请求体整个为空、空对象 `{}`、必填字段缺失、字段为 `null`、空串 `""`、
  纯空白 `"   "`（`requestId` / `targetPaymentRequestId` 均校验）；`asOf` 缺省时按当日处理，不算非法；
- 请求体 JSON 语法错误（无法解析）由框架层统一映射为 HTTP 400，同样不是 5xx；
- 非法请求在**进入任何存储之前**被拦截：不做 CAS 占位、不取贷款锁、不写事件流水，
  科目余额、挂账、分期状态一律不变；也不落幂等记录——同一 `requestId` 补全参数后
  重新提交会按正常请求处理（非法请求不算“已受理”，修正后重试是安全的）；
- 与其它拒绝码的区分：`REJECTED_REQUEST_INVALID` 表示“请求本身没法理解”，
  `REJECTED_PAYMENT_NOT_FOUND` 表示“请求合法但目标不存在/未成功受理”，
  `REJECTED_ALREADY_REVERSED` 表示“目标已被他单撤销”，三者不得混用。

**兼容说明**：本次入参校验只作用于“以前会直接抛 500/400 异常”的请求形状，
对合法请求的处理路径零改动——正常撤销（`REVERSED`）、同键重放（`REPLAYED`）、
并发冲突（`CONFLICT_CONCURRENT`）、指纹不符（`REJECTED_IDEMPOTENCY_MISMATCH`）、
换键重复撤销（`REJECTED_ALREADY_REVERSED`）的结果码与账务效果与之前完全一致；
分摊、逾期账龄、幂等、并发语义均不受影响，撤销后重新对账与事件流水结论保持可复现。

### 4.4 撤销的幂等与并发协议

与还款受理完全同构（锁顺序固定为“请求维度 CAS → 贷款维度分段锁”）：

```
① 撤销 requestId 维度 CAS 登记 PROCESSING（ConcurrentMap.putIfAbsent）；
② 获取与还款共用的同一把贷款锁（撤销与还款/挂账冲抵天然互斥）；
③ 锁内复查：目标还款是否成功受理 → 是否已被他单撤销 → 重放台账 → 守恒自检；
④ 锁内整体替换聚合、固化撤销 COMPLETED 记录、追加 PAYMENT_REVERSED 事件，再释放锁。
```

- 同一撤销 requestId 并发：只有 CAS 赢家可能生效，其余在其持锁期间得到 `CONFLICT_CONCURRENT`、
  释放锁后得到 `REPLAYED`，**同一笔还款被同时发起多次撤销只真正生效一次**（12 线程并发用例覆盖）；
- 撤销 requestId 与原还款 requestId 相互独立；原还款即便已撤销，用原键重放仍返回还款侧 `REPLAYED`，
  不会把钱“还回来”；
- 多次“还→撤→还→撤”往返时，每次重放都会剔除全部历史已撤销还款，旧账不会被“复活”。

### 4.5 因果链与事件

撤销成功只追加一条 `PAYMENT_REVERSED`（属性含 `reversedPaymentRequestId`、
`reversedRequestedAmount`、撤销后三科未还/挂账快照、重放操作数）：

```
PAYMENT_APPLIED(requestId=P1)  ──撤销──▶  PAYMENT_REVERSED(requestId=R1, reversedPaymentRequestId=P1)
```

从 `GET /api/loans/{loanId}/events` 可双向核对“哪笔还款、在什么时候、被哪个撤销请求、
以什么业务日期冲正”；拒绝事件为 `PAYMENT_REVERSAL_REJECTED`（携带结果码与原因）。

---

## 5. HTTP 接口

启动后访问 `http://localhost:8080`，应用会自动创建演示贷款 `DEMO-001`
（3 期，首期到期日相对启动日已过 10 天，宽限期 3 天，罚息年化 18%）。

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/loans` | 建档（loanId、penaltyRateBps、graceDays、schedule） |
| `GET  /api/loans/{loanId}` | 贷款全景（各科目计划/已还/未还、状态、账龄、挂账） |
| `POST /api/loans/{loanId}/payments` | 还款受理（body：requestId、amount、asOf） |
| `POST /api/loans/{loanId}/assess` | 逾期认定/账龄推进（body：asOf），先计提后刷新 |
| `POST /api/loans/{loanId}/suspense/apply?requestId=&asOf=` | 挂账冲抵 |
| `POST /api/loans/{loanId}/payments/reversal` | 撤销已受理还款（body：requestId、targetPaymentRequestId、asOf） |
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

# 撤销上面 req-2 那笔超额还款（REVERSED；重复提交返回 REPLAYED，换撤销键再来返回 REJECTED_ALREADY_REVERSED）
curl -s -X POST localhost:8080/api/loans/DEMO-001/payments/reversal \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"rev-1","targetPaymentRequestId":"req-2","asOf":"2026-09-23"}'

# 非法输入：缺 targetPaymentRequestId / 空对象 / 空请求体 → REJECTED_REQUEST_INVALID（HTTP 200，非 5xx，账不变）
curl -s -X POST localhost:8080/api/loans/DEMO-001/payments/reversal \
  -H 'Content-Type: application/json' -d '{"requestId":"rev-2"}'
curl -s -X POST localhost:8080/api/loans/DEMO-001/payments/reversal \
  -H 'Content-Type: application/json' -d '{}'
```

---

## 6. 验证方法

### 6.1 运行全部测试

```bash
mvn test
```

共 34 个用例（均为本地、无外部依赖）：

- `PaymentAllocationTest`（3）：科目顺序、罚息优先、不足额/超额边界（纯领域）；
- `PaymentIdempotencyConcurrencyTest`（5）：
  顺序重复提交、**同 requestId 12 线程并发**、PROCESSING 占位冲突、
  **不同 requestId 20 线程并发**现金精确性、指纹篡改拒绝；每个用例后强制执行对账；
- `OverdueAgingTest`（3）：宽限期边界、M1→M2→M3→M3+ 账龄推进与同日重复计提幂等、
  部分还款后结清不翻转/不重复计收；
- `SuspenseTest`（2）：超额挂账、挂账跨期冲抵下一期并按科目顺序分摊；
- `PaymentReversalTest`（7）：基础红冲、撤销重放、换键重复撤销、
  **同一撤销 requestId 12 线程并发只生效一次**、贷款/还款不存在与未成功受理等可区分拒绝、
  撤销指纹篡改、撤后重放原还款不复活与多轮“还→撤→还→撤”；
- `ReversalInvalidInputTest`（7，HTTP 边界）：空请求体/空对象/缺字段、
  字段为 null/空串/纯空白、JSON 语法错误（400 而非 5xx）、贷款不存在/目标还款不存在/
  未成功受理、换键重复撤销、合法撤销不被入参校验误拒、
  **不同撤销 requestId 12 线程并发撤销同一笔还款只生效一次**（其余 `REJECTED_ALREADY_REVERSED`）；
  每个场景结束都校验科目余额/挂账不变、对账恒等式与现金守恒；
- `PaymentReversalComplexTest`（5）：超额挂账退回、**原挂账已被后续冲抵使用后仍安全回退**、
  **后续还款后撤销在瀑布中重新就座**、结清并推进账龄后撤销恢复逾期状态与补提罚息、
  两笔挂账+跨期冲抵后撤销其一；每个场景都逐字段比对“该还款从未发生”的影子台账，
  并校验科目恒等式与现金守恒；
- `EndToEndFlowTest`（1）：逾期→还款→重放→挂账→冲抵→账龄→对账→事件追溯；
- `ApplicationTests`（1）：Spring 上下文加载。

### 6.2 日志可观察性

测试日志统一打印 **输入 → 判定依据 → 关键状态变化**，例如：

```
[输入] 12 个线程用同一 requestId=REV-C-REV 并发撤销同一笔还款
[判定依据] CAS 占位成功数=1；赢家持锁期间→CONFLICT_CONCURRENT，释放锁后→REPLAYED
[撤销重放] target=REV-C-PAY, excluded=[REV-C-PAY], replayOps=0
[关键状态] reversed=1, conflict=..., replayed=...；撤销后 unpaid(P/I/N)=.../.../...
[对账结果] balanced=true, paid(P/I/N)=..., suspense=..., violations=0
[账龄快照] status=OVERDUE, bucket=M1, overdueDays=30, basis=asOf(...) > dueDate(...)+grace(3)
```

### 6.3 手工端到端

```bash
mvn spring-boot:run
# 另开终端使用第 5 节 curl；重启后内存数据与 DEMO-001 会重新初始化
```

---

## 7. 代码结构

```
domain/money/Money.java                 金额值对象（分存储，比例分摊 prorate）
domain/loan/Loan.java                   贷款聚合根：计提/逾期刷新/分摊计算与提交/挂账/撤销台账重放
domain/loan/Installment.java            分期实体：三类科目余额、罚息锚点、状态与账龄、重放归零拷贝
domain/loan/LedgerOperation.java        台账重放操作（外部还款 / 挂账冲抵，sealed）
domain/loan/{LoanStatus,InstallmentStatus,Bucket}
domain/payment/PaymentCommand(Result/Allocation/...)  还款命令、结果码、分摊方案
domain/reversal/*                       撤销命令/结果/结果码（REVERSED/REPLAYED/各 REJECTED_*）
domain/event/{DomainEvent,EventType}    不可变领域事件（判定依据属性，含 PAYMENT_REVERSED 因果链）
repo/*                                   内存实现：分段锁仓储 / CAS 还款幂等库 / CAS 撤销幂等库 / 事件流水
service/PaymentService.java             幂等+并发协议、计提→分摊→挂账主流程
service/ReversalService.java            撤销 CAS+贷款锁协议、历史操作还原、台账重放与现金守恒自检
service/OverdueService.java             逾期认定与账龄推进（单贷/日终批量）
service/ReconciliationService.java      科目与现金恒等式对账
service/LoanService.java                建档/查询
web/LoanController.java                 REST 接口（含 /payments/reversal）
web/dto/*Reversal*                      撤销请求/结果视图
config/                                  Jackson 金额序列化、演示数据初始化
```

## 8. 范围与约束（有意的简化）

- 存储为单进程内存实现，重启数据重置；接口已抽象为 Repository，可替换为数据库/分布式锁实现；
- 罚息采用 30/360 单利、基数为逾期未结本金；正常利息为计划值，不随日计提；
- 撤销以“重放仍有效的历史现金动作”恢复台账：历史流水只追加不改写，重放依赖流水完整
  （内存实现下成立；替换持久化存储时需保证事件不丢失）；撤销不产生真实资金出金指令，
  只回退账务台账，实际退款渠道不在本服务范围内；
- 未实现费用（fee）科目、提前还本指令；超额现金一律挂账，不自动提前还本；
- 不包含任何外部服务、真实凭据或不可访问资源。

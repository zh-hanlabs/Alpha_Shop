# ShopAgent · W3-4 交易安全开发任务清单

> 目标版本：两周（10 个工作日）：交易工具 + 幂等 + 分布式锁
> 原则：幂等+锁是简历核心，功能可砍它不可砍（主计划 §9）
> 配套：主计划 §4（设计依据）· AGENTS.md（开发规则）· docs/business-logic.md §6（嵌入预演）

---

## 一、两周验收标准（Definition of Done）

两周结束时，能完成这条演示链路：

> 「帮我下一个充电宝」→ Agent 先报商品/价格向用户确认 → 调 placeOrder → 「已下单，订单号 X，待付款」
> → 同一句话再发一遍 → 「您已下单，订单号 X」（返回首次结果，**不产生第二个订单**）
> → 「把订单 10001 退了」→ 确认后调 refundOrder → 「退款已受理」
> → 打开 h2-console：trade_audit_log 里每笔交易（谁/何时/幂等键/结果）可查。

硬性指标：

- [ ] 对话可完成：下单（含二次确认）→ 查询 → 退款 / 取消，全链路
- [ ] 同一句话重发两遍：只产生一个订单，第二次返回首次结果（幂等语义 D3）
- [ ] 混沌测试：同键并发 ×10 → 1 单；异键并发 ×50 → 零超卖、库存精确（截图存证）
- [ ] `mvn test` 全绿（service/ + infra/ 单测覆盖，不依赖外部环境）
- [ ] Redis 停机时交易工具 fail-closed（「交易暂不可用」），查询工具不受影响
- [ ] README「交易安全设计」章节：闸序流程图 + 混沌结果 + 追问预演表

---

## 二、设计定稿（2026-10-03 对齐，开发中勿推翻）

> 三处歧义的裁决记录：主计划 §4.2 的幂等键含 orderId，但 placeOrder 执行时订单号还不存在；
> W1D10 预研笔记写的是 paramsHash。本节为最终口径，改动需先改这里。

### 2.1 幂等组件（W3D3）

**形态**：`infra/idempotent/IdempotentExecutor`，显式调用，不用注解 AOP。
理由：与 W1「data 前插闸」埋点故事一致；`@Tool` 方法由 Spring AI 反射调用，AOP 代理有兼容风险；纯单测友好。

**幂等键**（`IdempotentKeys` 静态构造，键内容做 sha256）：

| 动作 | 键组成 | 说明 |
|---|---|---|
| placeOrder | userId + action + productId + quantity + **conversationId + instructionDigest** | 下单时无订单号；会话+指令摘要区分「重放」与「新意图」 |
| refund / cancel | userId + action + orderNo | 同一订单同一动作只有一个结果（跨会话也拦） |

- `instructionDigest` = sha256(用户消息原文)，由 controller 注入 toolContext（`ToolContextKeys` 新增 `CONVERSATION_ID`、`INSTRUCTION_DIGEST`）
- **为什么 placeOrder 要带会话+指令摘要**：同会话同句重发 = 重放（拦截）；换句话（「再买一个」）或换会话 = 新意图（放行）。纯参数摘要会把「24h 内正常复购同商品同数量」误判为重复。
- **已知局限**（面试讲改进方向）：同会话隔天一字不差重说同一句 → 仍被拦；误判窗口 = TTL。生产方案是客户端幂等令牌（requestId），本项目用指令摘要近似，换取前端零改动。

**Redis 结构**（Redisson RBucket）：

- `idempotent:mark:{key}` —— SETNX 标记「已受理」，TTL 86400s
- `idempotent:result:{key}` —— 首次 ToolResult（JSON），TTL 与 mark 同步

**四态语义**：

| 状态 | 表现 |
|---|---|
| result 命中 | 返回首次 ToolResult（**不报错**，防 LLM 重试死循环，ADR D3） |
| mark 命中无 result | 在途或前次崩溃 → 「操作处理中，请稍后再试」（宁可拒绝不可重复） |
| 业务失败（库存不足等） | 也是正常 ToolResult，照存 result；同句重放返回同结果 |
| 运行时异常 | 删 mark + 返回 error ToolResult，下次可重试（mark 只为「已开始」负责） |

### 2.2 锁组件（W3D4）

**形态**：`infra/lock/LockExecutor`，封装 RedissonClient。

| 项 | 定稿 |
|---|---|
| 锁键 | placeOrder：`lock:trade:{userId}:placeOrder:{productId}`；refund/cancel：`lock:trade:{userId}:order:{orderNo}` |
| API | `RLock.tryLock(waitTime=3s)`，**不传 leaseTime** → 看门狗续期；`finally` 解锁 |
| 失败话术 | 「操作处理中，请稍后再试」（不排队堆积） |
| 粒度理由 | 订单号不存在时以商品维度代位；同用户同商品并发串行化，跨商品并行（ADR D4 用户+订单级） |

### 2.3 闸序（tools/ 层编排，每工具两行）

```java
lockExecutor.withLock(lockKey, () ->
    idempotentExecutor.execute(idemKey, () -> tradeService.xxx(userId, ...)));
```

```
抢锁(3s) ─失败→ 「操作处理中」
   └成功→ 锁内：result 快查 ─命中→ 返回首次结果
              └miss→ mark SETNX ─失败→ 「操作处理中」(在途/崩溃残留)
                         └成功→ 业务(@Transactional) → 写 result(TTL 24h) → finally 解锁
```

**双保险（面试必背）**：锁防并发（双写窗口），幂等防重放（锁释放后的重试）。
只锁不幂等 = 重试穿透；只幂等不锁 = SETNX 检查窗口内双写。

> 重放会多付一次抢锁开销（本地 Redis 可忽略）。若 W3D5 第三个工具接入后编排三处重复，
> 抽 `TradeGuard` 并把 result 快查提到锁外（三处规则，§AGENTS.md 5）。

### 2.4 新增依赖报备（对照主计划 §1 禁止清单）

| 依赖 | 理由 | 禁止清单核对 |
|---|---|---|
| `org.redisson:redisson-spring-boot-starter`（当前 GA） | RLock 看门狗 + RBucket SETNX，一个客户端覆盖幂等+锁 | 非微服务 / 非编排框架 / 非 A2A / 非前端工具链 / 非独立 MQ ✓；主计划 §1「Redis + Redisson（W3 起）」预批 |

无其他新增。**不引** spring-boot-starter-data-redis（W5 缓存需要时再议）。

---

## 三、前置准备（W3D0，半天）

| # | 事项 | 完成标志 |
|---|---|---|
| P1 | Docker Desktop 启动，跑 `redis:7-alpine` 容器（名 shopagent-redis，端口 6379） | `docker exec shopagent-redis redis-cli ping` → PONG |
| P2 | application.yml 加 Redisson 单机配置 | 应用带 Redis 启动无报错 |
| P3 | 验证 Redisson 启动连接行为：Redis 未起时应用是否拒绝启动 | 若是 → fail-closed 语义写进 README 启动前置 |
| P4 | README 启动步骤补 Redis；`.env.example` 无新增（本地无密码） | 生人可复现 |

---

## 四、逐日任务（W3）

### W3D1-2：placeOrder 下单工具（不含幂等锁，D3/D4 回接）

| 任务 | 内容 | 完成标志 |
|---|---|---|
| T1.1 交易 Service | `service/TradeService.place(userId, productId, quantity)`：查商品 → 条件扣库存 → 生成订单号 → 写 orders + order_item（价格快照），`@Transactional` | 单测过 |
| T1.2 防超卖 | 库存扣减用条件更新：`UPDATE product SET stock = stock - N WHERE id = ? AND stock >= N`，影响行数 0 → 「库存不足」（W4 混沌 ×50 的验证点） | 并发下无负库存 |
| T1.3 订单号生成 | 时间戳 + 随机，≤20 位纯数字（配合既有白名单 `\d{1,20}`）；uk_order_no 唯一索引兜底 | 无冲突 |
| T1.4 交易工具 | `tools/trade/PlaceOrderTool.placeOrder(productId, quantity, toolContext)`：白名单校验（productId `\d{1,10}`、quantity 1-99）→ 取身份 → ToolEvents.publish → 编排 Service；description 写明「productId 必须来自 searchProduct 结果，不得猜测；调用前必须先向用户确认商品、数量、总价」 | 模型能正确决策 |
| T1.5 Prompt 改造 | System Prompt：移除「交易功能正在升级中」统一话术 → 下单/退款前先二次确认；改价仍拒绝；红线保留 | 越界测试回归通过 |
| T1.6 单测 | 正常下单 / 库存不足 / 商品不存在 / 参数越界 | mvn test 绿 |

> 二次确认 = Prompt 层（体验），防重复 = 幂等+锁（安全边界）——与 W1D8「两层安全」同构，面试点。
> 订单初始状态 = 待付款（与 mock 数据 10003 一致），W3 不做支付工具（超范围）。

### W3D3：infra/idempotent/ 幂等组件 + placeOrder 接入

| 任务 | 内容 | 完成标志 |
|---|---|---|
| T3.1 依赖落地 | redisson-spring-boot-starter（§2.4 已报备）+ yml 配置 | 启动无报错 |
| T3.2 组件 | `IdempotentExecutor.execute(key, Supplier<ToolResult>)`（§2.1 四态语义）+ `IdempotentKeys` + 常量（TTL 86400、前缀） | 单测过 |
| T3.3 上下文注入 | controller 把 conversationId、instructionDigest 注入 toolContext | ToolContextKeys 扩展 |
| T3.4 回接 | placeOrder 套幂等（D4 再套锁） | — |
| T3.5 单测 | mock RedissonClient：首次执行 / 重放返首次 / mark 在途「处理中」/ 异常删 mark 可重试 | mvn test 绿 |
| T3.6 冒烟 | 真 Redis：同一句话发两遍 → 第二次返回首次订单号，h2 只多一行订单 | 手工验证 |

### W3D4：infra/lock/ Redisson 锁 + 完整闸序

| 任务 | 内容 | 完成标志 |
|---|---|---|
| T4.1 组件 | `LockExecutor.withLock(lockKey, Supplier<ToolResult>)`（§2.2）| 单测过 |
| T4.2 回接 | placeOrder 套锁 → §2.3 完整闸序 | — |
| T4.3 单测 | mock RLock：抢锁失败话术 / 成功执行 / finally 解锁防泄漏 | mvn test 绿 |
| T4.4 冒烟 | 并发脚本同请求 ×10 → h2 只 1 单 | 手工验证 |

### W3D5：退款 / 取消工具（生而带幂等+锁）

| 任务 | 内容 | 完成标志 |
|---|---|---|
| T5.1 Service | `TradeService.refund(userId, orderNo)` / `cancel(userId, orderNo)`：归属校验（userId+orderNo 双条件，他人单与不存在同话术，复用 W1D8 口径）→ 状态机迁移 → 还库存（条件更新 +N） | 单测过 |
| T5.2 状态机 | refund：已发货/已签收 → 已退款；退款中 → 「已在退款流程中」；待付款 → 引导取消。cancel：待付款 → 已取消；其余 → 明确提示当前状态不可办 | 单测覆盖全迁移 |
| T5.3 工具 | `tools/trade/RefundOrderTool` / `CancelOrderTool`（refundOrder / cancelOrder 各自 @Tool，description 写明确认前置与适用状态） | 模型能正确决策 |
| T5.4 接入 | 幂等+锁接入；三处编排重复则抽 TradeGuard | — |
| T5.5 Prompt | 退款/取消二次确认话术 | 冒烟过 |
| T5.6 单测+冒烟 | 状态机各迁移 / 越权 / 重放；全链路：下单→退款→重复退款→越权退款 | mvn test 绿 + 冒烟清单过 |

---

## 五、逐日任务（W4）

### W4D1-2：混沌测试（截图存 docs/，面试核心证据）

| # | 场景 | 预期 |
|---|---|---|
| C1 | 同键重放 ×10 并发（同 conversationId + 同消息直发交易路径） | 只 1 单；其余返回首次结果或「处理中」 |
| C2 | 异键并发 ×50（同用户同商品、不同消息） | 50 单全部落库、库存精确扣减、零超卖 |
| C3 | 退款重放：退款成功后同句重发 | 返回首次退款结果，无二次退款、无二次还库存 |
| C4 | Redis 停机（docker stop） | 交易工具「交易暂不可用」，查询链路不受影响 |

- 执行方式绕过 LLM 保证确定性：dev-only 直连端点或 @SpringBootTest 集成测试，W4D1 开工时定（默认倾向 dev 端点 + PowerShell 并发脚本，W7 JMeter 再做正式压测）
- 完成标志：C1-C4 全过 + 截图存证 + 结果数字记入 README（简历素材）

### W4D3：交易审计日志

- 新表 `trade_audit_log`（id, user_id, action, order_no, idempotent_key, result_code, result_msg, created_at + user/time 索引）
- 锁内随 result 同步写入（谁、何时、幂等键、结果）；与 ToolEventListener 旁路埋点同构
- 完成标志：一笔交易后 SQL 能查到完整记录，重放场景两条日志同幂等键

### W4D4：README「交易安全设计」章节

闸序流程图（§2.3 同款）+ 混沌测试结果表 + 面试三层追问预演（主计划 §4.5）+ 已知局限与改进方向（§2.1 指令摘要 vs 客户端令牌）。

### W4D5：Buffer + commit 整理

补拖期任务；commit 按 W3D1→W4D5 校对（一个任务一个 commit）。

---

## 六、测试口径

| 层 | 方式 | 环境 |
|---|---|---|
| infra/ + service/ | 单测，mock RedissonClient/RLock，**不依赖外部环境** | `mvn test` 全绿 |
| tools/ | 冒烟清单（真模型 + 真 Redis + 真 H2） | 手工，连跑稳定 |
| 混沌（W4） | 并发脚本 + 真 Redis | 截图存证 |

## 七、风险清单

| 风险 | 应对 |
|---|---|
| Redisson 版本与 Boot 3.5 / JDK17 兼容 | 落地时以当前 GA 为准，启动冒烟验证 |
| Redisson 默认启动即连接，Redis 未起 → 应用起不来 | P3 专项验证；fail-closed 写进 README 前置（交易安全语义：Redis 挂了宁可不做交易） |
| H2 并发条件更新语义 | MVStore 行锁支持 `stock >= N` 条件更新，C2 场景专项验证 |
| 幂等结果 JSON 往返后 data 变 LinkedHashMap | 对模型无影响（再序列化同 JSON）；单测断言 JSON 等价而非类型等价 |

---

*设计定稿于 2026-10-03 会话（幂等键/组件形态/Redis 环境三项用户确认）。改动先改本文件 §2，再改代码。*

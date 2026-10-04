# ShopAgent · W6 稳定性三件套开发任务清单

> 目标版本：一周（D0-D5 六个工作日）：LLM 限流熔断降级 + Redis 会话记忆 + 结构化观测
> 原则：W6 是「稳定性 + 分布式化」叙事轴——限流/熔断护 LLM API，记忆 Redis 化让接入层无状态；**不破坏 W3 交易安全与 W5 RAG/缓存的既有语义**
> 配套：主计划 §6（任务依据，2026-10-04 已随本清单修订两处）· AGENTS.md（开发规则）· shopagent-w3w4-tasks.md §2（幂等/锁口径勿动）· shopagent-w5-tasks.md §2（缓存/分级降级矩阵口径，本清单只补格不改判）

---

## 一、验收标准（Definition of Done）

一周结束时，能完成这条演示链路：

> 并发突刺：同用户 10 连发提问 → 配额内 2 次正常回答，其余收到「当前咨询人数较多，请稍后再试」；换一用户同时提问 → 互不挤占（用户桶独立）。
> 停 LLM（错 base-url 起应用）→ 连续失败后熔断 OPEN → 提问得到规则回复「高峰期，简单问题我直接答…」（不再是报错刷屏）；下单/退款请求降级期只引导绝不执行。
> 等 20s 半开探测（仍故障）→ 回 OPEN 继续降级；dev 端点 reset（模拟 API 恢复时刻）→ 恢复正常回答。
> 重启应用（正确 base-url）→ 同 conversationId 继续对话，上一轮内容还记得（对照 W1 重启即失忆）。
> Redis 停机 → 聊天空历史照常回答 + 限流放行（fail-open），交易仍 fail-closed（C4 回归，分级降级矩阵补格）。
> dev 端点看结构化决策日志：正常/限流/降级三类轮次的耗时、token 消耗、工具调用清单。

硬性指标：

- [ ] 限流：同用户并发突刺超出配额部分被拒（/api/chat 返回 429；/api/chat/stream 返回 SSE error 话术+done），双用户桶独立互不挤占——PS5 并发脚本证据 `docs/resilience/`
- [ ] 限流参数实测定稿回填（用户桶/全局桶 rate 与依据，见 §2.1）
- [ ] 熔断：LLM 故障注入 → OPEN → 规则回复（非报错）；半开探测失败回 OPEN；恢复后闭合——故障注入证据 `docs/resilience/`
- [ ] 降级：意图分类 ≥5 类（订单/物流/商品/交易/其他）+ 交易类降级绝不规则执行只引导（安全红线）
- [ ] 记忆：重启应用同会话记忆连续（Redis 键 TTL 7 天直读演示）；W1 记忆冒烟清单复跑行为等价（窗口语义/跨会话隔离）
- [ ] Redis 停机：chat 记忆 fail-open 空历史照常、限流 fail-open 放行、交易仍 fail-closed——分级降级矩阵补格实测
- [ ] 观测：OK / RATE_LIMITED / DEGRADED 三类轮次结构化记录齐全（耗时/token/工具清单），dev 端点可查
- [ ] token 用量接线实测定稿（DeepSeek 流式 usage 可得则真值；不可得则 N/A + 字符数估算口径回填）
- [ ] `mvn test` 全绿；混沌 C1-C5 回归 PASS（W6 动了 chat 链路与 pom，必须回归）
- [ ] README 新增「稳定性设计」章节（三件套分工表 / 双层限流 / 降级矩阵补格 / 面试三层追问预演 / 已知局限）

---

## 二、设计定稿（2026-10-04 用户确认冻结：§2.1 Redisson 双层 / §2.2 纯话术 + resilience4j 库；开发中改动需先改这里）

> 裁决记录：主计划 §6 原文「Resilience4j RateLimiter 令牌桶」经事实核查修订为 Redisson RRateLimiter 双层——Resilience4j RateLimiter 是纯进程内实现，多实例下单用户桶配额 ×N 放大，与 W6「接入层无状态扩容」主题自相矛盾；主计划 §6 已随本清单同步修订。

### 2.1 双层限流【已定稿：Redisson RRateLimiter，分布式令牌桶】

**结构（`infra/resilience/RateLimitGuard`，包位置为主计划 §2 预留的 W6 resilience/）**：

- **用户桶** `rlimit:chat:user:{userId}`：RateType.OVERALL，每用户独立桶对象（注意：PER_CLIENT 是按 Redisson 客户端实例计数，不是按终端用户——常见误读，D0 已实证区分：冒烟 T6 两客户端 PER_CLIENT 各得独立 2 配额，内部键带 clientId 后缀）
- **全局桶** `rlimit:chat:global`：RateType.OVERALL，全实例共享，护 DeepSeek 账号级配额
- **检查顺序：先用户桶后全局桶**——被用户桶拒绝的请求不消耗全局配额（单用户刷子不该挤占全局限额）；两层都 `tryAcquire()` 即时返回，不排队堆积（与 W3 拿锁失败即返回同哲学）
- **挂点：ChatController 两端点入口前置闸**——/api/chat 返回 HTTP 429；/api/chat/stream 返回 SSE error 事件「当前咨询人数较多，请稍后再试～」+ done。被限流直接短路不进图（零记忆读、零 LLM 调用）。dev 端点（chaos/cache/obs/resilience）与 embedding 调用（DashScope 另一配额，知识检索已 fail-open 且量小）不挂限流
- **参数【D1 实测定稿】**：用户桶 rate=2/1s（正常聊天远低于配额，突刺演示 10 连发 2 过 8 拒，冒烟 A/B 精确命中）；全局桶 rate=10/1s（演示节奏 + W7 压测余量，冒烟 C：12 新用户齐发 10 过 2 拒，压测时它就是 API 的护盾）
- **桶键 TTL【D0 实测定稿】**：四参签名 = (type, rate, interval, ttl)，TTL 连内部键 `{key}:value`/`{key}:permits` 一起覆盖（冒烟 T4/T5）——**用户桶用四参 TTL=1h**（低频自动回收；到期=整桶重置，1s 窗口下无安全影响）；**全局桶三参常驻**（T3：三参无 TTL 永续，单键可接受）。Guard 调用序定稿：**每次请求先 trySetRate（幂等）再 tryAcquire**——T7 实证无配置桶 tryAcquire 直接抛 `RedisException("RateLimiter is not initialized")`，先 trySetRate 顺带覆盖 TTL 到期后的重建。运维坑：deleteByPattern/scan 模式须含前导 `*` 才能扫到内部键（花括号 hash-tag 开头）；三参桶（无 TTL）主键+内部键会永久堆积
- **Redis 故障 fail-open**：限流器是保护器不是正确性来源——Redis 不可用时放行聊天（与缓存/知识同级），log warn 留痕
- **已知局限（README 记录）**：userId 由调用方直传无登录态，换 userId 可绕过用户桶（真解 W8+ 登录态注入），全局桶兜底

### 2.2 熔断与降级【已定稿：resilience4j-circuitbreaker + 纯话术规则回复】

**熔断（`infra/resilience/LlmCircuitBreaker`，programmatic 封装，参数 yml 化）**：

- **挂点：ShopAgentGraph chat 节点**，包住 `chatClient...stream()...blockLast()` 整段——token 流照常从 Sinks.Many 旁路发出，熔断器只统计整次 LLM 调用成败；OPEN 时 `CallNotPermittedException` 短路，不发起 LLM 调用直接走降级
- **失败判定**：LLM 调用抛异常（超时/连接拒绝/5xx/流中断）计 failure；工具失败不算——工具内部消化不外抛（W1 铁律），到不了这层；慢调用阈值不开（保守起步，D3 冒烟后可回填）
- **参数起点（D3 实测定稿回填）**：slidingWindowSize=10（次数滑窗）/ failureRateThreshold=50% / minimumNumberOfCalls=5（不满 5 次不评估，防启动期偶发即开）/ waitDurationInOpenState=20s（demo 节奏，生产 30-60s）/ permittedNumberOfCallsInHalfOpenState=3
- **熔断器单实例全局一个**（name=llmChat）：护的是同一个 DeepSeek API，所有会话共享命运
- **为什么熔断可以进程内而限流要分布式（面试核心点）**：熔断器是实例自保——各实例独立探测独立降级，半开探测流量有限（3 次/实例）可控；限流配额是共享资源语义（账号配额/用户公平性），必须全局一致，状态放 Redis 换一致性

**降级（`infra/resilience/RuleFallbackService`，纯话术——用户已裁，查询工具直答不做）**：

- **意图分类**：关键词规则 ≥5 类（订单/物流/商品/交易/其他），命中取话术模板，未命中走兜底话术
- **安全红线**：交易类意图（下单/退款/取消）降级期**绝不规则执行**——交易的二次确认是 Prompt 行为链路，没有 LLM 就没有确认链路；只引导「高峰期暂不能办理交易，稍后再试」
- **SSE 体感**：降级回复走 answer token 流照发（前端零改动，打字机照常）；persistMemory 照跑（降级轮也进记忆，LLM 恢复后知道说过什么）——用户体感 > 系统正确性（主计划 §6 面试深挖点原文）
- **演示控制**：DevResilienceController（@Profile("dev")）：GET breaker 状态 / POST reset / force-open——真实场景 API 恢复后半开探测自动闭合，demo 用 reset 端点模拟「API 已恢复」时刻（README 说明口径）
- **故障注入方式（冒烟）**：`DEEPSEEK_BASE_URL=http://127.0.0.1:9` 起应用 → 全部 LLM 调用连接拒绝 → 真实失败率累积触发 OPEN（不 mock，走真实链路）

### 2.3 会话记忆 Redis 化（技术路线已预核实证，决策 D5 兑现）

- **扩展点已定**：实现 `ChatMemoryRepository`（javap 实证 Spring AI 1.1.2 四方法接口：findConversationIds / findByConversationId / saveAll / deleteByConversationId）——`MessageWindowChatMemory.builder().chatMemoryRepository(...)` 换装，**窗口语义（默认 20 条）零改动行为等价**。比主计划原文「自定义 ChatMemory 实现」更贴框架扩展点，主计划已同步修订
- **结构**：Redis hash `chat:memory:{conversationId}`，field=序号，value=Message JSON；**TTL 7 天写时刷新**（每次 saveAll 重置 EXPIRE——活跃会话永续，沉默会话 7 天自然过期）；saveAll 语义=整窗替换，与 hash 全量重写天然契合
- **序列化**：只存 UserMessage/AssistantMessage 两态（persistMemory 现状只写这两类，无 ToolResponseMessage 多态坑）；D0 排查 Spring AI 自带 codec（JdbcChatMemoryRepository 的 payload 序列化）可复用则复用，否则自研 type-tag 两态 JSON——**D0 结论：不可复用→自研 type-tag 两态 JSON**（jdbc 仓库 1.1.2 无独立 codec 类，消息↔行转换内联且按 type+content 列重建、非 JSON payload；spring-ai-model jar 无任何 codec/序列化类，证据 docs/resilience/w6d0-t03-fact-checks.md §5）
- **换装点**：`ChatClientConfig.chatMemory()` bean 一处改（W5D4 代码注释预留位「W6 换 Redis 实现，决策 D5」）
- **fail-open（分级降级矩阵新格）**：读失败 → 空历史照常聊（记忆是体验不是正确性）；写失败 → 吞掉 log warn。与交易 fail-closed（W3）形成对照：交易错一笔是真金白银，记忆丢一轮是体验问题
- **演示价值**：重启应用同会话连续 = 「接入层无状态」实证（对照 W1 重启失忆）

### 2.4 观测口径（结构化决策日志，W7 压测数据源）

- **轮级指标（`infra/obs/TurnMetricsRecorder`）**：conversationId / userId / **outcome（OK / RATE_LIMITED / DEGRADED / ERROR）** / totalMs / llmMs / 首 token 延迟 / promptTokens / completionTokens / 工具调用列表与次数
- **接线点**：token 用量 = `OpenAiChatOptions.streamUsage`（字段已 javap 实证存在，D4 实测 DeepSeek 流式是否真返回 usage；不可得 → N/A + 字符数估算口径回填，面试讲清口径比硬凑数字好）；工具计数 = 包装 ToolEventListener（旁路与 W1 ToolEvents 同构，tools/ 零改动）；限流轮在 controller 记、图内轮在 chatStream 收尾记——观测是横切，跨层记账
- **落点**：单行 JSON 日志（Jackson 已在 classpath）+ 环形缓冲（最近 100 轮）+ DevObsController（@Profile("dev")）GET /api/dev/obs/turns 与 /api/dev/obs/stats（轮数/平均耗时/token 合计/工具调用分布）——W7 JMeter 从日志文件取数
- **砍单线**：不上 Micrometer/Prometheus/Grafana（主计划 §6 原文就是「决策日志结构化」），不做全链路 trace

---

## 三、依赖报备（对照主计划 §1 禁止清单）

| 依赖 | 版本 | 用途 | 合规依据 |
|---|---|---|---|
| `io.github.resilience4j:resilience4j-circuitbreaker` | 2.4.0（D0 校验中央仓实存最新稳定 2.x，已实落 pom；dependency:tree 确认仅 core+circuitbreaker 两个 jar，无传递拖带） | 失败率熔断 + 半开探测 | 主计划 §6 预批 Resilience4j；**仅熔断件，不带 spring-cloud-starter-circuitbreaker-resilience4j**（避免拖 Spring Cloud 全家桶，单体不引入云配置） |
| （限流零新增）Redisson RRateLimiter | 已有 3.52.0 | 分布式令牌桶 | §1「Redis + Redisson W3 起」既有依赖的既有能力，无越界；javap 实证 API 可用且 extends RExpirable |

---

## 四、逐日任务

### D0：设计定稿冻结 + 事实核查（半天-1 天）

- [x] T0.1 三项裁决冻结（§2.1 Redisson 双层 / §2.2 纯话术 + resilience4j 库），本清单 commit + 主计划同步修订（§6 限流行 Resilience4j→Redisson、记忆行改 ChatMemoryRepository 措辞、§3 状态行、配套文件行补 w5/w6 清单）——主计划四处同步点已核齐，随本 commit 落盘
- [x] T0.2 依赖落地 resilience4j-circuitbreaker（2.4.0，`dependency:tree` 校验）+ **RRateLimiter 桶语义单机冒烟 7/7 全绿**（可回归语义锁 `RRateLimiterSemanticsSmokeTest`，证据 docs/resilience/）：trySetRate 只首次生效/setRate 覆盖 ✓、rate=2/1s 连发 5 次 2 过 3 拒 ✓、四参 = (type, rate, interval, ttl) 且 TTL 连内部键一起覆盖 ✓、桶键 TTL 方案定稿（用户桶四参 1h / 全局桶三参常驻 / Guard 先 trySetRate 再 tryAcquire，见 §2.1）
- [x] T0.3 三个预核事实回填（ChatMemoryRepository 四方法 / MessageWindowChatMemory.builder / OpenAiChatOptions.streamUsage 均已 javap 实证 1.1.2）+ Spring AI 自带 Message JSON codec 排查（jdbc 仓库无 codec 类不可复用 → §2.3 定稿自研 type-tag 两态 JSON，证据 docs/resilience/w6d0-t03-fact-checks.md）

### D1：双层限流

- [x] T1.1 `infra/resilience/RateLimitGuard`：用户桶+全局桶（先用户后全局）、参数 yml 化（@Value `resilience.rate-limit.*`）、Redis 故障 fail-open 放行、桶键 TTL（用户桶四参 1h / 全局桶三参常驻，D0 定稿落地）
- [x] T1.2 ChatController 两端点前置闸：/api/chat → HTTP 429；/api/chat/stream → SSE error 话术 + done（前端零改动）；被限流不进图（零记忆读零 LLM 调用）
- [x] T1.3 限流冒烟四场景全 PASS（证据 docs/resilience/：burst.ps1 + smoke.txt + app-log-verdicts.txt + SSE raw ×3）：A 同用户 ×10 → 2 过 8 拒；B 双用户各 ×10 → 各 2 过 8 拒互不挤占；C 12 新用户 → 10 过 2 拒（全局桶独立）；D stream ×3 → answer×2 + error 话术×1。实测踩坑三条记录在脚本头：①per-request Start-Job 的进程唤醒抖动 >1s 窗口会撕开配额窗（改 job 内 HttpClient 齐射）；②PS5 无 BOM 读 UTF-8 脚本按 GBK，中文注释字节可破坏语法（补 BOM）；③Task.WaitAll 带 timeout 重载绑定失败需显式 cast Task[]。LLM 用本地 OpenAI 协议桩（w6d1-llm-stub.jsh，DEEPSEEK_API_KEY 不入仓不阻塞），限流语义与真 LLM 无关
- [x] T1.4 单测（mock Redisson，6 用例）：用户桶拒绝短路且全局桶零消耗 / 全局桶拒绝 / 双桶通过 / Redis 故障 fail-open / 每次先 trySetRate 幂等重建 / 参数锁定（用户桶四参 TTL 版+全局桶三参版）

### D2：会话记忆 Redis 化

- [x] T2.1 `infra/memory/RedisChatMemoryRepository`：hash `chat:memory:{会话}` + 序号 field + 两态 type-tag JSON（D0 定稿自研）+ TTL 7 天写时刷新 + 读写 fail-open（读→空历史照常聊 / 写→吞掉 log warn）；损坏条目单条跳过不拖垮整窗（降级粒度=单条）
- [x] T2.2 ChatClientConfig 换装（`chatMemory()` 一处改，W5D4 预留位兑现；`MessageWindowChatMemory.builder().chatMemoryRepository(...)` 窗口 20 默认不变）+ 单测 8 例：读写往返 / 序号乱序重建 / 未知类型与损坏条目单条跳过 / 读故障 fail-open / 写故障不外抛 / 跨会话分键 / 会话扫描剥前缀 / **换装集成窗口截断 25→20**（锁定"只换存储、窗口语义零改动"）——全量 116 中 109 绿+7 跳
- [x] T2.3 冒烟三 phase 全 PASS（docs/resilience/w6d2-memory-smoke.txt + w6d2-memory-smoke.sh + w6d2-llm-stub.jsh marker 回路桩——桩统计请求体 marker 出现次数与 role 条数做确定性断言，无需真 LLM）：①重启应用同会话 recall **marker_hits=2** = 记忆从 Redis 回来（对照 W1 失忆基线 hits=1，Redis 键随停机幸存）；②跨会话隔离 hits=1 无泄漏；③TTL 写时刷新 604786→604800 重置；④窗口 25 轮（550ms 步进）→ HLEN=20；⑤redis-cli 直读 hash：field=序号、value=`{"type":"USER","content":...}` 可演示。**插曲（降级矩阵活证）**：25 连发未步进被 W6D1 限流闸掐到只剩 3 轮（HLEN=6）——限流在 chat 入口端到端生效的意外实证。W1 清单中依赖 LLM 行为的条目（购物边界词等）待真 Key 环境 D5 复跑

### D3：熔断 + 降级

- [x] T3.1 `infra/resilience/LlmCircuitBreaker`（Resilience4j 2.4 programmatic 封装，参数 yml 化 `resilience.circuit-breaker.*`）+ chat 节点接线：execute 包住整段 LLM 调用（token 流照常走 sink 旁路，熔断器只统计整次调用成败）；OPEN 抛 `CallNotPermittedException` 短路不调 LLM 直走降级；CLOSED 下真实失败记入滑窗后透传同样走降级。实测 API 坑：Builder 是 `slidingWindowType`+`slidingWindowSize` 两方法、迁移方法名 `transitionToForcedOpenState()`、`getNumberOfNotPermittedCalls` 返回 long
- [x] T3.2 `RuleFallbackService` 意图分类 5 类（交易/订单/物流/商品/兜底）关键词规则命中话术模板；**交易类最先判定**（下单/退款/取消红线：只引导稍后再试，话术无"已下单"等执行性表述——没有 LLM 就没有二次确认链路，绝不规则执行）；降级回复按 4 字/40ms 小片走 answer token 流（SSE 打字机照常前端零改动）；persistMemory 照跑（降级轮进记忆，恢复后 LLM 知道降级期说过什么）
- [x] T3.3 `DevResilienceController`（@Profile("dev")）：GET /api/dev/resilience/breaker（状态+失败率+滑窗计数+短路次数）/ POST reset（模拟 API 恢复）/ POST force-open（无故障演示 OPEN）
- [x] T3.4 熔断冒烟全 PASS（docs/resilience/w6d3-circuit-smoke.txt）：段1 错 base-url `http://127.0.0.1:9` 真实故障注入——CLOSED 5 连败（5/5=100%，min5 满）→ **OPEN**；OPEN 短路 0.61s 不发起连接；等 20s 半开放行探测（真实尝试连接 0.55s）仍失败 → **回 OPEN**；段2 桩 LLM 恢复环境——force-open → FORCED_OPEN 下降级回复 SSE 分片照发；persistMemory 照落；reset → CLOSED → 正常回答恢复（history_msgs=6 含降级轮）。参数定稿：§2.2 起点参数实测全部成立不调整
- [x] T3.5 单测 12 例（全量 128 中 121 绿+7 跳）：fallback 五类命中/交易优先判定/话术无执行性表述/null 安全（5）；熔断器 CLOSED 失败透传+滑窗计数 / 失败率过阈值自动跳 OPEN / OPEN 短路 supplier 不执行 / 半开放行探测成功闭合 / 半开探测失败回 OPEN / reset 与 forceOpen（6）；graph 层 breaker OPEN 短路 verifyNoInteractions(chatClient) + 规则回复走 token 流 + chatMemory.add ×2 记忆照落（1）

### D4：观测

- [ ] T4.1 `infra/obs/TurnMetricsRecorder`：轮级 outcome/耗时/首 token 延迟/token 用量/工具清单（包装 ToolEventListener 计数）；单行 JSON 日志 + 环形缓冲 100
- [ ] T4.2 `DevObsController`：GET /api/dev/obs/turns + /api/dev/obs/stats
- [ ] T4.3 冒烟：OK / RATE_LIMITED / DEGRADED 三类轮次记录齐全；DeepSeek 流式 usage 实测（拿不到 → N/A + 估算口径回填 §2.4）
- [ ] T4.4 单测：指标采集 / 工具计数 / outcome 分类 / 环形缓冲

### D5：buffer + 收尾

- [ ] T5.1 混沌回归 C1-C5（W6 动了 chat 链路与 pom，交易安全必须重验）+ 新增 **C6 限流突刺 / C7 熔断故障注入恢复**，证据存 `docs/resilience/`
- [ ] T5.2 README「稳定性设计」章节：三件套分工表 + 双层限流设计（用户桶/全局桶/检查顺序图）+ 分级降级矩阵补格（chat 记忆 fail-open、限流 fail-open）+ 面试三层追问预演（令牌桶参数怎么定 / 为什么熔断进程内而限流分布式 / 降级为什么是规则回复）+ 已知局限（userId 可伪造换桶 / 熔断进程内 / 观测非全链路 / 降级无查询直答）；架构图加 resilience 层节点
- [ ] T5.3 commit 校对（`W6D{n}:` 格式）+ 本清单硬性指标逐项勾选 + 主计划 §6/§3 状态更新 + 项目记忆更新
- （可选加分，时间富余才做）双实例 curl 演证：同 conversationId 跨实例记忆连续 + 全局桶跨实例共享——「无状态扩容」实证；做不完不算欠账

---

## 五、测试口径（沿用 AGENTS.md）

- `infra/resilience/`、`infra/memory/`、`infra/obs/`：单测必须（mock Redisson / ChatClient / 时钟，零外部依赖，沿用 MockRedissonConfig 模式）
- `tools/` 零改动；agent/ 与 controller/ 改动靠冒烟清单手工验证
- 真 Redis / 真 LLM 故障注入 / PS5 并发突刺冒烟单独跑，不进单测

## 六、风险与砍单线

1. **RRateLimiter 语义坑**（trySetRate 只首次生效 / 桶键无默认 TTL / PER_CLIENT 误读）→ T0.2 单机冒烟实证后再写 RateLimitGuard
2. **DeepSeek 流式 usage 不返回**（streamUsage 需 API 侧支持 stream_options.include_usage）→ D4 实测；不可得则 N/A + 字符数估算口径（面试讲清口径）
3. **熔断误伤偶发超时** → minimumNumberOfCalls=5 + 失败率 50% 保守起步；慢调用阈值不开（默认只算失败率）
4. **Message 序列化多态坑** → 只存 User/Assistant 两态（现状如此）+ T0.3 codec 排查优先复用
5. **userId 无登录态可伪造**（换 userId 绕过用户桶）→ 已知局限记录（真解 W8+ 登录态），全局桶兜底
6. **砍单线**（主计划原文 + 本清单补充）：不上 Micrometer/Prometheus/Grafana；不做分布式熔断状态；不做限流排队/优先级/黑名单；规则回复只做关键词意图分类不做 NLU；不做查询工具直答（用户已裁：纯话术）；双实例演证是加分项不是欠账；观测不做全链路 trace；embedding 不挂限流

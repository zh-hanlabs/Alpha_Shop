# ShopAgent 项目主计划（Vibecoding 总纲）

> 用途：本文件是项目的唯一事实来源（Single Source of Truth）。以后每次用 AI 辅助开发（vibecoding）时，把本文档 + 对应周的任务清单一起喂给 AI，即可冷启动。
> 配套文件：`shopagent-w1w2-mvp-tasks.md`（W1-2 逐日任务）· `shopagent-w3w4-tasks.md`（W3-4 逐日任务 + 设计定稿）· `shopagent-w5-tasks.md`（W5 逐日任务 + 设计定稿）· `shopagent-w6-tasks.md`（W6 逐日任务 + 设计定稿）· `shopagent-w7-tasks.md`（W7 逐日任务 + 设计定稿）· `shopagent-w8-tasks.md`（W8 逐日任务 + 收官核销）
> 创建时间：2026-10-03 · 维护规则：每完成一周，更新对应周的「状态」列

---

## 0. 一句话定位

**对话式电商交易 Agent**：用自然语言完成"查—问—办"全流程，核心差异化是把高并发交易系统的工程思维（幂等、分布式锁、限流、多级缓存）迁移到 LLM Agent 场景。

**简历故事线（所有开发决策的裁判）**：
> "我把高并发交易系统里的幂等、锁、限流降级这套工程思维，迁移到了 LLM 驱动的交易系统。"

**面试映射表**：每条简历 bullet ↔ 可被追问的技术深度点

| 简历 bullet | 追问深度点（自己必须讲得清） |
|---|---|
| ReAct + 工具调用 + RAG | 工具 description 设计、模型决策日志、幻觉参数防御 |
| 幂等 + 分布式锁防重复扣款 | 幂等键设计、锁粒度、锁超时与看门狗、最终一致 |
| LLM 限流熔断降级 | 令牌桶参数怎么定、熔断窗口、降级到什么 |
| 会话记忆分布式化 | 为什么 InMemory 不够、Redis 结构选型、无状态扩容 |
| 多级缓存 + 压测 | 缓存一致性、热点 key、压测数字怎么来的 |

---

## 1. 技术栈基线（版本锁定）

| 组件 | 选型 | 版本/说明 | 不可漂移原因 |
|---|---|---|---|
| JDK | 17+ | Spring Boot 3.x 硬性要求 | — |
| 框架 | Spring Boot | 3.5.x（2026-10-03 起跟随 SAA 1.1.2.x GA 基线，3.3.x 已 EOL 且不兼容当前 GA） | 与 Spring AI Alibaba 匹配 |
| Agent 框架 | Spring AI Alibaba | 1.x（以 java2ai.com 当前 GA 为准；2026-10-03 落地 1.1.2.4-security-fix，starter 坐标 spring-ai-alibaba-starter-dashscope，GA 已发中央仓无需 milestone 仓库） | 你的开源贡献主场 |
| LLM | 通义 qwen-plus / DeepSeek | 调试 qwen-turbo，验收 plus；2026-10-03 起实际接入 DeepSeek（已有 Key，spring-ai-starter-model-openai 兼容协议，切回路径见 README） | 便宜 + 国内可用 |
| ORM | MyBatis-Plus 或 JPA | 二选一后不再换 | — |
| 数据库 | H2 → MySQL 8 | W1 用 H2，W7 部署切 MySQL | 零依赖起步 |
| 缓存/锁 | Redis + Redisson | W3 引入 | 简历核心 |
| 向量库 | Redis Stack 或 PGVector | W5 引入，不引独立向量库 | 轻量 |
| 压测 | JMeter | W7 | — |
| 部署 | Docker Compose | W7 | — |
| 构建 | Maven 单体多模块（逻辑分包） | 不拆微服务 | 面试讲"如何演进" |

**禁止引入**：微服务拆分、多 Agent 编排框架、A2A、前端构建工具链、独立消息队列（除非 W6 后有富余且面试需要）。

---

## 2. 工程结构（演进式，W3+ 扩展 infra/）

```
shopagent/
├── pom.xml
├── AGENTS.md              ← W1 Day1 建立：拷贝本文档「§9 开发约定」
├── README.md              ← 架构图 + 启动步骤 + 演示 GIF
└── src/main/
    ├── java/com/shopagent/
    │   ├── ShopAgentApplication.java
    │   ├── config/            # ChatClient 装配、Redis、模型参数
    │   ├── controller/        # /api/chat、/api/chat/stream
    │   ├── agent/             # 编排层：W1 ChatClient 直连 → W5 迁 Graph
    │   ├── tools/             # @Tool 工具层
    │   │   ├── query/         # W1-2：订单/物流/商品查询
    │   │   ├── trade/         # W3：下单/退款/取消（幂等+锁）
    │   │   └── support/       # ToolResult、ToolContext（userId 注入）
    │   ├── service/           # 订单/商品/物流业务逻辑
    │   └── infra/            # W3: idempotent/ + lock/；W6: resilience/
    └── resources/
        ├── application.yml
        ├── schema.sql / data.sql
        └── static/index.html
```

**分层铁律**：tools/ 只做参数校验和编排，业务逻辑在 service/，横切能力在 infra/。这是面试讲"模块化单体"的实证。

---

## 3. 路线图总览（8 周）

| 周 | 主题 | 核心交付 | 简历价值 | 状态 |
|---|---|---|---|---|
| W1-2 | MVP 跑通 | ReAct + 查询工具 + SSE + 聊天页 | 铺垫 | ✅ 2026-10-03 |
| W3-4 | **交易安全（核心）** | 下单/退款工具 + 幂等 + Redisson 锁 | ⭐⭐⭐ | ✅ 2026-10-03 |
| W5 | RAG + 缓存 | 商品知识库问答 + 热点多级缓存 | ⭐⭐ | ✅ 2026-10-04 |
| W6 | 稳定性 | LLM 限流熔断降级 + Redis 会话记忆 | ⭐⭐ | ✅ 2026-10-04 |
| W7 | 数字 + 部署 | JMeter 压测 + Docker Compose + H2→MySQL | ⭐⭐ | ✅ 2026-10-05 |
| W8 | 打磨 | 简历措辞 + 三层追问准备 + commit 整理 | 收口 | ✅ 2026-10-05 |

> W1-2 已有逐日清单见 `shopagent-w1w2-mvp-tasks.md`，下文从 W3 起展开。

---

## 4. W3-4：交易工具 + 幂等 + 分布式锁（项目灵魂）

### 4.1 背景问题（写进 README，面试直接讲）

Agent 调交易工具的三个风险：
1. **模型重试**：LLM 网关超时重试 → 同一意图执行两次 → 重复扣款
2. **用户连点**：前端/语音场景用户重复发出指令
3. **流式断连重放**：SSE 断开后客户端重发最后一条消息

### 4.2 幂等设计（先想清楚再写码）

```
幂等键 = sha256(userId + action + orderId + paramsDigest)
存储   = Redis SETNX key value EX 86400
语义   = 同键首次执行放行，后续返回首次结果（不是简单拒绝！）
```

- 首次结果存 Redis（`idempotent:result:{key}`，TTL 与幂等键同步）
- 命中幂等时返回首次的 ToolResult，模型对用户表现为"已办理过"
- **面试深挖点**：为什么返回首次结果而不是报错？——LLM 会根据报错重试，形成死循环

### 4.3 分布式锁设计

```
锁键   = lock:trade:{userId}:{orderId}
工具   = Redisson RLock，看门狗续期
粒度   = 用户+订单级（不要全局锁，面试讲锁粒度权衡）
超时   = waitTime 3s / leaseTime 由看门狗管理
兜底   = 拿锁失败 → 返回"操作处理中，请稍后"而非排队堆积
```

- **双保险关系**：锁防并发，幂等防重放。只锁不幂等：重试穿透；只幂等不锁：并发窗口内双写
- 锁内逻辑：查状态 → 校验 → 执行 → 写幂等结果 → 释放

### 4.4 任务分解

| 日 | 任务 | 完成标志 |
|---|---|---|
| W3D1-2 | 下单工具 `placeOrder`（含库存校验）+ 二次确认话术 | 对话可完成一次下单 |
| W3D3 | `infra/idempotent/` 幂等组件（注解 `@Idempotent` 或显式调用） | 并发单测：同键只执行一次 |
| W3D4 | `infra/lock/` Redisson 接入 + 锁粒度封装 | Jedis/mock 并发单测通过 |
| W3D5 | 退款/取消工具接入幂等+锁，越权校验（只能退自己的单） | 全链路冒烟 |
| W4D1-2 | **混沌测试**：模拟模型重试（同请求重发 ×10）、并发下单 ×50 | 零重复扣款，截图存证 |
| W4D3 | 交易审计日志表（谁、何时、幂等键、结果） | 事后可追溯 |
| W4D4 | README 增加「交易安全设计」章节 + 流程图 | 生人看懂设计 |
| W4D5 | Buffer + commit 整理 | — |

### 4.5 面试三层追问预演（提前背熟）

1. **为什么不用数据库唯一索引就够了？** → 答：唯一索引是最后防线，本方案在前置层拦截，减少 DB 压力 + 天然支持"返回首次结果"语义
2. **锁超时任务没执行完怎么办？** → 答：看门狗续期 + 业务幂等兜底，锁只是优化不是正确性来源
3. **幂等键怎么防用户正常重复下单？** → 答：键里带 paramsDigest（商品+数量），买两件同商品是不同键；同一订单的退款才是同键

---

## 5. W5：RAG + 多级缓存

| 任务 | 说明 |
|---|---|
| 商品知识库 | 商品详情/FAQ 文本向量化入 Redis Stack（或 PGVector） |
| 检索工具 | `searchKnowledge(keyword)` 接入 RAG：召回 → 重排 → 注入上下文 |
| 热点缓存 | 商品查询走 Caffeine(本地) + Redis 两级；热点 key 打标 |
| 缓存一致性 | 更新商品走 Cache Aside；面试讲"为什么先更库再删缓存" |
| 编排升级 | agent/ 从 ChatClient 直连迁到 Spring AI Alibaba Graph（状态节点化） |

**砍单线**：知识库只做商品域（几十条文档足够演示），不做通用爬取。

---

## 6. W6：稳定性三件套

| 能力 | 设计要点 |
|---|---|
| 限流 | Redisson RRateLimiter 分布式令牌桶，护 LLM API：全局 QPS + 单用户 QPS 双层（2026-10-04 修订：原 Resilience4j RateLimiter 为进程内实现，多实例下单用户桶配额 ×N 放大，与无状态扩容矛盾；裁决与设计见 shopagent-w6-tasks.md §2.1） |
| 熔断 | Resilience4j CircuitBreaker 失败率熔断，半开探测恢复；熔断期间**降级到规则回复**（"高峰期，简单问题我直接答"）而非报错 |
| 会话记忆 Redis 化 | 自定义 ChatMemoryRepository 实现（Redis hash: `chat:memory:{conversationId}`，TTL 7 天，MessageWindowChatMemory 窗口语义复用），接入层变无状态 |
| 观测 | 决策日志结构化：每轮耗时/token 消耗/工具命中率，W7 压测的数据源 |

**面试深挖点**：降级策略为什么是"规则回复"——用户体感 > 系统正确性的场景判断。

---

## 7. W7：数字 + 部署（简历数字收口周，2026-10-04 清单化）

| 任务 | 说明 |
|---|---|
| JMeter 压测 | 四组脚本：商品详情缓存链路（冷/热命中对比）/ 交易下单并发（零超卖 + 同键重放）/ 聊天阻塞端点（限流前后对比，桩 LLM）/ SSE 流式（插件）；项目 DoD 两组数字必须落 README |
| 压测口径 | 桩 LLM=链路吞吐非 LLM 能力（口径声明）+ 真 DeepSeek 小样本延迟参照；取数双源=JMeter JTL/HTML + TurnMetrics 单行 JSON 日志（W6D4 预埋的取数源） |
| H2→MySQL 8 | 双 profile：dev/单测保持 H2（clone 即跑），新增 mysql profile 供压测与部署；schema 按平台分文件（ADR D8） |
| Docker Compose | 一条命令拉起全部依赖：redis-stack（本地自建镜像）+ MySQL 8（healthcheck + .env 注入）；app 宿主机跑，容器化为加分项 |

**砍单线**：不做多机分布式压测；不上 Prometheus/Grafana（维持 W6 裁决）；真 LLM 只小样本。逐日任务与设计定稿见 `shopagent-w7-tasks.md`。

---

## 8. W8：打磨收官（2026-10-05 清单化）

| 任务 | 说明 |
|---|---|
| 简历 bullets | 5 条对照 §0 映射表，数字全部可溯源到 docs/ 证据，入 `docs/resume/` |
| 三层追问自测 | 逐 bullet 三层自测（原理→数字口径→反驳局限），弱项补进 docs/study 学习指南，不另建文档 |
| 已知局限话术 | 四章已知局限汇总为「主动讲 + 缓解路径」标准应答并入学习指南 |
| commit 整理 | 全量校对 `W{周}D{天}:` 格式，只校对不重写历史；v1.0 轻量标签打在 W8 最终 commit 上 |
| README 截图收口 | 聊天页 / 混沌 / JMeter 三图入仓 `docs/screenshots/`；克隆即跑三步终验 |
| 项目 DoD 终验 | §12 六项逐项核销；查→问→办（含退款）演示链路证据留档；W8 ✅ 后项目完结 |

**砍单线**：零新功能、零代码改动（终验钓出 bug 除外=最小修复+单测+另立 commit）；不做 GIF/视频 demo、英文 README、在线部署；不改写 commit 历史。逐日任务与设计定稿见 `shopagent-w8-tasks.md`。

**W8 状态**：✅ 2026-10-05 收官——T5.1 真 DeepSeek 演示链路终验（`docs/deploy/w8d5-demo-evidence.txt`）+ T5.2 §12 六项 DoD 逐项核销 + T5.3 `v1.0` 轻量标签打在 W8 最终 commit。八周路线图全部走完；§12 第 5 项（简历 bullet 扛住三层追问）的终审=本人脱稿复述，判定权不随文档收官。

---

## 9. 开发约定（Vibecoding 规则，W1 拷入仓库 AGENTS.md）

### 给 AI 编码助手的行为规则

1. **先读主计划再动手**：每次会话先确认本次任务属于哪一周哪个任务编号，不做范围外的事
2. **小步提交**：一个任务一个 commit，消息格式 `W3D3: infra/idempotent 幂等组件 + 单测`
3. **不擅自引依赖**：新增任何依赖必须先说明理由并对照 §1 禁止清单
4. **不过度设计**：没有 W 任务编号支撑的抽象不做；三处重复再抽，一处不抽
5. **测试口径**：infra/ 和 service/ 必须有单测；tools/ 靠冒烟清单手工验证
6. **密钥安全**：API Key 只走环境变量，任何代码/配置/测试不落盘真实密钥
7. **遇歧义先问**：涉及幂等语义、锁粒度、缓存一致性这三个点，先对齐设计再写码

### 代码风格

- 包名 `com.shopagent.*`，类名见名知义，工具类后缀 `Tool`，业务后缀 `Service`
- 所有工具方法返回 `ToolResult{code, msg, data}`，异常内部消化不外抛
- 注释只写 Why 不写 What；魔法数字进常量类
- 每周末跑一遍冒烟清单（见各周 DoD）再标记完成

---

## 10. 已定决策记录（ADR-lite，防止 AI 会话反复横跳）

| # | 决策 | 理由 | 替代方案（为何不选） |
|---|---|---|---|
| D1 | 单体模块化，不拆微服务 | 两周迭代速度 + 面试讲演进 | 微服务：运维成本爆炸 |
| D2 | H2 起步，W7 切 MySQL | clone 即跑的演示体验 | 直接 MySQL：环境劝退 |
| D3 | 幂等返回首次结果，非报错 | 防 LLM 重试死循环 | 直接拒绝：模型会重试 |
| D4 | 锁粒度 = 用户+订单 | 并发度与安全平衡 | 全局锁：压测数字难看 |
| D5 | InMemory 记忆 W6 才换 Redis | MVP 速度优先，演进有故事 | 一步到位：少一个演进叙事 |
| D6 | 向量库用 Redis Stack | 不加运维负担 | 独立向量库：超范围 |
| D7 | 原生单页前端 | 面试官看 Agent 不看 CSS | React：时间黑洞 |
| D8 | W7 双 profile：H2 留 dev/单测，MySQL 走压测与部署 | clone 即跑保留 + 单测口径零动（D2 落地形态兑现） | 全量切 MySQL：克隆即跑倒退，演示体验劝退 |

---

## 11. 风险与砍单清单（Scope Guard）

| 风险 | 触发信号 | 应对 |
|---|---|---|
| W3 混沌测试过不了 | 并发下单出现重复记录 | 降级方案：数据库唯一索引兜底 + README 记录分析过程（面试反而加分） |
| 进度落后 >3 天 | 周末 Buffer 用尽 | 砍 W5 的 Graph 迁移（保留 ChatClient 直连），保交易安全主线 |
| LLM 调工具不稳 | qwen-plus 仍频繁幻觉参数 | 收紧工具 description + 参数白名单校验，不换框架 |
| 时间富余 | W6 提前完成 | 优先补压测深度和 README 质量，不扩功能 |

**总原则：功能可以砍，W3-4 的交易安全不能砍**——它是整个项目的简历价值核心。

---

## 12. 完成定义（项目级 DoD）

8 周结束时：
- [x] 演示链路全通：查 → 问 → 办（含退款），带幂等与锁的可视化日志 —— **W8D5 真 DeepSeek 终验**（13 轮，`docs/deploy/w8d5-demo-evidence.txt`）：查（u1001 的 10001 明细+物流，模型自主并行两工具，答案与库内真值 148.90/129.00/19.90/SHIPPED 逐项一致）→ 问（RAG 露营灯防水，四个事实点全来自语料）→ 办（下单先查再请确认、确认后一次落单，库存 43→42 只扣 1 次）→ 办（退款 10002→REFUNDED、两商品各还库存 +1、审计 id=139）；幂等=**跨层重放**（真 LLM 写的 `5eb9d614…` 键，工具层 `/api/dev/chaos/refund` 重放命中同一把锁与同一份缓存：`code=0` 返首次结果、库存零变化、审计 2 条同键）；锁=**命令级证据**：`redis-cli MONITOR` 30 条 EVALSHA = tryLock 22 + unlock 8、8 对 `hincrby ±1`、1 SET mark + 1 PSETEX result；单测口径零动（140 中 133 绿 + 7 跳）。**本轮另钓出第四个真问题**：模型「口头退款」（空转，三源证明零执行），已入 README 局限 8 / 踩坑 #20 / 学习指南 §9.3 第 4 条
- [x] JMeter 报告：含限流前后对比、缓存命中提升的 QPS 数字（简历用） —— `docs/jmeter/w7d3/w7d3-stress-matrix.md`（T3.1 更正说明 + 入仓 JTL 可复算）：限流关/开同形突发 300=全过 vs 10 放行（96.67% 429）；冷 523.6/s→热稳态 3644.3/s **≈7.0 倍**（P95 162ms→23ms 同 ≈7.0 倍；同形状冷热 2.6 倍作保守档一并报），双源对账（TurnMetrics ↔ JTL）；真 LLM 延迟参照 `docs/jmeter/w7d3/parse-real.txt`（usage 17/17）
- [x] README：架构图 + 交易安全设计章节 + 混沌测试截图 + 一键启动 —— 三张截图入仓（四张 PNG，JMeter 为冷/热双联）`docs/screenshots/w8d4-{chat-flow,chaos-c1,jmeter-cold,jmeter-hot}.png`，README 三处嵌入；`docs/**` 引用路径存在性全查零 MISSING；踩坑实录 20 条
- [x] commit 历史清晰可读（按周成段） —— 全量校对零违规（D3 复核 70/70 + W8D5 收官重扫 **74/74**，分布 W1×30 / W3×6 / W4×5 / W5×9 / W6×6 / W7×10 / W8×8；复算命令 `git log --format='%s' | grep -vcE '^W[0-9]+D[0-9]+: '` = 0），每段首末抽样 14 条 `git show --stat` 标题相符；按周分段档案见 `docs/resume/shopagent-bullets.md` 附二
- [ ] 每条简历 bullet 能扛住三层追问（对照 §0 映射表自测） —— **文档层已就位**：5 条 bullet × 三层 = 15 问答存档（学习指南 §11 末）+ §13 自测清单 29 项；**这一格的判定权在本人脱稿复述**（`docs/resume/shopagent-bullets.md` §7 七项，W8 清单 T1.3 同一道闸），复述通过的行由本人勾选，讲不出的回补学习指南——Agent 不代勾、不冒充完成
- [x] Docker Compose 一条命令拉起全部依赖 —— `compose.yml`（redis-stack + mysql 8.4，健康门 + AOF `--dir /data`）；克隆即跑终验 `docs/deploy/w8d4-final-smoke.txt`（独立项目 + 全新卷从零走通 8 项判定，含容器全重建后幂等标记存活复验）

---

## 13. 每周开工提示词模板（直接复制用）

```
我在开发 ShopAgent 项目（对话式电商交易 Agent，Spring AI Alibaba）。
请先阅读项目根目录 AGENTS.md 和 PLAN 中【W{N}】章节。
本次任务：{粘贴对应周任务表中的一条}。
约束：遵守 §9 开发约定，不做范围外的事，完成后给出验证步骤。
```

---

*本计划由 2026-10-03 的方向决策会话生成。方向变了先改这里，再改代码。*

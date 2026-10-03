# ShopAgent · W5 RAG + 多级缓存开发任务清单

> 目标版本：一周（D0-D5 六个工作日）：商品知识库 RAG + 两级缓存 + Graph 编排升级
> 原则：W5 的主目标是 RAG + 缓存一致性（简历第二叙事轴）；Graph 是锦上添花，**不可拖垮主目标**
> 配套：主计划 §5（任务依据）· AGENTS.md（开发规则）· shopagent-w3w4-tasks.md §2（W3 已定幂等/锁口径，勿动）

---

## 一、验收标准（Definition of Done）

一周结束时，能完成这条演示链路：

> 「露营灯防水吗」→ Agent 调 searchKnowledge → 「IPX5 防水，小雨可淋，不可浸泡」（知识库事实，非模型编造）
> 「充电宝能带上飞机吗」→ 政策 FAQ 命中 → 「100Wh 以下可随身携带…」
> 同一商品再问一次 → 日志显示 L1 本地缓存命中（0 次 DB 查询）
> dev 端点改价 → 再问 → 新价格（无脏读，Cache Aside 先更库再删缓存）
> `docker stop` Redis → 交易 fail-closed「交易暂不可用」+ 知识库降级话术 + 商品查询走库 → 重启后全部自愈。

硬性指标：

- [ ] 知识问答冒烟 ≥8 条：命中正确文档且关键事实准确（IPX5 / 退货政策天数等）
- [ ] `searchKnowledge` 与 `searchProduct` 路由正确（含一次链式调用案例：先查知识再搜商品）
- [ ] 相似度阈值截断生效：无关问题（天气）不注入知识，不返回垃圾召回
- [ ] 缓存证据：同商品二次查询 L1/L2 命中日志；**库存字段永不走缓存**（日志验证）
- [ ] 一致性：`updateProduct` 改价 → L1+L2 双删 → 再查回源新值（截图存证 docs/cache/）
- [ ] 分级降级矩阵实测：Redis 停机 → 交易 fail-closed（W3 回归）+ 知识检索降级 + 缓存透传走库，恢复自愈
- [ ] Graph：迁移成功则打字机效果与五类 SSE 事件不变、前端零改动；否则 ADR 记录回退决策（两者都算完成）
- [ ] `mvn test` 全绿；混沌 C1-C4 回归 PASS（W5 大量动了 Redis 环境与 pom，必须回归）
- [ ] README 新增「RAG 与多级缓存设计」章节（检索分层表 / 缓存边界与一致性 / 分级降级矩阵）

---

## 二、设计定稿（2026-10-03 用户确认冻结：§2.1 DashScope / §2.4 方案A / §2.5 spike先行；开发中改动需先改这里）

> 裁决记录：主计划 §5 只给了任务名，本节把歧义点定死。开发中改动需先改这里（同 W3 惯例）。

### 2.1 Embedding 提供方【已定稿：DashScope text-embedding-v4】

**事实**：DeepSeek API 只有 chat completions，**没有 embedding 端点**，向量能力必须引入第二提供方。

| 方案 | 说明 | 判断 |
|---|---|---|
| **A（推荐）DashScope text-embedding-v4** | `spring-ai-alibaba-starter-dashscope`（1.1.2.4-security-fix），默认 1024 维，中文强，SAA 主场对齐 | 成本：40 条文档全量重建 ≈ 0.004 元，忽略不计；与「Spring AI Alibaba」项目定位一致 |
| B 本地 ONNX | `spring-ai-transformers` + all-MiniLM-L6-v2 / bge-small-zh | 零 API 成本、离线确定；但 80MB 模型文件管理 + 中文质量一般，简历叙事弱 |
| C 其他云 embedding | 智谱 / SiliconFlow 等 | 引入第三家供应商，复杂度不值 |

**双模型共存机制（关键工程点）**：当前 chat 走 `spring-ai-starter-model-openai`（DeepSeek），加 DashScope starter 后用 Spring AI 路由属性分离：

```yaml
spring.ai.model.chat: openai          # 聊天保持 DeepSeek
spring.ai.model.embedding: dashscope  # 向量走 DashScope
```

- 路由不生效的后备方案（D0 验证时定）：exclude DashScope 聊天自动装配，或仅依赖非 starter 构件手工构造 `DashScopeEmbeddingModel` bean
- 新增环境变量 `AI_DASHSCOPE_API_KEY`（密钥只走环境变量，AGENTS.md 铁律）
- 切换 embedding 模型 = 维度变 = **索引必须重建**（D1 用 `FT.INFO` 校验维度一致）

### 2.2 向量库与知识库（已定稿）

- **容器**：`shopagent-redis`（redis:7-alpine，无 RediSearch 模块）→ 替换为 `redis/redis-stack-server`（7.4.x-v 线，D0 以 `docker pull` 实际可得 tag 为准），端口 6379 不变，应用配置零改动
  - 替换即清空：现有幂等 mark/result（TTL 24h）丢失可接受——dev 环境，应用重启即重建，混沌脚本自包含
  - 向量库与幂等/锁**共用同一实例**（demo 规模）；生产应分离——独立故障域 + 独立扩容（面试点）
- **接入**：`spring-ai-starter-vector-store-redis`（BOM 1.1.2 内），自动装配 `RedisVectorStore`（基于 Jedis）。**Jedis 与 Redisson 并存**：各自连接池互不影响，职责分离（Redisson=锁/幂等/缓存 L2，Jedis=RediSearch FT.*）
- **索引参数**：index-name `shopagent-knowledge`，prefix `knowledge:`，FLAT 算法（几十条规模精确 KNN 足够，HNSW 的近似搜索在小索引无收益还多一层解释成本），metadata：`source`（product/policy）、`productId`、`docType`（faq/spec）
- **语料**：`src/main/resources/knowledge/*.md`，**只做商品域**（主计划砍单线）：8-10 个商品各 3-5 条 FAQ + 平台政策 8-10 条（退货/换货/保修/运费/支付/发票），合计 ≈ 40 条
  - 文档粒度 = 单条 FAQ（<500 字），**不做递归切分**——切分策略服务规模，几十条规模下整条即最优 chunk
- **索引构建 `infra/rag/KnowledgeIndexer`**：`ApplicationReadyEvent` 触发；语料全集 sha256 指纹存 Redis，指纹不变跳过、变更删索引全量重建（幂等启动）；embedding 批量上限 10 条/次（DashScope 限制）分批灌入
  - 启动嵌入失败（Key 缺失/网络）**不阻断启动**：知识检索降级，聊天主链路照常——fail-open，见 §2.3

### 2.3 检索与重排（已定稿）

`searchKnowledge(query)` → `service/KnowledgeService.search`：

1. **召回**：query 向量化 → top-k（k=5），相似度阈值截断（起点 0.5，D2 冒烟实测定稿）
2. **重排**：轻量规则重排——查询词与商品名/文档标题的精确或包含命中加权 + 向量分，取 top-3。**不引独立 reranker 模型**：几十条规模多一次 API 调用不值，且「重排要不要上模型」本身就是按规模分层的面试叙事
3. **注入**：top-3 结构化进 `ToolResult.data`，模型组织自然语言回答；零召回（阈值下全 miss）→ notFound「知识库没有覆盖这个问题」，模型诚实告知并引导

- **降级语义（分级降级矩阵）**：Redis 停机 → error code「知识库暂不可用」→ 聊天继续。**交易 fail-closed（W3 已定）vs 知识/缓存 fail-open**：按业务代价分级——交易错一笔是真金白银，知识答错一句是体验问题
- **Prompt 路由**：`searchProduct`=结构化找商品（买什么/多少钱/有没有货）；`searchKnowledge`=商品使用/参数细节/平台政策（怎么用/怎么办）；两者可链式（「防水又便宜的灯」→ 先知识后搜索）。归属：知识库是公共域，无 userId 校验需求（对比订单工具的归属铁律）
- 工具事件：「🔍 正在查询知识库…」（ToolEvents 旁路，前端零改动）

### 2.4 两级缓存与一致性【已定稿：方案 A——展示字段进缓存，库存不进】

| 方案 | 说明 | 判断 |
|---|---|---|
| **A（已选定）缓存边界=展示字段** | `ProductDetailVO`（name/description/price/category）进两级缓存，**库存不进缓存** | 库存是交易正确性字段：每单扣减→每单失效，缓存形同虚设（失效风暴）；stock 永远实时查库，交易链路零接触（W3 成果零风险） |
| B 全实体缓存 + 变更双删 | 含 stock，下单/退款/改价都 L1+L2 双删 | Cache Aside 演示更「全」，但扣库存高频双删把命中率打崩，与「热点缓存」目标自相矛盾（未选） |

**两级结构（`infra/cache/TwoLevelCache`，手写不用 Spring Cache 抽象——面试要讲清每一层，抽象反而藏细节）**：

- L1 Caffeine：`maximumSize=500`，`expireAfterWrite=60s`（短 TTL：本地缓存无失效广播，60s 是不一致窗口上限）
- L2 Redisson RBucket：`cache:product:detail:{id}`，TTL 30min
- 读路径 L1 → L2 → 库，回填顺序 L2 先 L1 后；L2 故障 fail-open 降级 L1/库（缓存是加速器，不是正确性来源）
- **热点 key 打标**：访问计数（Redis `INCR` + 周期快照），只做指标暴露不实现调度——数字留给 W7 JMeter 出（面试点：打标是手段，淘汰策略才是调度）

**一致性演示（dev 端点 `POST /api/dev/cache/update-product`，@Profile("dev")）**：改价走 **Cache Aside 先更库再删缓存**（L1+L2 双删）。面试口径：先删缓存再更库的窗口内，并发读会把旧值回填进缓存（脏数据永久驻留到 TTL）；先更库再删缓存最多容忍一个短暂旧值窗口 = 最终一致。

### 2.5 Graph 编排升级策略【已定稿：spike 先行，通过才迁】

**事实（D4 spike 的原因）**：SAA Graph 节点官方示例是 `chatClient.stream().content().reduce().block()` 聚合式返回——节点粒度是「整段」，token 级流式打字机**不是开箱能力**，需旁路方案（节点内 `chatClient.stream()` + `Sinks.Many` 推 SSE，与 W1 `ToolEventListener` 同构，本项目有先例但未验证）。

| 方案 | 说明 |
|---|---|
| **A（推荐）spike 先行，通过才迁** | D4 上午 spike 流式旁路可行性；通过 → 下午迁主链路；不通过 → ADR 记录回退，保留 ChatClient 直连 |
| B 坚定全迁移 | 主计划原意，但流式若需 hack 手段，稳定性风险转嫁到核心聊天链路 |
| C 推迟到 W6 后 | 偏离主计划 §5；且 W6 限流/熔断会再动链路，越晚迁冲突越多 |

- 迁移形态（最小化）：`START → loadMemory → chat(ChatClient+tools，流式旁路) → persistMemory → END`，行为等价（记忆隔离/七工具/五类 SSE 事件/二次确认全不变），前端零改动
- **明确不做**：多 Agent 协作、子图嵌套、人工中断（human-in-loop）、Graph 可视化导出——单 Agent 状态图最小闭环，AGENTS.md 禁止清单红线内

---

## 三、依赖报备（对照主计划 §1 禁止清单）

| 依赖 | 版本 | 用途 | 合规依据 |
|---|---|---|---|
| `org.springframework.ai:spring-ai-starter-vector-store-redis` | BOM 1.1.2 管理 | Redis Stack 向量库（传递引入 Jedis） | §1 预批「向量库 Redis Stack，W5 引入，不引独立向量库」 |
| `com.alibaba.cloud.ai:spring-ai-alibaba-starter-dashscope` | 1.1.2.4-security-fix | embedding 提供方（若 §2.1 选 A） | §1 技术栈基线锁定项；当前仅用 openai starter 属协议兼容切法，SAA 本就是项目基座 |
| `com.alibaba.cloud.ai:spring-ai-alibaba-graph-core` | 1.1.2.x（D0 校验中央仓与 SAA 同线的最新版） | Graph 编排 | §1 工程结构注释明确「agent/ W1 直连 → W5 迁 Graph」；Graph=单 Agent 状态图（LangGraph 同类），**非**禁止清单所指「多 Agent 编排框架」 |
| `com.github.ben-manes.caffeine:caffeine` | Boot parent 管理 | L1 本地缓存 | 标准缓存库，无基础设施越界 |

---

## 四、逐日任务

### D0：设计定稿 + 环境切换（半天-1 天）

- [x] T0.1 用户确认 §2.1 / §2.4 / §2.5 三项裁决，冻结设计定稿，本清单 commit
- [ ] T0.2 Redis 容器替换 redis:7-alpine → redis-stack-server；幂等/锁快速回归（C1 脚本复跑）确认 W3 能力无损
- [ ] T0.3 依赖四件落地（版本以中央仓实存为准，`mvn dependency:resolve` 校验）；`spring.ai.model.chat=openai / embedding=dashscope` 路由配置；启动自检双模型共存不冲突（冲突则按 §2.1 后备方案）
- [ ] T0.4 `AI_DASHSCOPE_API_KEY` 环境变量就位（不落盘）；README 快速启动段更新（双 Key + Redis Stack）

### D1：知识库构建

- [ ] T1.1 语料 40 条：`knowledge/*.md`（8-10 商品 FAQ + 平台政策），商品与 data.sql 对齐（id/名称一致）
- [ ] T1.2 `infra/rag/KnowledgeIndexer`：指纹比对幂等启动 + 变更全量重建 + 批量分批（≤10/批）+ 失败不阻断启动
- [ ] T1.3 `FT.INFO` 校验：索引存在、维度 1024、metadata 字段齐全；语料改动重启后确实触发重建
- [ ] T1.4 单测：指纹比对逻辑 / 分批切片（mock EmbeddingModel，不打真 API）

### D2：检索工具 + RAG 链路

- [ ] T2.1 `service/KnowledgeService`：召回 top-5 → 阈值截断 → 规则重排 top-3 → 结构化 data
- [ ] T2.2 `tools/query/KnowledgeSearchTool` + ToolEvents「正在查询知识库」+ System Prompt 路由段更新
- [ ] T2.3 LLM 冒烟 ≥8 条（含链式调用、无关问题零注入），阈值按实测定稿回填本清单
- [ ] T2.4 单测：重排加权规则 / 阈值截断 / fail-open 降级（mock VectorStore）

### D3：两级缓存 + 一致性

- [ ] T3.1 `infra/cache/TwoLevelCache`：L1 Caffeine + L2 RBucket，读路径回填、L2 故障降级
- [ ] T3.2 `ProductService` 详情走缓存（**stock 排除在缓存外**）；searchProduct 列表不缓存（走库）——缓存边界按数据变更特征划
- [ ] T3.3 dev 端点 `update-product`：先更库再删缓存（L1+L2 双删）演示链
- [ ] T3.4 一致性冒烟存证 `docs/cache/`：改价→双删→回源新值；同商品二次查询 L1 命中日志
- [ ] T3.5 单测：回填顺序 / 双删 / L2 故障 fail-open / stock 不进缓存

### D4：Graph 编排升级（spike 制）

- [ ] T4.1 上午 spike：Graph 节点内流式旁路（Sinks.Many → SSE）可行性验证，独立分支代码
- [ ] T4.2 spike 通过 → 迁移主链路（loadMemory → chat → persistMemory），全链路回归：W1 冒烟清单 + W3 交易冒烟 + 前端打字机
- [ ] T4.3 spike 失败 → ADR 写入 docs/（保留 ChatClient 的决策依据），回退分支，主链路零改动
- [ ] T4.4 `mvn test` 全绿 + LLM 全链路冒烟（W1/W3 清单复跑）

### D5：buffer + 收尾

- [ ] T5.1 混沌回归 C1-C4（W5 动了 Redis 环境与 pom，交易安全必须重验）
- [ ] T5.2 README「RAG 与多级缓存设计」章节：检索分层表 / 缓存边界与一致性 / 分级降级矩阵 / 架构图加 knowledge+vector 节点
- [ ] T5.3 commit 校对（`W{周}D{天}` 格式）+ 本清单硬性指标逐项勾选 + 项目记忆更新

---

## 五、测试口径（沿用 AGENTS.md）

- `infra/rag/`、`infra/cache/`、`service/KnowledgeService`：单测必须（mock EmbeddingModel / VectorStore / Redisson，零外部依赖，沿用 MockRedissonConfig 模式）
- `tools/`：冒烟清单手工验证（知识问答 8 条 + 路由 + 链式）
- 真 Redis Stack / 真 embedding API 冒烟单独跑，不进单测

## 六、风险与砍单线

1. **spring.ai.model.* 路由不生效**（双 starter bean 冲突）→ §2.1 后备方案：exclude DashScope 聊天自动装配或手工构造 bean；仍不行则 embedding 降级方案 B（本地 ONNX）
2. **Graph 流式旁路不可行** → ADR 回退（§2.5 方案 A 的既定路径，不硬迁）
3. **text-embedding-v4 维度与 RedisVectorStore 默认不符** → D1 `FT.INFO` 校验，不符则 builder 显式指定维度
4. **语料质量差召回不准** → D2 冒烟迭代语料与阈值；语料是 fixture 不是产品，控制打磨时间
5. **缓存改造破坏交易链路** → stock 不进缓存（零接触）；D5 混沌回归兜底
6. **砍单线**（主计划原文 + 本清单补充）：知识库只做商品域不做通用爬取；不做语义缓存（Redis semantic-cache 是 LLM 响应缓存，另一命题）；不引 reranker 模型；不做多 Agent / 子图 / 人工中断；热点 key 只做计数打标不做动态调度（数字 W7 JMeter 出）；searchProduct 列表查询不缓存

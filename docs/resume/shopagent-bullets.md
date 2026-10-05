# ShopAgent · 简历 Bullets（对外交付物）

> **本文管「写什么」**：可直接粘进简历的项目段落 + 5 条 bullet + 数字出处表。
> 「怎么讲」（电梯演讲、三层追问预演、自测清单）在 `docs/study/project-overview-interview.md`——两份不重复维护（W8 清单 §2.2）。
> **裁判 = 主计划 §0 面试映射表**：每条 bullet 必须扛住三层追问（原理 → 数字口径 → 反驳与局限）。
> **红线**：简历上的每个数字都要能当场指出证据文件并说清口径，讲不出的降级或删除。
> 定稿：2026-10-05（W8D1）· 零代码改动周产物。

---

## 1. 一句话定位（与主计划 §0 同源）

对话式电商交易 Agent：用自然语言走完「查—问—办」，把高并发交易系统的工程思维（**幂等 / 分布式锁 / 限流熔断降级 / 多级缓存**）迁移到 LLM Agent 场景。

## 2. 技术栈行

JDK 17 · Spring Boot 3.5 · Spring AI Alibaba（Graph 编排）· DeepSeek（聊天）+ DashScope（embedding）· MyBatis-Plus · MySQL 8 / H2 双 profile · Redis + Redisson 3.52（锁 / 幂等 / 限流 / 记忆）· Redis Stack 向量库 + Caffeine 两级缓存 · JMeter 5.6.3 · Docker Compose · 原生单页前端

## 3. 项目描述段落（简历「项目描述」栏）

独立设计与实现的对话式电商交易 Agent（8 周分阶段迭代，commit 按 `W{周}D{天}` 成段可追溯）：模型自主决策调用 9 个工具完成订单查询、商品知识问答（RAG）与下单/退款/取消全链路，SSE 流式打字机输出，一条命令可复现（Compose 拉起依赖 + H2 内存库克隆即跑）。
核心工作不是「接上大模型」，而是把 LLM 当成**不可靠的调用方**——它会重试、会重放、会传错参数——因此在工具层用代码写死交易闸序（锁外快查 → 分布式锁 → 幂等四态 → 审计），让正确性不依赖模型的自觉。
稳定性上为最贵最脆的 LLM 外呼链路建了三层护盾：分布式双层令牌桶限流、LLM 熔断、规则回复降级，并配套轮级结构化观测作为压测取数源。
所有结论以混沌测试与 JMeter 双源对账数字为准：并发 200 单零超卖、缓存冷热吞吐差 ≈7.0 倍、限流突发拦截 96.67%。

## 4. 五条 bullet（简历正文 · 标准版）

1. **ReAct Agent + RAG 知识问答**：基于 Spring AI Alibaba Graph 把「记忆读 → 模型决策 → 记忆写」显式节点化，编排 9 个电商工具（6 查询 + 3 交易）完成自然语言「查—问—办」；商品知识库 40 条语料入 Redis Stack 向量索引，检索走「召回 top5 → 相似度阈值截断 → bigram 规则重排 top3」，事实问答冒烟 10/10 且无关问题零注入；索引按语料指纹幂等重建（40 条 / 1.8s，重启零 API 调用）。

2. **幂等 + 分布式锁防重复扣款**：在工具层用 `TradeGuard` 统一闸序（锁外结果快查 → Redisson RLock `tryLock(3s)` + 看门狗 → SETNX 幂等四态 → 审计双出口），锁粒度 = 用户 + 资源，幂等命中**返回首次结果而非报错**以切断 LLM 重试死循环；混沌与压测实证：同键 100 并发仅 1 单且全部返回首次结果，异键 100 线程 200 单库存 500→300 精确对账零超卖，Redis 停机时交易 fail-closed 且恢复自愈。

3. **LLM 限流 / 熔断 / 降级三层护盾**：Redisson 分布式令牌桶双层限流（用户桶 2/s + 全局桶 10/s，先用户后全局、被用户桶拒绝不消耗全局配额，fail-open）+ Resilience4j 熔断（滑窗 10 / 失败率 50% / OPEN 20s / 半开 3 探测）+ 规则回复降级（5 类意图话术，交易类只引导绝不执行）；实测 300 请求突发仅放行 10（拦截 96.67%、全局桶精确 10/s），熔断 OPEN 短路 0.61s 不发起外呼，降级回复 40ms 出话术不裸报错。

4. **会话记忆分布式化（接入层无状态）**：自研 `RedisChatMemoryRepository` 实现 Spring AI `ChatMemoryRepository` 扩展点，Redis hash + 序号 field + 两态 type-tag JSON，TTL 7 天写时刷新，读写 fail-open（读失败空历史照常聊）；双实例实证跨进程记忆连续（A 实例存、B 实例取，marker_hits=2）与全局桶跨实例共享（12 用户精确 10 过 2 拒，429 分落两实例）。

5. **多级缓存 + JMeter 压测（把工程能力兑换成数字）**：Caffeine L1（500/60s）+ Redis L2（30min）两级缓存只缓存展示字段、**库存永不缓存**（正确性字段实时查库），Cache Aside 先更库再双删；JMeter 5.6.3 四组脚本 + 客户端 JTL / 服务端 TurnMetrics 日志双源逐条对账，商品详情冷→热吞吐 523.6→3644.3/s（≈7.0 倍）、P95 162→23ms（≈7.0 倍），同 JVM 隔离探针回源 28ms vs L1 命中 3-4ms。

**备用条（篇幅富余或面试展开时用，跨条引用）**：真 DeepSeek 小样本 n=17 延迟参照——首 token P50=735ms、整轮 P50=1045ms，Agent 链路自身开销（totalMs − llmMs）<6%，证明瓶颈在模型生成、限流护盾打在真瓶颈之前；token usage 真回传 17/17。

## 5. 精简版（每条一行，一页塞不下时用）

1. Spring AI Alibaba Graph 编排 9 工具 ReAct Agent + 40 条语料 RAG（top5→阈值→重排 top3，索引指纹幂等重建），自然语言完成「查—问—办」。
2. 工具层统一闸序 Redisson 锁 + SETNX 幂等四态 + 审计，同键重放返回首次结果防 LLM 重试；实测 200 单并发零超卖、同键 100 并发仅 1 单。
3. Redisson 双层令牌桶 + Resilience4j 熔断 + 规则回复降级：突发 300 请求拦截 96.67%，熔断 OPEN 短路 0.61s，降级 40ms 出话术。
4. 自研 ChatMemoryRepository 落 Redis（hash + TTL 7 天写时刷新，fail-open），双实例跨进程记忆连续 + 全局桶跨实例共享，接入层无状态。
5. Caffeine + Redis 两级缓存（库存不缓存）+ JMeter 四组脚本双源对账：冷热吞吐 523.6→3644.3/s ≈7.0 倍、P95 162→23ms ≈7.0 倍。

---

## 6. 数字出处表（被问「这个数字怎么来的」的标准应答指针）

> 用法：左列=简历上出现的数字；中列=当场可打开的证据路径；右列=口径一句话（**主动说清数字量的是什么、不量什么**，比数字本身更值钱）。

| 数字 | 证据路径 | 口径一句话 |
|---|---|---|
| 9 个工具 | `src/main/java/com/shopagent/tools/query/`（6）+ `tools/trade/`（3） | 注册的工具数，不是模型调用次数；工具统一返回 `ToolResult{code,msg,data}` |
| 40 条语料 / 索引重建 40 条·1.8s | `src/main/resources/knowledge/`（10 文件 40 文档）、`docs/deploy/w7d4-compose-smoke.md` | 单条 FAQ=单文档不做切分（几十条规模切分无收益）；1784ms 是空库全量重建含 embedding API 往返 |
| 召回 top5 → 阈值 0.5 → top3 | `src/main/java/com/shopagent/service/KnowledgeService.java`、README「RAG 与多级缓存设计」 | 阈值 0.5 由 D2 实测定（命中分 0.73-0.95，无灰色地带），非拍脑袋；重排是规则不是 reranker 模型 |
| RAG 事实问答 10/10 | `docs/rag/smoke-w5d2.txt` | 真 LLM 冒烟：事实全准 / 链式调用 / 无关问题零注入三项合起来才叫 10/10 |
| 同键 100 并发仅 1 单（Avg=849ms） | `docs/jmeter/w7d3/s2k-t100.jtl`、`docs/jmeter/w7d3/w7d3-stress-matrix.md` | 849ms 是**锁排队代价**不是缺陷，随并发线性（t50=495ms→t100=849ms）——正确性换时延的明码标价 |
| 异键 200 单零超卖（500→300） | `docs/jmeter/w7d3/s2u-ramp.jtl`、`docs/jmeter/w7d3/w7d3-stress-matrix.md` | 100 线程 / 10s 爬升，三方核对：客户端样本数 = 落库订单数 = 库存 delta |
| C1 同键 ×10 仅 1 单 / C2 ×50 零超卖 / C3 退款只还一次 / C4 停机 fail-closed | `docs/chaos/C1-same-key-x10.txt`、`C2-diff-keys-x50.txt`、`C3-refund-replay-x10.txt`、`C4-redis-down.txt` | dev-only 端点绕过 LLM 直打工具层完整闸序，**换取并发场景确定性**（不是端到端聊天链路，如实声明） |
| 限流突发拦截 96.67%（放行 10） | `docs/jmeter/w7d3/s3a-rlon-burst.jtl`、`docs/jmeter/w7d3/w7d3-stress-matrix.md`、`docs/jmeter/w7d3/parse-apprlon.txt` | 同形对照：限流关同参 300 全过 → 差值全归因于闸；全局桶 10/s 精确核验 = 突发恰放 10 + 满秒 admit=10 |
| 双源对账（31 OK+470 RL vs 501 全 OK） | `docs/jmeter/w7d3/parse-apprlon.txt`、`parse-apprloff.txt` | 服务端 TurnMetrics 单行 JSON 逐条对上客户端 429 计数——数字可信的原因是两个独立来源互相印证 |
| 熔断 OPEN 短路 0.61s / 状态机全链 | `docs/resilience/w6d3-circuit-smoke.txt`、`docs/resilience/c7-circuit-break-w6d5.txt` | 真实故障注入（错 base-url 打到 :9）5 连败→OPEN→短路→20s 半开→回 OPEN；0.61s 含 HTTP 层，短路本身不发起连接 |
| 降级回复 40ms / 交易类只引导 | `src/main/java/com/shopagent/infra/resilience/RuleFallbackService.java`、README「稳定性设计」 | 4字/40ms 分片走 answer 流保打字机体感；没有 LLM 就没有二次确认链路，故降级态绝不执行交易 |
| 记忆 marker_hits=2 / 双实例 12 用户 10 过 2 拒 | `docs/resilience/w6d2-memory-smoke.txt`、`docs/resilience/dual-instance-w6d5.txt` | hits=2 = 重启后记忆从 Redis 回来（对照 W1 内存态失忆 hits=1）；双实例同 jar 共连一 Redis，429 分落两实例证全局桶分布式 |
| 冷 523.6/s → 热 3644.3/s（≈7.0 倍）、P95 162→23ms（≈7.0 倍） | `docs/jmeter/w7d3/s1-cold.jtl`、`s1-hot-sustained.jtl`（HTML 报告本地生成不入库：`jmeter -g <jtl> -o report-*`）、`docs/screenshots/w8d4-jmeter-cold.png`/`-hot.png` | 冷=重启清 L1 + DEL 目标 L2 键（含真 dogpile，穿透 50 样本 P50=160ms）；吞吐口径=JMeter 报告 Total.Throughput（样本数÷首末样本跨度）；单机 localhost 环境，不是集群数字。**W8D4 重算更正**：原记 344.8→3514.9（10.2 倍）为 D3 现算值，与入仓 JTL 不符（同名 .jtl 被复跑覆盖），已按入仓证据改口 7.0 倍 |
| 回源 28ms vs L1 命中 3-4ms | `docs/jmeter/w7d3/s1-warmmiss-probe.jtl` | 同 JVM evict 探针**隔离纯缓存贡献**——冷热 7.0 倍里混着并发与连接池因素，这条是干净口径 |
| 桩链路 96.5/s、P95=45ms | `docs/jmeter/w7d2/w7d2-baseline.md` | 量的是工程链路（限流闸→记忆→Graph→LLM 桩→工具→persist）**不是 LLM 能力**；与真 LLM 体感 ~1s/轮两个口径分开讲 |
| 真 DeepSeek 首 token P50=735ms、整轮 1045ms、usage 17/17、链路开销 <6% | `docs/jmeter/w7d3/parse-real.txt`（UTF-16 归档，需直读）、`docs/jmeter/w7d3/s3a-real.jtl` | n=17 小样本不做统计显著性声明；链路开销 = totalMs − llmMs，正是「限流该护在哪」的判据 |
| 单测 140 中 133 绿 + 7 跳 | `mvn test`（`src/test/` 24 个测试类） | 7 跳=需外部依赖的条件用例；infra/ 与 service/ 必有单测，tools/ 走冒烟清单（AGENTS.md 规则 6） |

## 7. 终审清单（T1.3 用户视角检验，脱稿复述讲不出即降级）

- [ ] bullet 1（ReAct + RAG）：能不看文档讲满 2 分钟，含「为什么不引 reranker 模型」
- [ ] bullet 2（幂等 + 锁）：能答「为什么返回首次结果而不是报错」「只上锁不幂等会怎样」
- [ ] bullet 3（限流熔断降级）：能答「令牌桶参数怎么定」「为什么熔断进程内而限流分布式」
- [ ] bullet 4（记忆分布式化）：能答「为什么 InMemory 不够」「Redis hash 为什么带序号 field」
- [ ] bullet 5（缓存 + 压测）：能答「为什么库存不进缓存」「7.0 倍这个数怎么取的、口径陷阱在哪（为什么不是当初记的 10.2 倍）」
- [ ] 抽查 3 个数字（建议：96.67%、3644.3/s、849.7ms）当场打开证据文件对上
- [ ] 每条 bullet 的「口径陷阱」能**主动**说出来，不等面试官追问

---

## 附一：与主计划 §0 映射表的对应

| §0 简历 bullet | 本文条号 | 追问深度点是否已备好 |
|---|---|---|
| ReAct + 工具调用 + RAG | 1 | 工具 description 设计 / 决策日志（TurnMetrics）/ 幻觉参数防御（W1D8 边界测试 7 例，README「安全设计」） |
| 幂等 + 分布式锁防重复扣款 | 2 | 幂等键设计（`IdempotentKeys`）/ 锁粒度 / 看门狗与锁超时 / 最终一致（四态语义） |
| LLM 限流熔断降级 | 3 | 令牌桶参数依据 / 熔断窗口 / 降级到什么（规则回复） |
| 会话记忆分布式化 | 4 | 为什么 InMemory 不够 / Redis 结构选型 / 无状态扩容实证 |
| 多级缓存 + 压测 | 5 | 缓存一致性（先更库再双删）/ 热点 key 打标 / 数字怎么来的（双源对账） |

## 附二：按周迭代节奏档案（W8D3，commit 全量校对 + 分段）

> 用途：面试讲「迭代节奏 / 怎么拆解一个大项目」的实证。校对口径=AGENTS.md 规则 1 + 主计划 §9：一个任务一个 commit，消息格式 `W{周}D{天}: 简述`。

| 段 | commit 条数 | 时间跨度 | 主题一行 |
|---|---|---|---|
| W1（含 W2 buffer） | 30 | 10-03 15:42 → 16:59 | 工程初始化 + ReAct 与 4 个查询工具 + SSE 流式 + 聊天页 + 边界安全测试实录（7 类攻击） |
| W3 | 6 | 10-03 18:22 → 19:49 | 交易安全核心：下单/退款/取消工具 + 幂等四态 + Redisson 锁 + `TradeGuard` 统一闸序 |
| W4 | 5 | 10-03 20:15 → 20:52 | 混沌测试 C1-C4 + 交易审计表与双出口埋点 + README 交易安全章节 |
| W5 | 9 | 10-03 21:06 → 10-04 08:50 | RAG 知识库（40 条 + 指纹幂等索引）+ 两级缓存 + ChatClient 直连迁 Graph + 分级降级矩阵 C5 |
| W6 | 6 | 10-04 10:12 → 14:18 | 稳定性三件套：Redisson 双层限流 + 熔断与规则降级 + 记忆 Redis 化 + 轮级结构化观测 + 双实例演证 |
| W7 | 10 | 10-04 16:14 → 10-05 16:38 | JMeter 四组脚本与双源对账 + H2→MySQL 双 profile + Docker Compose + C1-C7 全回归（钓出并修复 2 个真 bug） |
| W8 | 5（本条 D3 含，D4/D5 另增） | 10-05 16:50 → | 打磨收官：简历 bullets + 三层追问自测 + 局限应答 + commit 校对 + README 三图 + DoD 终验 |

**格式校对待账（D3 复核）**

- 全量 **70/70 合规、零违规**（校对时点=D3 本条 commit 之前；D0 基线 66/66 + D1/D2 新增 4 条，此后每条新 commit 仍按同一格式递进）；正则口径 `^W\d+D\d+: `（注意 `W1D10` 这类两位天号属合规，单数字正则会产生假违规——D0 基线用 `W\d+D\d+:` 无误）
- 抽样 14 条（每段首末各 1 条）`git show --stat` 核对**标题与实际改动相符**：如 `W6D0` 标题显式披露 pom.xml 新增 `resilience4j-circuitbreaker 2.4.0` 与「不带 spring-cloud starter」的理由，`W8D0` 标题列出的六个跨文件引用同步 = 实际改动文件集，无「标题撒谎」
- 违规项处置=只记录不改写（§2.1 裁决：历史已推送远端，rebase/reword 会毁掉 PUSH_LOG 与清单里的哈希引用链）

**⚠ 一处必须校准的说法（事实核查发现，别在面试里说错）**

「8 周项目」是**计划粒度**，不是耗时口径：全部 commit 落在 **2026-10-03 15:42 → 10-05 17:08 三个自然日**内（W5/W7 段各跨一个夜间）。

- **不要说**：「我花了 8 周开发这个项目」——被追问细节会露。
- **要说**：「我按 8 周路线图把项目拆成 8 个可验收的周清单（每周先冻结设计再写码、一个任务一个 commit），实际集中三天做完；三天能做完的原因是范围被砍单线锁死了——零微服务、零前端构建链、零独立 MQ，功能可以砍但幂等和锁不能砍。」
- **配套证据密度**（这句话的可信度来源）：133 个单测绿 + 混沌 C1-C7 全回归 + 压测双源对账 + 17 条踩坑实录 + 每个数字可打开证据文件（本文 §6）。

# W7D3 · 压测矩阵 + 数字落袋证据（T3.1-T3.4 全部完成）

> 日期：2026-10-05 · 环境：应用 `dev,mysql` @8080（默认限流=限流开实例；`--resilience.rate-limit.*=1000000` 覆盖=限流关实例）· 桩 LLM @18081 · MySQL 8.4.11 @13306 · redis-stack @6379 · JMeter 5.6.3 CLI · JDK 21
> 脚本沿用 D2：`s1-cache-detail.jmx` / `s2-trade-unique.jmx` / `s2-trade-samekey.jmx` / `s3a-chat-block.jmx`。**S3a body D3 起改为全请求唯一 userId/conversationId**（用户桶不绑定，全局桶单独受控；W6 用户桶 2/s 行为由 W6D1 burst 与 D2 sanity 覆盖）。
> runbook 执行：切限流组前后 `redis-cli --scan --pattern '*rlimit*' | xargs redis-cli DEL` 全删（D2 踩坑①），实测切组前清掉 903 个遗留键。

## T3.1 缓存冷/热两组（硬指标②）✅

| 组 | 状态 | 样本 | 吞吐/s | P50 | P95 | max |
|---|---|---|---|---|---|---|
| 冷组 | 重启清 L1 + DEL `cache:product:detail:2` | 100（50×2） | **523.6** | 159 | **162** | 162 |
| 热组同形 | 预热后同 50×2 形状 | 100 | **1351.4** | 47 | 49 | 49 |
| 热组持续 | 预热后 50×200 | 10000 | **3644.3** | 12 | **23** | 64 |

> **口径（W8D4 重算更正）**：吞吐 = JMeter HTML 报告 `Total.Throughput` = 样本数 ÷（末样本结束 − 首样本开始），任何人 `jmeter -g docs/jmeter/w7d3/s1-cold.jtl -o <out>` 可从入仓 JTL 复现同一数字。本表原写 344.8 / 529.1 / 3514.9（≈10.2 倍）为当日 D3 现算值，与**入仓后**的 JTL 不一致（同名 .jtl 被后续复跑覆盖，早期 JTL 未留存），且冷组 P50 一格误填了尾段命中样本的 P95（22ms）。W8 收口按入仓证据重算，硬指标②改口为 **≈7.0 倍**——与下方同 JVM 隔离探针的「纯缓存 ≈7 倍」互相印证，比原 10.2 倍更保守也更能扛追问。

- 冷组内部 cohort（JTL 按时间序）：**穿透 50 样本 P50=160ms / P95=162ms**，同轮尾段命中 50 样本 P50=16ms / P95=22ms；穿透窗 163ms 内 50 次穿透（无 singleflight，50 并发首次全打穿到 L2/库=真实 thundering herd）。整组均值因此落在 87.9ms、P50=159ms（半数样本仍在穿透）。
- **硬指标②口径：热稳态 3644.3/s 较冷组 523.6/s ≈ 7.0 倍；P95 162ms → 23ms ≈ 7.0 倍；同形状冷热比（1351.4 / 523.6）≈ 2.6 倍**——同形对比剔掉了样本数差异，是更保守的一档，两档一起报。

- 隔离口径（补充探针）：同 JVM 内 `update-product` 双删 evict 后单请求回源 **28ms**，随后 L1 命中 **3-4ms**（连续 5 样本 28/4/4/4/3）——纯缓存 miss→hit 贡献 ≈7 倍；冷组余量（160→28ms）为 JVM 冷启动 JIT/连接池预热，属设计口径「重启=冷」的固有成分，如实声明。
- 注意：S1 端点 `getStock` 每请求实时查库（§2.4 缓存边界铁律：交易正确性字段不走缓存），热稳态 12ms 中含一次 MySQL PK 查询——缓存只省 detail JSON 查询，这是口径里「缓存是加速器不是正确性来源」的数字体现。

## T3.2 限流关/开两组（硬指标①）✅

| 组 | 形态 | offered | 放行 | 429% | 放行 P50/P95 |
|---|---|---|---|---|---|
| 限流关 | 突发 10×30 | 300 | **300（100%）** | 0 | 36/54ms |
| 限流开 | 突发 10×30 | 300 | **10（3.33%）** | **96.67%** | 65/66ms |
| 限流关 | 持续 1×200 | ~52/s | 200 全过 | 0 | 17/24ms |
| 限流开 | 持续 1×200 | ~111/s | 20 | 90% | 25/48ms |

- **全局桶 10/s 精确放行核验**：突发组恰好放行 10（=首秒配额）；持续组逐秒直方图 `admitted: 部分秒2 / 满秒10 / 部分秒8`——满秒精确 10。
- 双源对账：限流开实例 501 轮（探针1+突发300+持续200）= **31 OK + 470 RATE_LIMITED**，与客户端 10+20 放行、290+180 拒逐条吻合（`parse-apprlon.txt`）；限流关实例 501 轮 **全部 OK**、零 RATE_LIMITED（`parse-apprloff.txt`）。
- 限流开突发放行 P50=65ms 略高于限流关 36ms：放行的 10 个请求同批并发处理（锁/连接竞争），非限流器开销（429 路径本身 avg 7ms 即返）。

## T3.3 交易 ramp-up 并发 ✅

- 前置：`UPDATE product SET stock=500 WHERE id=2`（fixture 重置，测试数据隔离声明：product 2 为压测专用档）。
- **异键 ramp-up**：100 线程 / ramp-up 10s / 2 环 = 200 下单，CSV-b 段（jload501-700，500 行专用段防跨进程重放），0% 错误，吞吐 20.4/s（ramp 爬升节奏内），Avg=41.9ms、P95=74ms。**库存 500 → 300，delta=200 与成功单数精确相等，零超卖**。
- **同键 ×N 并发**：100 线程同幂等键（jsame3/jsame3-conv，全新键），0% 错误，**orderCount=1、库存 300→299 仅扣 1**——幂等 under load。Avg=849.7ms、P95=1469ms、吞吐 65.4/s（100 并发在用户锁上串行排队，排队时延随并发线性增长：D2 t50=495ms → D3 t100=849ms，正确性零妥协的代价曲线）。
- audit 抽查：jload501 单条记录 resultCode=0。

## T3.4 真 LLM 小样本 ✅（真 DeepSeek 10-20 请求）

用户在会话提供 Key（按 AGENTS.md 规则 4 仅经进程环境变量注入，未入仓未落盘未入证据）；应用不带 `DEEPSEEK_BASE_URL` 覆盖走真 `https://api.deepseek.com`。

| 指标（服务端 TurnMetrics，n=17 含探针） | P50 | P95 | max |
|---|---|---|---|
| **首 token（firstTokenMs）** | **735ms** | **1041ms** | 1041ms |
| **整轮（totalMs）** | **1045ms** | **1524ms** | 1524ms |
| LLM 段（llmMs） | 1032ms | 1461ms | 1461ms |
| 客户端 elapsed（JMeter JTL，n=16） | 995ms | 1341ms | 1353ms |

- **usage 真回传验证成功：usageHits=17/17**（stream-usage/include_usage 生效），promptTokens=42437（≈2494/轮=系统提示+记忆窗口）、completionTokens=1388（≈82/轮，短问短答）。
- **两个口径分开陈述（DoD 原文）**：桩链路吞吐 96.5/s（工程链路能力）≠ 真 LLM 体感 ~1s/轮（生成能力）；链路自身开销 = totalMs−llmMs ≈ 13-63ms，占比 <6%——瓶颈在 LLM 生成，Agent 框架（限流闸→记忆→Graph→persist）开销可忽略，这正是「护盾」限流打在真正瓶颈前的意义。
- 0% 错误；全局桶 10/s 对 ~0.4/s 的真实 offered 无约束（限流语义与真 LLM 延迟正交）。
- 复现 runbook（Key 只走环境变量）：`DEEPSEEK_API_KEY=<Key> java -jar ... --spring.profiles.active=dev,mysql` → `jmeter.bat -n -t docs/jmeter/s3a-chat-block.jmx -Jthreads=2 -Jloops=8 -Jrampup=10 -Jmsg=hello`（S3a message 已属性化 `-Jmsg`，默认 hello-stub 兼容桩组）→ `parse-turnmetrics.ps1`。

## 运行留痕

JTL：`s1-cold / s1-hot-shape / s1-hot-sustained / s1-warmmiss-probe / s2u-ramp / s2k-t100 / s3a-rlon-burst / s3a-rlon-sustained / s3a-rloff-burst / s3a-rloff-sustained`（.jtl，本目录，**入仓**）+ HTML 报告 `report-w7d3-*`（**gitignore 口径本地生成，不入库**——复现命令 `jmeter -g docs/jmeter/w7d3/<name>.jtl -o docs/jmeter/report-<name>`）+ 解析 `parse-apprlon.txt / parse-apprloff.txt`。README「压测与部署」的冷热报告页截图见 `docs/screenshots/w8d4-jmeter-cold.png` / `w8d4-jmeter-hot.png`。

**测试数据漂移记录**（D4/D5 演示须知）：product 2 库存 299（压测后）、价格 19.90；product 1 价格 119.00（D1 冒烟改价遗留）；orders 表含 jload001-700 / jsame1-3 / jsmoke1 合成用户订单。

# W7D3 · 压测矩阵 + 数字落袋证据（T3.1-T3.3，T3.4 阻塞待密钥）

> 日期：2026-10-05 · 环境：应用 `dev,mysql` @8080（默认限流=限流开实例；`--resilience.rate-limit.*=1000000` 覆盖=限流关实例）· 桩 LLM @18081 · MySQL 8.4.11 @13306 · redis-stack @6379 · JMeter 5.6.3 CLI · JDK 21
> 脚本沿用 D2：`s1-cache-detail.jmx` / `s2-trade-unique.jmx` / `s2-trade-samekey.jmx` / `s3a-chat-block.jmx`。**S3a body D3 起改为全请求唯一 userId/conversationId**（用户桶不绑定，全局桶单独受控；W6 用户桶 2/s 行为由 W6D1 burst 与 D2 sanity 覆盖）。
> runbook 执行：切限流组前后 `redis-cli --scan --pattern '*rlimit*' | xargs redis-cli DEL` 全删（D2 踩坑①），实测切组前清掉 903 个遗留键。

## T3.1 缓存冷/热两组（硬指标②）✅

| 组 | 状态 | 样本 | 吞吐/s | P50 | P95 | max |
|---|---|---|---|---|---|---|
| 冷组 | 重启清 L1 + DEL `cache:product:detail:2` | 100（50×2） | **344.8** | 22 | **162** | 162 |
| 热组同形 | 预热后同 50×2 形状 | 100 | **529.1** | 20 | 49 | 49 |
| 热组持续 | 预热后 50×200 | 10000 | **3514.9** | 12 | **23** | 64 |

- 冷组内部 cohort（JTL 按时间序前 54 样本=热点计数实测穿透数）：**穿透样本 P50=160ms / P95=162ms**，同轮尾段 L1 命中已降至 P50=16ms；冷窗 166ms 内 54 次穿透（无 singleflight，50 并发首次全打穿到 L2/库=真实 thundering herd）。
- **硬指标②口径：热稳态 3514.9/s 较冷组 344.8/s ≈ 10.2 倍；P95 162ms → 23ms ≈ 7 倍**。
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
- **异键 ramp-up**：100 线程 / ramp-up 10s / 2 环 = 200 下单，CSV-b 段（jload501-700，500 行专用段防跨进程重放），0% 错误，吞吐 19.9/s（ramp 爬升节奏内），Avg=41ms。**库存 500 → 300，delta=200 与成功单数精确相等，零超卖**。
- **同键 ×N 并发**：100 线程同幂等键（jsame3/jsame3-conv，全新键），0% 错误，**orderCount=1、库存 300→299 仅扣 1**——幂等 under load。Avg=849ms（100 并发在用户锁上串行排队，排队时延随并发线性增长：D2 t50=495ms → D3 t100=849ms，正确性零妥协的代价曲线）。
- audit 抽查：jload501 单条记录 resultCode=0。

## T3.4 真 LLM 小样本 ⏸ 阻塞待密钥

`DEEPSEEK_API_KEY` 会话内、Windows User/Machine 级环境变量均未设置（AGENTS.md 规则 4：密钥只走环境变量，不入仓不入对话）。runbook 已备好，密钥到位后 ~10 分钟可出数：

```
# 1. 起应用（不带 DEEPSEEK_BASE_URL 覆盖，走真 https://api.deepseek.com；密钥走环境变量）
DEEPSEEK_API_KEY=<真实Key> MYSQL_PORT=13306 java -jar target/shopagent-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev,mysql --logging.file.name=logs/jm-w7d3-real.log
# 2. JMeter 小样本（真 LLM 延迟秒级，无需限流参数调整；全局桶 10/s 对 10-20 请求无压力）
jmeter.bat -n -t docs/jmeter/s3a-chat-block.jmx -Jthreads=2 -Jloops=8 -Jrampup=10 \
  -l docs/jmeter/w7d3/s3a-real.jtl -e -o docs/jmeter/report-w7d3-real
# 3. 取数：parse-turnmetrics.ps1 → firstTokenMs/totalMs P50/P95 + usageHits（stream-usage 真回传验证）
```

## 运行留痕

JTL：`s1-cold / s1-hot-shape / s1-hot-sustained / s1-warmmiss-probe / s2u-ramp / s2k-t100 / s3a-rlon-burst / s3a-rlon-sustained / s3a-rloff-burst / s3a-rloff-sustained`（.jtl，本目录）+ HTML 报告 `report-w7d3-*`（gitignore 口径本地生成）+ 解析 `parse-apprlon.txt / parse-apprloff.txt`。

**测试数据漂移记录**（D4/D5 演示须知）：product 2 库存 299（压测后）、价格 19.90；product 1 价格 119.00（D1 冒烟改价遗留）；orders 表含 jload001-700 / jsame1-3 / jsmoke1 合成用户订单。

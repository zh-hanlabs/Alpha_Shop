# W7D2 · JMeter 脚手架 + 基线跑批证据（T2.1-T2.3）

> 日期：2026-10-05 · 环境：应用 `dev,mysql` @8080（jar 13:59 构建）· 桩 LLM `w6d1-llm-stub.jsh` @18081（DEEPSEEK_BASE_URL 指桩）· MySQL 8.4.11 容器 @13306 · redis-stack 7.4.0-v8 @6379 · JMeter 5.6.3 CLI（`D:\dev\apache-jmeter-5.6.3`，repo 外）· JDK 21
> 脚本：`s1-cache-detail.jmx` / `s2-trade-unique.jmx` / `s2-trade-samekey.jmx` / `s3a-chat-block.jmx` + `s3b-sse-burst.ps1`（SSE 插件兜底，w7d0 F4）+ `parse-turnmetrics.ps1`（服务端取数）+ `data/s2-unique-users.csv`（500 唯一用户）
> HTML 报告：`docs/jmeter/report-w7d2-*`（gitignore `report-*` 口径，本地生成不进仓）；JTL 与解析证据在本目录进仓。

## 签名现场核对（T2.1 预判项，一处清单笔误修正）

任务清单初稿写的 S2 端点 `POST /api/dev/chaos/place-order` 系笔误，实际为 **`POST /api/dev/chaos/place`**（DevChaosController），body `{userId, productId, quantity, conversationId, message}`。S1=GET `/api/dev/cache/product-detail?productId=`；S3a=POST `/api/chat`；S3b=POST `/api/chat/stream`（SSE）。清单 T2.1 已同步修正。

## 基线数字（客户端 JTL 口径，0% 错误组）

| 跑批 | 形态 | 样本 | Err% | 吞吐/s | P50 | P95 | max |
|---|---|---|---|---|---|---|---|
| S1 缓存详情 t10×10 | 热缓存（首样本 L2 miss 203ms） | 100 | 0 | 99.3 | 8 | 12 | 203 |
| S1 缓存详情 t50×4 | 热缓存 | 200 | 0 | 100.0 | 7 | 11 | 32 |
| S2a 异键下单 t10×5 | CSV 唯一 userId，qty=1 | 50 | 0 | 36.2 | 97 | 122 | 174 |
| S2a 异键下单 t50×2 | 50 新单 + 50 跨进程重放（见观察③） | 100 | 0 | 61.1 | 71 | 325 | 343 |
| S2b 同键重放 t50×1 | 50 并发同幂等键 | 50 | 0 | 49.5 | 492 | 839 | 884 |
| S3a 聊天阻塞 t50×2 | 桩 LLM，限流关 | 100 | 0 | 96.5 | 20 | 45 | 57 |

限流开组（默认参数 用户 2/1s + 全局 10/1s）：S3a t10×10 → **90% 429**（全局桶 10/s 放行 10、用户桶互不挤占）；S3b SSE 12 用户×2 → **10 过闸 14 限流话术，done 收尾 24/24**。S3b 干净组 t10×3：30/30 done、errEvent=0、ttfb P50=14ms/P95=61ms、answerEvents=2/请求（桩固定两片）。

同键组 P50=492ms 的含义：50 并发同键在 Redisson 用户锁上排队，首个执行落库、其余拿幂等首次结果——**延迟是锁排队的代价，正确性零妥协**（面试素材）。

## 双源对账（JMeter 客户端 ↔ TurnMetrics 单行 JSON 日志，解析脚本 parse-turnmetrics.ps1）

- **应用 #1（限流开）**：服务端 126 轮 = 22 OK + 104 RATE_LIMITED（`parse-app1-rl.txt`）。客户端：S3a 10×200+90×429、S3b 10 过 14 限、启动冒烟 2 轮 → OK 22 ✓ RL 104（90+14）✓。两个场景都**精确放行 10 个**=全局桶 10/s。
- **应用 #2（限流关）**：服务端 290 轮 = 160 OK + 130 RATE_LIMITED（`parse-app2-base.txt`）。客户端逐条对上：S3a 污染轮 10 OK+90 RL、S3b 两次存量桶残留组各 10 OK+20 RL（踩坑①时间线）、干净 S3a 100 OK、干净 S3b 30 OK → 160/130 ✓。token 口径：usageHits=0/290（桩不回 usage=N/A，answerChars 代理 3680）、toolCalls 空（桩不触发工具）。
- **S2 业务侧对账**：product 2 库存 199 → 98。S2a 150 样本 → 100 真实新单（库存 -100）+ 50 跨进程幂等重放；S2b 50 样本 → **仅 1 单落库**（库存 -1）。审计表实证：jload001 两条 audit 同一 idempotentKey、orderCount=1（重放返首次）；jload060 一条；jsame orderCount=1。**零超卖、账目精确吻合**。

## 踩坑实录（D2 新增 3 条）

1. **调限流参数必须清存量桶**：Redisson `trySetRate` 只在桶不存在时生效（try 语义），重启应用改 `resilience.rate-limit.*` 不影响 Redis 里活着的桶；且 RRateLimiter 内部键带 hash-tag（`{rlimit:chat:global}:permits/:value`），只删主键 `rlimit:*` 扫不到内部键——半删状态（配置已新、计数残留）行为仍是旧限流值。正确清法：`--scan --pattern '*rlimit*'` 全删。实测：只清主键后 S3b 仍 20/30 被拒，全删后 0 拒。→ **D3 T3.2 限流关/开两组切换的 runbook 步骤**。
2. **JMeter 遇已存在 JTL 拒绝启动**：`-l` 文件存在时报错退出（"Starting standalone test" 都不打印），输出被 grep 过滤后表现为「静默无 summary」。跑批前必须删 JTL 与报告目录（本次 s3a 重试即中招，`s3a-t50-stale-buckets-pitfall.jtl` 留证污染轮数据）。
3. **JTL 是带引号多行字段的 CSV**：429 断言失败消息含换行，`wc -l` 会把一条样本拆成多行——按行数推样本数会得出 641>100 的假象；正确按 CSV 解析（200 组无断言消息不受影响）。

### 观察项（非踩坑，D3 取数须知）

- **CSV 每进程从头读**：S2a 两轮共用一份 CSV，第二轮前 50 个 userId 与第一轮重复 → 命中跨进程幂等重放（返回首次结果，行为正确且是幂等跨进程生效的活证），但「样本数 ≠ 新订单数」。D3 交易矩阵若需足量新单：分段使用 500 行（jload 剩余 400 行）或换新 CSV，并按 audit/stats 对账。
- **S1 首样本 L2 miss**：t10 组 max=203ms 即冷启动回源，热组（t50）max=32ms——D3 T3.1 冷/热对照组即以此为受控实验（冷=重启清 L1+DEL 目标 L2 键，严禁 FLUSHDB）。

## 运行清单（复现口径）

```
# 桩 LLM
jshell -q docs/resilience/w6d1-llm-stub.jsh   # 18081, 15min 自杀需重起
# 应用（限流开=默认；限流关=追加两个覆盖参数，跑前先清 '*rlimit*'）
DEEPSEEK_BASE_URL=http://127.0.0.1:18081 MYSQL_PORT=13306 java -jar target/shopagent-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev,mysql --logging.file.name=logs/<场景>.log \
  [--resilience.rate-limit.user-rate=1000000 --resilience.rate-limit.global-rate=1000000]
# 跑批（每次先删 JTL 与报告目录）
jmeter.bat -n -t docs/jmeter/<脚本>.jmx -Jthreads=N -Jloops=M -l docs/jmeter/w7d2/<名>.jtl -e -o docs/jmeter/report-w7d2-<名>
# 服务端取数
powershell -File docs/jmeter/parse-turnmetrics.ps1 -LogPath logs/<场景>.log -Label <名> -OutFile docs/jmeter/w7d2/parse-<名>.txt
```

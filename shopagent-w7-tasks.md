# ShopAgent · W7 数字与部署开发任务清单

> 目标版本：一周（D0-D5 六个工作日）：JMeter 压测（简历数字收口）+ H2→MySQL 8 双 profile 切换 + Docker Compose 一键部署
> 原则：W7 是把前六周工程能力兑换成简历数字的收口周——主计划 §11（项目 DoD）两项硬指标「限流前后对比」「缓存命中提升的 QPS」必须落袋；**不破坏 W3 交易安全 / W5 RAG·缓存 / W6 稳定性的既有语义**
> 配套：主计划 §7（任务依据，2026-10-04 随本清单新增，原 §7-§11 顺移为 §8-§12）· AGENTS.md（开发规则）· shopagent-w6-tasks.md §2.4（观测口径=压测取数源，勿重造）· shopagent-w3w4-tasks.md §2（幂等/锁口径勿动）· shopagent-w5-tasks.md §2（缓存边界与一致性语义勿动）

---

## 一、验收标准（Definition of Done）

一周结束时，能完成这条演示链路：

> `docker compose up -d` 一条命令拉起 Redis Stack + MySQL 8（healthcheck 就绪；redis-stack 本地镜像按目录 README 一次性构建）→ 应用以 `dev,mysql` 双 profile 起 → 查/问/办全链路走通：交易数据在 MySQL、向量索引指纹幂等自动重建（W5 机制）、两级缓存命中、中文零乱码。
> JMeter 打商品详情冷/热两组：热缓存较冷回源 QPS 提升 N 倍，P95 对比一目了然（DoD 硬指标②）。
> JMeter 打交易下单 ramp-up 并发：异键零超卖、库存精确扣减；同键 ×N 重放全返首次（幂等正确性 under load）。
> JMeter 打聊天链路（桩 LLM）：限流关=链路吞吐基线；限流开=全局桶精确 10 QPS 放行、超出部分 429，护盾效果可量化（DoD 硬指标①）；SSE 插件压流式端点：并发连接保持数、事件时延、done 收尾率。
> 真 DeepSeek 小样本 10-20 请求：真实首 token / 整轮延迟参照——链路吞吐与真实体感两个口径分开陈述。
> README「压测与部署」章节：数字矩阵 + 取数口径声明 + 克隆三步启动；主计划 W7 ✅。

硬性指标：

- [ ] MySQL 双 profile：`dev,mysql` 激活下九工具 + 下单/退款全闸序 + 知识检索 + 两级缓存全链路跑通，utf8mb4 中文零乱码；dev 默认 H2 clone 即跑与单测口径（133 绿+7 跳）零动
- [ ] 混沌 C1/C2 在 MySQL 上回归 PASS（幂等/锁/零超卖跨库不变）
- [ ] JMeter 四组脚本：S1 缓存详情链路 / S2 交易下单并发 / S3a 聊天阻塞端点（桩 LLM）/ S3b SSE 流式（插件）——JTL + HTML 报告证据存档 `docs/jmeter/`
- [ ] 限流前后对比数字落袋：关/开两组吞吐、429 比例、P95，全局桶 10/s 精确放行核验（主计划 §11 DoD 硬指标①）
- [ ] 缓存命中提升数字落袋：冷（回源 DB）vs 热（L1/L2 命中）QPS/P95 对比（主计划 §11 DoD 硬指标②）
- [ ] 交易并发压测：ramp-up 异键并发零超卖、库存精确扣减；同键 ×N 并发全返首次
- [ ] 真 LLM 小样本延迟参照（真 DeepSeek 10-20 请求，首 token/整轮 P50/P95）+「链路吞吐 ≠ LLM 能力」口径声明进 README
- [ ] 取数双源：JMeter JTL/HTML（客户端）+ TurnMetrics 单行 JSON 日志解析脚本（服务端 outcome/llmMs/工具分布），双源口径对齐写清
- [ ] Docker Compose 一条命令拉起全部依赖（redis-stack 本地镜像 + MySQL 8 + healthcheck + .env 注入），README 克隆后三步启动
- [ ] README「压测与部署」章节 + 架构图刷新 + 踩坑实录补条 + 混沌 C1-C7 回归 PASS + 主计划 W7 ✅ + commit `W7D{n}` 校对

---

## 二、设计定稿（2026-10-04 用户确认冻结：§2.1 双 profile / §2.2 桩为主+真 LLM 小样本+SSE 插件 / §2.3 Compose 依赖编排；开发中改动需先改这里）

### 2.1 H2→MySQL 8【已定稿：双 profile 并存】

- **决策（ADR D8）**：dev 默认与单测保持 H2（clone 即跑 + @SpringBootTest 隔离库口径零动），新增 `application-mysql.yml`；压测与 Compose 部署用 `--spring.profiles.active=dev,mysql` 双激活（dev 端点 + MySQL 数据源）
- **schema 分平台**：`spring.sql.init.platform` 机制——`schema-mysql.sql` 单独适配。已预核必改点：`CREATE INDEX IF NOT EXISTS`（trade_audit_log 两处）MySQL 8 不支持（H2 MODE=MySQL 容忍）；其余 AUTO_INCREMENT / TEXT / DECIMAL / UNIQUE / TIMESTAMP DEFAULT 平移兼容，D0 F3 逐行复核收口；data.sql 相对时间偏移语法（TIMESTAMPADD/INTERVAL）一并复核
- **驱动**：`com.mysql:mysql-connector-j`（Boot 3.5.16 BOM 管理版本，pom 不写死），runtime scope
- **版本**：MySQL 8.4 LTS 优先、8.0 兜底——镜像获取探路（F2）结果定版，均在主计划 §1「MySQL 8」锁定范围
- **连接与凭据**：utf8mb4 连接参数（characterEncoding/connectionCollation 按 D1 实测定）防中文乱码（W1 data.sql GBK 踩坑前科）；凭据走 `${MYSQL_USERNAME}/${MYSQL_PASSWORD}` 环境变量占位（.env.example 补样例），与密钥安全惯例一致
- **连接池**：HikariCP 起步默认（maximum-pool-size=10），压测若显示 DB 等待显著再显式调大并记录理由——不为数字好看预先调优
- **语义红线**：双 profile 只动连接层；幂等键、锁键、缓存键、观测口径零漂移；C1/C2 在 MySQL 上回归通过才算切换成功

### 2.2 压测口径【已定稿：桩 LLM 为主 + 真 LLM 小样本 + SSE 插件压流式】

- **主数字=链路吞吐**：聊天压测打本地桩 LLM（复用 w6d1-llm-stub.jsh / w6d4-llm-stub-toolcall.jsh，按 F5 并发化改造，DEEPSEEK_BASE_URL 指桩）——确定性、免费、无账号配额污染。README 口径声明：**数字=Agent 工程链路吞吐（限流闸→记忆读→Graph→LLM 桩→工具→persist 全链路），非 LLM 生成能力**
- **真 LLM 小样本**：真 DeepSeek 10-20 请求，报首 token / 整轮 P50/P95 作真实体感参照——面试话术「链路吞吐与真实体感两个口径」
- **SSE 流式组（用户已裁：装插件）**：JMeter SSE 插件压 `/api/chat/stream`（POST + JSON body，插件采样能力 F4 探路核）；指标=并发连接保持数、事件时延、done 收尾率、限流话术率；**吞吐主口径仍走 /api/chat 阻塞端点**（同一 Graph 链路，长连接 QPS 语义不同，两组数字分开陈述）；插件获取失败兜底=PS5 脚本抽样（W6 burst.ps1 模式），口径声明降级记录不阻塞其他数字
- **对照组设计**：
  - 限流关 = 启动参数覆盖 `resilience.rate-limit.user-rate/global-rate` 为极大值（不改代码）；限流开 = 默认值（用户桶 2/1s + 全局桶 10/1s，W6 §2.1 定稿）
  - 缓存冷 = 重启应用清 L1 + **仅 DEL 目标商品 L2 缓存键**（严禁 FLUSHDB——幂等键/向量索引/会话记忆同库共存），首轮回源计冷；缓存热 = 预热后全命中
  - 交易异键 = 参数化 userId/orderNo 唯一（JMeter CSV/UUID）；交易同键 = 同参数并发重放验「全返首次」
- **取数双源**：JMeter JTL + HTML 报告（`-g` 生成）= 客户端吞吐/分位；TurnMetrics 单行 JSON 日志解析脚本（PS5，UTF-8 BOM 惯例）= 服务端 outcome 分布 / llmMs 分位 / 工具调用分布。环形缓冲仅 100 轮，压测取数以日志文件为准（W6D4 既有设计）；压测运行时应用日志落盘（logging.file.name）
- **压测入口与限流关系（勿混淆）**：S1 缓存（/api/dev/cache/product-detail）与 S2 交易（/api/dev/chaos/place-order）为 dev 端点不挂限流（W6 §2.1），可打满；S3 聊天两入口都过限流闸——它就是「限流前后对比」的实验对象本身

### 2.3 Docker Compose【已定稿：基线只编排依赖】

- **编排范围**：compose.yml 只管 redis-stack + MySQL 8 + healthcheck + depends_on 就绪序 + .env 密钥注入；app 宿主机跑（压测本就打本地 app）
- **redis-stack**：本地自建镜像 `shopagent/redis-stack-server:7.4.0-v8`——compose 挂 `build: docker/redis-stack-server`（deb 按 该目录 README 一次性下载，gitignore 不入仓），**禁止 pull**
- **MySQL**：F2 探路定版镜像（pull 直得或官网直连下包自建，W5D0 playbook 复用）；凭据经 .env 注入
- **一键启动链路**：`docker compose up -d` → 等 healthcheck → 应用 `dev,mysql` 起 → 全链路演示一轮（知识库索引指纹幂等重建自动生效，W5 机制零改动）；README 克隆三步 = ①下 deb 构建 redis-stack 镜像 ②docker compose up -d ③起应用
- **app 容器化=加分项非欠账**：视 F2/JRE 基镜像探路结果裁；若做=mvn package 后 COPY jar 零网络 Dockerfile（W5D0 playbook 复用），不引入 buildpacks（需网络）

---

## 三、依赖报备（对照主计划 §1 禁止清单）

| 依赖 | 版本 | 用途 | 合规依据 |
|---|---|---|---|
| `com.mysql:mysql-connector-j` | **9.7.0**（Boot 3.5.16 BOM 管理版，pom 不写死，dependency:tree 已验） | MySQL 8 JDBC 驱动 | 主计划 §1 预批「H2 → MySQL 8，W7 部署切 MySQL」；runtime scope |
| JMeter + SSE 采样插件 | **JMeter 5.6.3 定版（D0 探路）**；SSE 插件 `jmeter-sse-sampler` 2.0.1 探路结论=无 POST body 能力不符，走 PS5 兜底 | 压测执行器（外部工具，不入 pom） | 主计划 §1「压测 JMeter（W7）」预批；插件属压测工具链非项目依赖 |

---

## 四、逐日任务

### D0：设计定稿冻结 + 探路事实核查（半天-1 天）

- [x] T0.1 四项裁决冻结（§2.1 双 profile / §2.2 桩为主+真 LLM 小样本+SSE 插件 / §2.3 Compose 依赖编排），本清单落盘 commit + 主计划同步（新增 §7 W7 章节、原 §7-§11 顺移 §8-§12、§3 状态行 🔄、配套文件行、ADR 补 D8、AGENTS.md 决策引用 §8→§9）
- [x] T0.2 F1 JMeter 下载安装（官方 dlcdn 直下成功，无需镜像）+ CLI 冒烟：5.6.3，6 samples Err 0.00%，JTL+HTML 报告生成（JDK21 可驱动；HTML 报告目录 3.1M gitignore，真跑批报告 D2/D3 再归档口径见 docs/deploy/w7d0-fact-checks.md F1）
- [x] T0.3 F2 MySQL 镜像探路：**`docker pull mysql:8.4` 直接连成**（digest 6ea90827…，W5D0 断网前科不复现）→ 版本定版 8.4 LTS，compose 直用 image 无 build 段；本地无 JRE 基镜像（app 容器化加分项届时再探）
- [x] T0.4 F3 方言逐行复核收口（docs/deploy/w7d0-fact-checks.md F3）：schema 5 表仅两处 `CREATE INDEX IF NOT EXISTS` 必改；data.sql 两处必改（FORMATDATETIME→DATE_FORMAT 换格式串、`||`→CONCAT——MySQL 默认无 PIPES_AS_CONCAT 是逻辑或）；TIMESTAMPADD 原样兼容；**两个 init 隐藏坑**——`sql.init.mode` 默认 embedded 对 MySQL 不执行（mysql profile 必须 always）+ CREATE INDEX 无幂等语法（continue-on-error:true 兜底 + 显式 schema/data-locations 指向 -mysql 文件防双跑）
- [x] T0.5 F4 SSE 插件探路 → **走兜底**：候选 `io.github.cuneytcakir:jmeter-sse-sampler` 2.0.1（repo1 实存）解剖后仅 URL/DURATION/HEADERS 三属性、无 POST body——撑不起 POST+JSON；按 §2.2 预设：吞吐主口径走核心采样器压阻塞端点，SSE 组 PS5 抽样报事件时延/done 收尾率，口径声明降级不阻塞
- [x] T0.6 F5 桩并发核查：既有桩（16 线程池）50 并发全 200，P50=1.5ms/P95=2.6ms/max=28ms——**无需线程池化改造**（T2.2 改判为维持现状；梯度到 100 并发时再复核，风险预案保留）
- [x] T0.7 依赖落地：pom + mysql-connector-j（runtime，BOM 管理）→ dependency:tree 实测 **9.7.0** 入树 BUILD SUCCESS；dev 默认 H2 零动

### D1：H2→MySQL 双 profile

- [ ] T1.1 `application-mysql.yml`（datasource + utf8mb4 连接参数 + `${MYSQL_USERNAME}/${MYSQL_PASSWORD}` 占位）+ `schema-mysql.sql`（F3 差异清单落地）+ `spring.sql.init.platform` 接线；单测/dev 默认 H2 零动确认（application.yml 不改）
- [ ] T1.2 MySQL 容器起 → `dev,mysql` 双激活全链路冒烟：九工具 + 下单/退款全闸序 + 知识检索（真 embedding API）+ 两级缓存命中 + 改价双删链 + 中文零乱码核验（data.sql 全量抽查）
- [ ] T1.3 混沌 C1/C2 在 MySQL 上回归（同键 10/10 返首次、异键并发零超卖库存精确）+ 单测 133 绿+7 跳零动 + dev 默认 H2 clone 即跑验证

### D2：JMeter 脚手架 + 基线

- [ ] T2.1 四组 JMX 脚本（参数化 userId/orderNo，请求体对齐控制器实际签名 D2 现场核对）：S1 缓存详情（GET /api/dev/cache/product-detail）/ S2 交易下单（POST /api/dev/chaos/place-order，异键 CSV + 同键两组）/ S3a 聊天阻塞（POST /api/chat 桩 LLM）/ S3b SSE 流式（插件压 /api/chat/stream）
- [ ] T2.2 桩 LLM 线程池化（F5 结果落地）+ 应用日志落盘（logging.file.name）+ TurnMetrics 单行 JSON 解析脚本（PS5 UTF-8 BOM：outcome 分布 / llmMs 分位 / 工具调用分布汇总）
- [ ] T2.3 基线跑批（梯度 10→50 短窗口）：四脚本跑通 + JTL/HTML 报告生成 + 解析脚本与 JMeter 客户端数字双源对账一致 + 证据落 `docs/jmeter/`

### D3：压测矩阵 + 数字落袋

- [ ] T3.1 缓存冷/热两组（硬指标②）：冷=重启应用清 L1 + 仅 DEL 目标 L2 键首轮回源；热=预热全命中——QPS/P95 提升数字
- [ ] T3.2 限流关/开两组（硬指标①）：聊天链路（桩 LLM）吞吐 / 429 比例 / P95 对比 + 全局桶 10/s 精确放行核验
- [ ] T3.3 交易 ramp-up 并发：异键零超卖库存精确扣减 + 同键 ×N 并发全返首次（幂等 under load）
- [ ] T3.4 真 LLM 小样本：真 DeepSeek 10-20 请求首 token/整轮 P50/P95 + 数字回填本清单 §一 + JTL/HTML/日志解析三件证据存档

### D4：Docker Compose

- [ ] T4.1 compose.yml（redis-stack 本地镜像 build + MySQL 8 + healthcheck + depends_on + .env 注入）+ .env.example 扩展（MySQL 凭据样例）+ 一键启动 README 草稿段（克隆三步）
- [ ] T4.2 部署冒烟：`docker compose up -d` → healthcheck 就绪 → 应用 `dev,mysql` 连容器中间件 → 查/问/办演示一轮 + 知识库索引指纹重建验证 + 混沌 C2 抽查（容器 MySQL 上零超卖）
- [ ] T4.3 （加分，按 T0.3 探路结果裁）app 镜像：mvn package + COPY jar 零网络 Dockerfile 入编排，compose 内全栈演示一轮

### D5：buffer + 收尾

- [ ] T5.1 混沌回归 C1-C7 全 PASS（W7 动了数据源与部署形态，重点重验 C1/C2/C4）+ mvn test 全绿
- [ ] T5.2 README「压测与部署」章节：压测矩阵数字表（限流前后 / 缓存冷热 / 交易并发 / SSE 流式）+ 取数口径声明（桩=链路吞吐非 LLM 能力、环形缓冲 vs 日志文件、双源对齐）+ 克隆三步启动 + 面试三层追问预演（数字怎么来的 / 瓶颈在哪 / 为什么桩测）+ 已知局限（单机 localhost 压测口径 / HikariCP 起步默认 / userId 可伪造维持 W6 记录）+ 架构图刷新（MySQL / Compose / JMeter 边界）+ 踩坑实录补条
- [ ] T5.3 commit 校对（W7D0-D5 全部 `W7D{n}:` 格式）+ 硬性指标逐项核销 + 主计划 §3 W7 状态 ✅
- [ ] （顺带清欠账）真 Key 环境补跑 W6D5 记录的 W1 冒烟清单 LLM 行为项（依赖真 LLM 的条目，口径已记录）

---

## 五、测试口径（沿用 AGENTS.md）

- 单测保持 H2 隔离库零动（@SpringBootTest 覆盖 datasource.url 惯例维持，防上下文共享污染）；MySQL 切换是连接层配置不新增单测目标，靠冒烟 + 混沌回归兜底
- JMeter JMX / PS5 取数脚本 / compose / Dockerfile 靠冒烟清单手工验证（同 tools/ 口径），证据统一 `docs/jmeter/` 与 `docs/deploy/`
- 压测跑批与真 Redis / 真 LLM 冒烟单独执行不进单测；PS5 脚本一律 UTF-8 with BOM（W6 踩坑前科）

## 六、风险与砍单线

1. **Docker Hub 断网前科**（W5D0 四连坑）→ MySQL / JRE 基镜像 F2 探路先行；playbook=官网直连下包自建镜像（redis-stack 先例）；拿不到则 app 容器化裁掉（compose 保中间件、app 宿主机跑），**压测与 DoD 数字不受影响**
2. **SSE 插件获取或能力不符**（需支持 POST + JSON body 的流式采样）→ F4 探路；失败兜底=PS5 抽样验证流式端点，口径声明降级记录，不阻塞其他三组数字
3. **桩 LLM 单线程瓶颈扭曲聊天吞吐** → F5 线程池化 + 口径声明；压测若发现桩先饱和则记瓶颈分析（面试素材）
4. **MySQL 方言坑**（索引语句 / 相对时间 INTERVAL / utf8mb4 中文 / 表名大小写）→ F3 D0 逐行收口 + D1 全量数据中文抽查（W1 GBK 踩坑前科）
5. **压测打挂本机容器或 Redis** → 梯度升压（10→50→100）先短窗后长窗，DevObsController stats 与 docker stats 同步观测
6. **砍单线**：不上 Micrometer/Prometheus/Grafana（维持 W6 裁决）；不做多机分布式压测 / K8s / CI-CD；真 LLM 只小样本不压大流量（账号配额 + 成本）；app 容器化加分项非欠账；HikariCP 不为数字预先调优（压测显示瓶颈才调并记录理由）；不做公网延迟模拟（单机 localhost 口径如实声明）

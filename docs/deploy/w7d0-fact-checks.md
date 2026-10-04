# W7D0 · T0.2-T0.7 探路事实核查记录

> 日期：2026-10-04 · 结论已回填 shopagent-w7-tasks.md（D0 勾选 + §三版本定版）

## F1（T0.2）JMeter 安装与 CLI 冒烟 ✓
- 版本定版：**Apache JMeter 5.6.3**（官方 dlcdn 直下，~80MB，无失败无需清华镜像）；安装位 D:\dev\apache-jmeter-5.6.3（repo 外）
- CLI 冒烟：`jmeter.bat -n -t docs/jmeter/smoke-test.jmx -l docs/jmeter/smoke.jtl -e -o docs/jmeter/report-smoke` → 6 samples（2 线程×3 loop）**Err 0.00%**，JTL 字段完整、HTML 报告 index.html 生成
- JDK 21 可驱动（JMeter 5.6.3 支持 8-21），无需 JAVA_HOME 配置（PATH java 可达）

## F2（T0.3）MySQL 镜像获取探路 ✓
- **`docker pull mysql:8.4` 直接连成**（digest sha256:6ea90827b110...）——W5D0 的 Docker Hub 断网前科已不复现，自建 playbook 不需要触发
- 版本定版：**mysql:8.4 LTS**（§2.1 预案第一优先）；compose 直接 image: mysql:8.4，无 build 段
- 本地基座盘点：redis:7-alpine / postgres:16 / python:3.11 等（app 容器化加分项若做，无现成 JRE 基镜像，需 pull eclipse-temurin 或复用联网 pull 路径——届时探路）

## F3（T0.4）schema.sql / data.sql MySQL 方言逐行复核
### schema-mysql.sql 差异清单（5 表逐行过）
- **必改①：两处 `CREATE INDEX IF NOT EXISTS`（trade_audit_log 的 idx_audit_user_time / idx_audit_idem_key）→ MySQL 8 无此语法，改平 `CREATE INDEX ...`**（预核确认，逐行复核无其他必改）
- 平移兼容逐项：CREATE TABLE IF NOT EXISTS ✓ / BIGINT AUTO_INCREMENT PRIMARY KEY ✓ / DECIMAL(10,2) ✓ / TEXT NOT NULL（无 DEFAULT，MySQL TEXT 限制不触发）✓ / 多列 TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ✓（8.0+ explicit_defaults_for_timestamp 默认 ON）/ 命名 UNIQUE 约束 ✓ / `orders` 非保留字 ✓
- utf8mb4：表定义不指定字符集随库默认（8.4 默认 utf8mb4_0900_ai_ci）；唯一键 VARCHAR(20)/(30) 索引长度远低于 3072 字节上限 ✓
### data-mysql.sql 差异清单
- **必改②：`FORMATDATETIME(x,'yyyy-MM-dd HH:mm:ss')`（H2 专用）→ `DATE_FORMAT(x,'%Y-%m-%d %H:%i:%s')`**（6 处，logistics.tracks 构造内）
- **必改③：`||` 字符串拼接 → `CONCAT()`**（MySQL 默认 sql_mode 无 PIPES_AS_CONCAT，`||` 是逻辑或，语义直接错）——logistics 5 行全量重写
- 平移兼容：TIMESTAMPADD(HOUR,-n,CURRENT_TIMESTAMP) ✓（MySQL 同名同序语法）
### init 机制两个隐藏坑（D1 决策已定）
- **坑A：spring.sql.init.mode 默认 embedded——MySQL 数据源下 schema/data 根本不会执行**，mysql profile 必须 `mode: always`
- **坑B：CREATE INDEX 无幂等语法**，重复启动 1061 → mysql profile `continue-on-error: true` 兜底（重复运行仅此无害错误；`platform: mysql` + **显式 schema-locations/data-locations 指向 -mysql 文件**，防平台文件与通用文件双跑）

## F4（T0.5）SSE 插件探路 → 走兜底
- 候选：`io.github.cuneytcakir:jmeter-sse-sampler` 2.0.1（repo1.maven.org 实存，Maven Central 搜索索引未收录）；jar 已下并解剖（javap+strings）
- **能力不符：采样器仅 URL / DURATION / HEADERS 三属性（OkHttp 订阅型，GET 长连导向），无 HTTP 方法与 body 字段**——撑不起 POST /api/chat/stream + JSON body
- **定稿：走 §2.2 预设兜底**——吞吐主口径 = JMeter 核心采样器压阻塞端点 /api/chat（本就不依赖插件）；SSE 流式组 = PS5 抽样（W6 burst 模式）报事件时延/done 收尾率/限流话术率，口径声明降级记录；插件 jar 不入 JMeter lib/ext

## F5（T0.6）桩 LLM 并发承受力核查 → 无需改造
- 既有桩（w6d1/w6d4，HttpServer + newFixedThreadPool(16)）**50 并发实测：全 200，P50=1.5ms / P95=2.6ms / max=28ms**——瞬时应答下 16 线程池无排队失真
- D2 T2.2 的「线程池化改造」改判为「实测达标维持现状」；若 D3 梯度加到 100 并发再复核（风险 §6.3 预案保留）

## T0.7 依赖落地 ✓
- pom + `com.mysql:mysql-connector-j`（runtime scope，版本 BOM 管理）→ dependency:tree 实测 **9.7.0** runtime 入树，BUILD SUCCESS；dev 默认 H2 零动

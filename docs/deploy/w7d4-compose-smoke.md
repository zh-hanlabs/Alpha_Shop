# W7D4 · Docker Compose 部署冒烟证据（T4.1-T4.3）

> 日期：2026-10-05 · Docker 29.6.1 + Compose v5.1.4（独立命令 `docker-compose`，v2 插件命令 `docker compose` 未装配）· compose.yml 编排 redis-stack 本地镜像 + mysql:8.4 + （加分）app 容器

## 编排形态（T4.1）

- **服务**：`redis`（build: docker/redis-stack-server，禁 pull，deb 不入仓）/ `mysql`（image mysql:8.4，F2 定版）/ `app`（**fullstack profile 加分项**，零网络 Dockerfile COPY jar，W5D0 playbook）
- **就绪序**：`redis healthcheck（redis-cli ping）` → `mysql depends_on service_healthy`（healthcheck 用带密码 root ping——首次 init 的临时 server root 密码未设不会误报健康）→ `app depends_on mysql healthy`；实测 redis 6s healthy → mysql 15s healthy
- **凭据**：.env 注入（compose 与应用同读 `MYSQL_PORT=13306` 等；**密钥类不写 .env**，只走系统环境变量——AGENTS.md 规则 4，`.env.example` 已加注释声明）
- **数据卷**：`redis-data` / `mysql-data` 命名卷；`down -v` 才清空，清空后应用启动自动重建 schema/演示数据/向量索引
- **README 草稿段**已入 README「快速启动」下（克隆三步），T5.2 统一打磨

## 迁移说明

旧 `docker run` 容器（shopagent-mysql/redis，匿名卷、无 healthcheck）`docker rm -f` 迁除，匿名卷退役（测试态数据不迁移）；compose 全新命名卷 = 从零克隆体验验证（见下）。

## T4.2 部署冒烟 ✅

1. **克隆体验（空库自动播种）**：compose 全新 MySQL 卷 → 应用 `dev,mysql` 启动 init → product 2 库存回到种子值 200（D3 fixture 残留 299 被清零重建）——**init 幂等 + 全新环境一键拉起验证**
2. **知识库索引指纹幂等重建（W5 机制零改动）**：空 Redis → `知识库索引开始重建：语料 40 条（首次构建）` → `重建完成：40 条，耗时 1784 ms`（DashScope embedding 真调用）
3. **查/问/办演示一轮**：
   - 查：订单 10001 查询 → 中文商品名/金额/物流状态零乱码
   - 问：中文问答（真 DeepSeek）「那个充电宝多少钱，还有货吗」→ 模型调 searchProduct 工具 → 中文回答价格+库存，零乱码
   - 办：下单全闸序 → orderNo 20261005152447983381 落 MySQL，库存 200→199
4. **混沌 C2 抽查（容器 MySQL 零超卖）**：fixture 重置 product 7 stock=100 → c2-diff-keys.ps1 → **50/50 success、50 唯一单号、stockDelta=50、finalStock=50，PASS**（跨库语义不变第三次实证：H2/W7D1/docker run MySQL/compose MySQL）
   - 附带：C2 脚本惯例 FLUSHDB 清空向量索引 → 后续 T4.3 容器化应用启动再次自动重建（指纹机制二次验证）

## T4.3 加分项：app 容器化 ✅（非欠账，探路通过后完成）

- **镜像获取探路通过**：`eclipse-temurin:21-jre` 拉取成功（472MB，W7D0 断网前科未复现）→ 加分项转正
- **零网络 Dockerfile**（docker/app/Dockerfile）：COPY target jar（.dockerignore 全排除仅放行 jar，context=仓库根）；Java 17 target 跑 JRE 21（pom java.version=17）
- **编排接入**：`profiles: ["fullstack"]`——默认 `up -d` 维持「只起依赖」不变，`--profile fullstack up -d` 全栈容器；容器内 `SPRING_DATA_REDIS_HOST=redis`（宽松绑定）+ `MYSQL_HOST=mysql`（yml 占位）走服务名，容器内 MySQL 端口落 yml 默认 3306 与宿主 13306 互不干扰
- **全栈演示一轮**：三容器 healthy → 容器内索引重建 40 条/2581ms → 中文问答（真 DeepSeek+工具调用）零乱码 → 应用日志确认 dev,mysql 双 profile

## 踩坑/备注

1. `docker compose`（v2 插件）本机未装配，用独立命令 `docker-compose`（v5.1.4）——README 命令口径按此
2. W6D3 老坑复踩：内联 curl 中文 JSON 在 GBK 控制台 400——冒烟统一 UTF-8 文件体（`--data-binary @file`）
3. 宿主 3306 被本机 mysqld 占用（W7D1 实况）→ compose 默认挂 13306，`.env` 统一 `MYSQL_PORT` 供 compose 端口映射与应用连接串同读

## 留存状态

三容器 compose 托管运行中（shopagent-redis / shopagent-mysql / shopagent-app）；宿主应用已停（8080 由容器 app 提供）。测试数据：product 7 stock=50（C2 后）、product 2 stock=199、u1001 合成订单若干。

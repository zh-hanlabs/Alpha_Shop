# ShopAgent · W1-2 MVP 开发任务清单

> 目标版本：两周 MVP（10 个工作日）
> 原则：先跑通再优化，所有代码为 W3+（幂等 / 分布式锁 / 限流）预留接缝

---

## 一、两周验收标准（Definition of Done）

两周结束时，能完成这条演示链路：

> 打开聊天页面 → 输入「我的订单 10001 到哪了」→ Agent 自主决定调用物流查询工具 → 流式返回自然语言答复 → 追问「这个订单里那个充电宝多少钱」→ Agent 结合上下文继续调工具回答。

硬性指标：

- [ ] 多轮对话有记忆，切换会话互不串扰
- [ ] 3 个查询工具（订单 / 物流 / 商品）由模型自主决策调用
- [ ] 前端页面流式打字机输出（SSE）
- [ ] `git clone` + 配 API Key 即可启动（H2 内存库 + schema 自动初始化）
- [ ] README 有架构图、启动步骤、演示 GIF

---

## 二、前置准备（Day 1 上午，一次性）

| # | 事项 | 说明 |
|---|---|---|
| P1 | JDK 17+ / Maven | Spring AI Alibaba 基于 Spring Boot 3.x，本地 JDK 必须 17 及以上 |
| P2 | 申请模型 API Key | 阿里云百炼平台申请，设置环境变量 `AI_DASHSCOPE_API_KEY`；备选 DeepSeek（兼容 OpenAI 协议） |
| P3 | 建仓 | GitHub 新建 `shopagent` 仓库，配 `.gitignore`（target/、.env、*.iml） |
| P4 | 拉官方示例作参考 | `git clone --depth=1 https://github.com/springaialibaba/spring-ai-alibaba-examples` |

---

## 三、任务分解（按天）

### Day 1：工程骨架 + 第一个对话接口

| 任务 | 内容 | 完成标志 |
|---|---|---|
| T1.1 初始化工程 | Spring Boot 3.3.x + `spring-ai-alibaba-starter`，配 spring-milestones 仓库 | `mvn compile` 通过 |
| T1.2 ChatClient 装配 | `ChatClient.Builder` 注入，默认 System Prompt（电商客服角色）+ `SimpleLoggerAdvisor` | 启动无报错 |
| T1.3 第一个接口 | `GET /api/chat?query=` 调用模型返回文本 | curl 得到通义回复 |

```xml
<!-- 以 java2ai.com 快速开始页当前 GA 版本为准（1.0.0-M5.1 及以上 / 1.x） -->
<dependency>
  <groupId>com.alibaba.cloud.ai</groupId>
  <artifactId>spring-ai-alibaba-starter</artifactId>
  <version>1.0.0-M5.1</version>
</dependency>

<!-- spring-ai 依赖未发布中央仓库时必需 -->
<repositories>
  <repository>
    <id>spring-milestones</id>
    <url>https://repo.spring.io/milestone</url>
  </repository>
</repositories>
```

> 2026-10-03 落地版本：SAA BOM `1.1.2.4-security-fix` + `spring-ai-alibaba-starter-dashscope` + Spring Boot 3.5.16。1.x GA 已全部发布中央仓，**无需** spring-milestones 仓库。

```yaml
spring:
  ai:
    dashscope:
      api-key: ${AI_DASHSCOPE_API_KEY}
      chat:
        options:
          model: qwen-plus   # 调试期用 qwen-turbo 省钱，演示前换回 plus
```

### Day 2：会话记忆与隔离

| 任务 | 内容 | 完成标志 |
|---|---|---|
| T2.1 多轮记忆 | ChatClient 挂 `MessageChatMemoryAdvisor`（先用 InMemory，W6 换 Redis） | 第二轮能引用第一轮内容 |
| T2.2 会话隔离 | 请求带 `conversationId`，不同 ID 上下文互不污染 | 两个会话并行不串扰 |
| T2.3 结构化请求 | 接口改为 `POST /api/chat`，Body 含 `conversationId + message` | curl 验证通过 |

> 注意：InMemoryChatMemory 重启即失忆，这是预期内的过渡方案，W6 统一换成 Redis 存储——面试时可讲这个演进决策。

### Day 3：模拟数据层

| 任务 | 内容 | 完成标志 |
|---|---|---|
| T3.1 引入 H2 | 内存库 + `schema.sql` / `data.sql` 自动初始化，零外部依赖 | 启动后能查到数据 |
| T3.2 建表 | `orders`（订单号、用户、状态、金额）、`order_item`、`product`（含库存价格）、`logistics`（物流轨迹 JSON） | 5~6 条真实感 mock 数据 |
| T3.3 MyBatis-Plus 或 JPA | 二选一，顺手哪个用哪个 | 单测/接口能查库 |

> mock 数据要「有故事」：不同状态的订单（待支付/已发货/已签收/退款中），演示时才有的聊。

### Day 4：第一个工具调用（本周核心）

| 任务 | 内容 | 完成标志 |
|---|---|---|
| T4.1 定义订单查询工具 | `@Tool` 注解 + 清晰的 description（何时调用/参数含义/返回什么） | 日志能看到模型发起 tool call |
| T4.2 接入 Agent | ChatClient `.tools(...)` 注册，观察 ReAct 决策链 | 问订单问题正确命中工具 |
| T4.3 决策日志 | 打印每轮：模型思考 → 工具调用 → 结果回填 → 最终回答 | 简单问题不乱调工具 |

工具描述模板（直接抄这个结构，效果稳定）：

```java
@Tool(description = "查询订单详情。当用户询问某个订单的金额、商品明细、状态时调用。" +
        "参数 orderId 为订单号，纯数字。用户没给订单号但说『我的订单』时，先用 recentOrders 查最近订单。")
public OrderDetail queryOrder(@ToolParam(description = "订单号，纯数字") String orderId) { ... }
```

> 经验：工具描述写得越像「需求文档」，模型调用准确率越高。这一步值得花 1 小时反复打磨。

### Day 5：查询类工具全家桶

| 任务 | 内容 | 完成标志 |
|---|---|---|
| T5.1 物流查询 | `queryLogistics(orderId)` 返回轨迹并转自然语言摘要 | 「到哪了」类问题全通 |
| T5.2 商品查询 | `searchProduct(keyword)` 模糊搜索商品名 | 「有没有充电宝」类问题全通 |
| T5.3 最近订单 | `recentOrders(userId)` 无订单号时兜底 | 「我最近的订单」类问题全通 |
| T5.4 统一返回结构 | 所有工具返回 `ToolResult{code, msg, data}`，异常不抛给模型 | 查无此单时模型能自然道歉 |

> T5.4 是刻意埋点：统一结构后，W3 的交易工具（下单/退款）能无缝接入幂等校验，不用改调用方。

### Day 6：SSE 流式接口

| 任务 | 内容 | 完成标志 |
|---|---|---|
| T6.1 流式输出 | `.stream()` 改造，`POST /api/chat/stream` 返回 `text/event-stream` | curl 看到逐块输出 |
| T6.2 事件区分 | 思考中 / 工具调用中 / 回答内容 三种事件类型 | 前端能展示「正在查询订单…」 |
| T6.3 异常兜底 | 模型超时/报错返回友好 SSE 错误事件 | 拔网线测一次 |

### Day 7：聊天前端

| 任务 | 内容 | 完成标志 |
|---|---|---|
| T7.1 单页聊天 UI | `static/index.html` + EventSource，打字机效果，会话 ID 自动生成 | 页面可完整对话 |
| T7.2 工具调用可视化 | 收到工具事件时展示「🔍 正在查询订单 10001…」 | 演示效果直观 |
| T7.3（可选） | 时间富余再考虑开源聊天模板套壳，否则原生前端足够 | — |

> 前端克制：一个 HTML 文件搞定，不引入 Node 构建。面试官看的是 Agent，不是 CSS。

### Day 8：Prompt 与边界打磨

| 任务 | 内容 | 完成标志 |
|---|---|---|
| T8.1 System Prompt | 电商客服人设 + 只谈订单/商品 + 引导话术 | 问无关话题礼貌拒答 |
| T8.2 越界测试 | 诱导改价、查别人订单、SQL 注入式提问 | 全部安全应答 |
| T8.3 兜底话术 | 查询失败 / 幻觉订单号 / 参数缺失 场景演练 | 无裸异常 |

> T8.2 的测试用例记进 README，这就是现成的面试素材：「我在 Agent 项目里做了哪些安全设计」。

### Day 9：收尾工程化

| 任务 | 内容 | 完成标志 |
|---|---|---|
| T9.1 README | 架构图（本清单同款）、启动步骤、演示 GIF | 生人可复现 |
| T9.2 配置外置 | API Key 只走环境变量，`.env.example` 提交 | 仓库无密钥泄漏 |
| T9.3 冒烟清单 | 把 DoD 的演示链路手动跑 3 遍 | 稳定复现 |

### Day 10：Buffer

- 补前两天拖下的任务
- Git 历史整理（rebase 成清晰的 commit 序列：骨架→记忆→工具→SSE→前端）
- 录演示 GIF
- 提前预研 W3：读一遍 Redisson `RLock` 文档，想清楚幂等键设计（`tool:{userId}:{action}:{paramsHash}`）

---

## 四、推荐工程结构（单体模块化）

```
shopagent/
├── pom.xml
├── README.md
└── src/main/
    ├── java/com/shopagent/
    │   ├── ShopAgentApplication.java
    │   ├── config/            # ChatClient 装配、模型参数
    │   ├── controller/        # /api/chat、/api/chat/stream
    │   ├── agent/             # Agent 编排（W2 后半迁到 Graph 结构）
    │   ├── tools/             # @Tool 工具层（查询类；W3+ 加交易类）
    │   │   └── support/       # ToolResult、ToolContext（userId 注入）
    │   ├── service/           # 订单/商品/物流业务查询
    │   └── infra/             # 预留：幂等、分布式锁、限流熔断（W3/W6）
    └── resources/
        ├── application.yml
        ├── schema.sql / data.sql   # H2 自动初始化
        └── static/index.html      # 聊天页
```

---

## 五、刻意埋点（为 W3+ 预留的接缝）

| 埋点 | 现在做什么 | 之后怎么用 |
|---|---|---|
| ToolResult 统一结构 | 所有工具返回 code/msg/data | W3 交易工具在 data 前插入幂等校验，调用方零改动 |
| userId 走 ToolContext | 会话级注入用户身份，工具内不硬编码 | W3 幂等键 = userId + action + 参数摘要 |
| infra/ 空包 | 占位 + 包说明注释 | W3 放幂等组件、W6 放限流熔断 |
| 会话抽象在 agent/ | ChatClient 用法集中在少数类 | W5+ 平滑切换到 Graph 编排 |
| SimpleLoggerAdvisor | 保留默认决策日志 | W7 压测与问题定位的数据来源 |

---

## 六、踩坑清单（提前避雷）

1. **API Key 别进 Git**：只走环境变量 `AI_DASHSCOPE_API_KEY`，`.gitignore` 加 `.env`。
2. **spring-ai 依赖解析失败**：确认 pom 配了 `repo.spring.io/milestone` 仓库。
3. **模型不调工具**：90% 是工具 description 写得太模糊；把「何时调用」写进去。
4. **工具参数被幻觉**：订单号让模型传 `String`，在工具内做格式校验，别信模型传参。
5. **流式 + 记忆冲突**：`MessageChatMemoryAdvisor` 与 `.stream()` 组合注意上下文写入时机，异常先打日志别吞。
6. **qwen-turbo 调工具不稳定**：调试期省钱可以用，验收测试一律 qwen-plus。
7. **H2 时区**：`data.sql` 里时间用相对偏移，别写死日期，避免「物流显示明年」的尴尬。

---

## 七、两周末演示脚本（面试直接用）

1. 「帮我看看订单 10001 到哪了」→ 工具调用可视化 + 物流摘要
2. 「那个订单里充电宝多少钱」→ 无参数追问也能命中（记忆 + 工具组合）
3. 「帮我把它退了」→ Agent 回复「退款功能确认中」（为 W3 交易工具留的钩子）
4. 打开日志面板展示 ReAct 决策链 —— 讲清楚模型怎么「想」的

> 第 3 步是精心设计的钩子：面试官必问「为什么不能退」，你顺势讲 W3 的幂等 + 分布式锁设计——把面试节奏握在自己手里。

---

*下一步：W3-4 交易工具 + 幂等 + 分布式锁任务清单（需要时再生成）。*

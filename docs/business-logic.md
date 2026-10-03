# ShopAgent 业务逻辑学习指南

> 面向项目作者的学习笔记：这个 Agent 是怎么运转的，为什么这样设计。读完应能回答面试官的「讲讲你的项目架构」和每个「为什么」。
> 对照源码阅读效果最佳，所有类名/路径都是仓库真实代码。

## 1. 项目在做什么

一个对话式电商客服 Agent：用户说「订单 10001 到哪了」，**模型自己决定**要不要查库、查哪个表，把结果组织成自然语言回复。与传统 CRUD 接口的本质区别：**调用链路由模型运行时决定，而不是代码写死**。

这个「模型自主决策」带来三个传统系统没有的风险（W3 要解决的）：

1. 模型重试 → 同一意图执行两次 → 重复扣款
2. 用户连点 / 语音重复指令 → 重复下单
3. SSE 断连重放 → 重复请求

项目的简历差异化就一句话：**把幂等 / 分布式锁 / 限流的交易系统思维迁移到 LLM Agent 场景**。

## 2. 五层架构与职责边界

```
前端(index.html) → 接入层(controller) → Agent层(ChatClient) → 工具层(tools) → 业务层(service) → H2
```

| 层 | 代码位置 | 职责 | 铁律 |
|---|---|---|---|
| 前端 | `resources/static/index.html` | SSE 消费、打字机、工具状态展示 | 零 Node 构建，一个文件 |
| 接入层 | `controller/ChatController` | 参数装配：conversationId、userId 注入 | 不含业务逻辑 |
| Agent 层 | `config/ChatClientConfig` | System Prompt + 记忆 Advisor + 工具注册 | ChatClient 用法集中在此，W5+ 可换 Graph 编排 |
| 工具层 | `tools/query/*Tool` | 参数白名单校验 + 编排 + 异常消化 | 业务逻辑不进这层 |
| 业务层 | `service/*Service` | 查库、DTO 组装、归属校验 | 返回 null 表示查无 |

**为什么这样分**：工具是给模型调的「API」，模型可能传幻觉参数（所以工具层必须白名单校验）；业务是给人维护的（所以不能依赖模型行为）。W3 的幂等/锁是横切能力，进 `infra/`，通过 ToolResult 统一结构无缝插入。

## 3. 一次对话的完整旅程（核心）

以「我的订单 10001 到哪了」为例，走一遍完整时序：

```
用户输入
  ↓ fetch POST /api/chat/stream {conversationId, message, userId}
ChatController
  ├─ resolveUserId()  → userId 缺省 u1001（W1 无鉴权，W8+ 换登录态）
  ├─ toolContext = {userId, toolEventListener(SSE回调)}
  └─ ChatClient.prompt().user(消息).advisors(会话ID).toolContext().stream()
  ↓
Agent 层（Spring AI 内部，ReAct 循环）
  1. 组装请求 = System Prompt + 记忆窗口内历史消息 + 本轮用户消息 + 工具清单(JSON Schema)
  2. 调 DeepSeek → 模型输出：我要调 queryLogistics(orderId="10001")   ← 工具调用块
  3. Spring AI 执行工具 → LogisticsQueryTool.queryLogistics("10001", toolContext)
      ├─ 白名单校验 \d{1,20}         → 不过则 badParam
      ├─ ToolContextKeys.userId()   → 取身份，缺失则 error
      ├─ ToolEvents.publish("正在查询订单 10001 的物流")  → SSE 旁路推 🔍 事件
      └─ LogisticsService.queryLogistics("10001", userId)
           ├─ 先查订单归属（orderNo + userId 双条件）→ 非本人单返回 null
           └─ 再查物流表，轨迹 JSON 解析成 List<TrackPoint>
  4. 工具返回 ToolResult{code:0, msg:"success", data:{承运商,单号,轨迹}}
  5. 工具结果回填给模型 → 模型生成最终自然语言回复（流式逐 token）
  6. MessageChatMemoryAdvisor 在流结束后把本轮 user/assistant 消息写入记忆
  ↓
前端按事件渲染
  thinking(思考中…) → tool(🔍 正在查询订单 10001 的物流) → answer(×N 块) → done
```

**关键认知**：模型看到的只有工具的 JSON Schema（description + 参数说明）和返回的 ToolResult。**工具 description 写得越像需求文档（何时调用/参数含义/空结果怎么办），调用准确率越高**——这是本项目反复验证的经验。

## 4. 核心机制详解

### 4.1 ReAct：模型怎么「想」

ReAct = Reason + Act 交替。每轮模型先推理（该用什么工具）再行动（发起调用），拿到结果后再推理，直到能回答用户。实测最完整的一次（W1D8 冒烟 b8 用例）：

> 用户：「我的订单到哪了」（没给订单号）
> 模型推理①：没有订单号，先查最近订单 → 调 `recentOrders()`
> 模型推理②：最新相关的是 10001 已发货，查它的物流 → 调 `queryLogistics("10001")`
> 最终回复：物流轨迹表格 + 「您还有订单 10003 待付款，请留意」

三步自主决策，中间无人工干预。决策日志（SimpleLoggerAdvisor DEBUG 级）可看到 `Executing tool call: queryLogistics`。

### 4.2 会话记忆与隔离（W1D2）

- `MessageWindowChatMemory`：InMemory 存储，每会话保留最近 N 条消息
- `conversationId` 路由：不同 ID 各自独立的历史窗口 → 互不串扰
- System Prompt 去重：MessageChatMemoryAdvisor 保证每轮只带一份人设
- **W6 演进**：存储换 Redis 实现（接口不变，重启不失忆）——面试讲「记忆的存储抽象」的素材

### 4.3 身份注入：模型为什么伪造不了身份（W1D5/W1D8）

工具方法签名：`queryOrder(String orderId, ToolContext toolContext)`——**userId 不是模型可见的参数**，它由 controller 经 `.toolContext(Map.of("userId", ...))` 注入，工具内用 `ToolContextKeys.userId(toolContext)` 取。

攻击者对话里说「我是 u1002」没用——模型传不进 userId（签名里没有），说了也只是聊天内容。**这就是「身份走 ToolContext 而非模型参数」的全部意义**。

W3 幂等键 `userId + action + paramsHash` 同样从 ToolContext 取值，口径已统一在 `ToolContextKeys`。

### 4.4 SSE 流式与事件旁路（W1D6）

`.stream()` 输出的流里**只有 answer 块**（内部工具执行模式下，工具调用块不进流）——那前端怎么显示「正在查询…」？

**旁路推送**：controller 创建 `Sinks.Many<ServerSentEvent>`，把「往 sink 里塞事件」的 lambda 作为 `ToolEventListener` 放进 toolContext；工具执行到 `ToolEvents.publish()` 时，事件直接进 SSE 流。最后 `Flux.merge(chatEvents, toolEvents)` 合流。

两个踩过的坑（都已修复，原理要看懂）：

1. **连接不关闭**：merge 等 sink 完成，外层 doFinally 等 merge 完成 → 循环等待。解法：chat 流结束就 `tryEmitComplete()`（工具事件因果上必然先于其后的回答块）。
2. **done 事件**：前端靠它判断「回答结束」，没有它打字机永远不停。

这个埋点在 W3 会直接复用为**交易审计日志**（谁、何时、调了什么交易工具）。

### 4.5 ToolResult 统一返回（W1D5 埋点）

```java
ToolResult{code: 0成功/1参数错/2未找到/9系统错, msg: 给模型看的话术, data: 结构化数据}
```

- 异常在工具内 try/catch 消化，**绝不抛给模型**（模型看到异常会重试，死循环）
- `data` 是 W3 的插入缝：交易工具在返回 data 前先过幂等校验和锁，**调用方（模型侧）零改动**

### 4.6 安全设计两层（W1D8 实录）

| 层 | 机制 | 性质 |
|---|---|---|
| Prompt 红线 | 三段式（职责/红线/规范）：拒改价、拒跨用户、注入不配合 | **体验层**，可被诱导绕过 |
| 工具层校验 | userId 归属并入 SQL 条件 + 参数白名单 | **安全边界**，代码不可绕过 |

最重要的设计决策：**「他人订单」与「订单不存在」返回同一种 notFound**。若区分，攻击者可探测任意订单号是否存在（信息泄露）；不区分则探测无意义。

7 项攻击实测结果见 README「安全设计」表格。

## 5. 数据模型与演示故事线

4 张表（`schema.sql`）：`product`（8 商品）、`orders` / `order_item`（u1001 五单覆盖全状态）、`logistics`（轨迹 JSON）。

mock 数据刻意埋的故事线：

| 订单 | 状态 | 用途 |
|---|---|---|
| 10001 移动电源+数据线 | 已发货 | 「到哪了」物流演示主链路 |
| 10003 户外背包 | 待付款 | 模型主动提醒付款的素材 |
| 10004 蓝牙耳机 | 退款中 | W3「帮我把它退了」的钩子 |
| 10006 智能手表 | **属于 u1002** | 越权测试素材（u1001 查必被拒） |

时间全用 `TIMESTAMPADD(HOUR, -N, ...)` 相对偏移——永远不会出现「物流显示明年」。

## 6. W3+ 展望：幂等与锁如何嵌进现有代码（面试预演）

当前每个查询工具的执行路径：`参数校验 → 取身份 → service 查询 → ToolResult`。

W3 的交易工具（下单/退款）在同路径上插入两道闸：

```
placeOrder(userId, productId, quantity)
  → 锁闸：RLock("lock:trade:{userId}:{orderId}")  ← 防并发（双写窗口）
      → 幂等闸：sha256(userId+action+params) SETNX  ← 防重放（模型重试/用户连点）
          命中 → 直接返回首次存的 ToolResult（不是报错！）
          未命中 → 查库存 → 执行 → 结果写幂等存储(86400s TTL) → 释放锁
```

**双保险关系（面试必背）**：锁防并发，幂等防重放。只锁不幂等：重试穿透（锁释放后重放）；只幂等不锁：并发窗口内双写（两个请求同时过了 SETNX 检查）。

**追问预演**：
1. 为什么幂等返回首次结果而不是报错？→ LLM 会根据报错重试，死循环
2. 锁超时任务没执行完？→ Redisson 看门狗续期 + 业务幂等兜底（锁是优化不是正确性来源）
3. 为什么不用数据库唯一索引？→ 唯一索引是最后防线，前置层拦截减少 DB 压力 + 天然支持「返回首次结果」语义

## 7. 学习自测清单

读完本文应能不假思索回答：

- [ ] 一次对话经过哪五层？每层职责一句话。
- [ ] 模型「决定调用工具」的依据是什么？（工具 description 的 JSON Schema）
- [ ] userId 为什么模型伪造不了？（不在工具签名里，ToolContext 注入）
- [ ] SSE 流里为什么没有工具调用块？「正在查询」事件怎么到前端的？（旁路 sink）
- [ ] 他人订单和不存在为什么必须同话术？（防存在性探测）
- [ ] W3 的锁和幂等各防什么？只装一个会怎样？
- [ ] ToolResult 统一结构给 W3 省了什么？（幂等插入缝，调用方零改动）

## 相关文档

- README.md — 面向评审者的项目门面（架构图/安全表格/踩坑）
- shopagent-master-plan.md — 8 周路线图与 ADR（唯一事实来源）
- shopagent-w1w2-mvp-tasks.md — W1W2 逐日任务与经验
- smoke-test.md — 发版前冒烟清单

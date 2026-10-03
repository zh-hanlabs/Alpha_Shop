# AGENTS.md — ShopAgent 开发规则

> AI 编码助手请先完整阅读本文件。本文件是仓库级规则，详细计划见 `shopagent-master-plan.md`。

## 项目是什么

对话式电商交易 Agent（Spring AI Alibaba），8 周简历项目。核心差异化：把幂等 / 分布式锁 / 限流的工程思维迁移到 LLM Agent 场景。

- 完整计划（唯一事实来源）：`shopagent-master-plan.md` 中的路线图、ADR、设计细节
- 当前阶段任务：见 `shopagent-w1w2-mvp-tasks.md` 或对应周清单

## 每次会话开始前

1. 读主计划 §3 路线图，确认当前处于哪一周
2. 用户给出任务编号（如 W3D3）后，只做该任务范围内的事
3. 不确定当前进度时，先问，不要猜

## 硬性规则

1. **小步提交**：一个任务一个 commit，格式 `W{周}D{天}: 简述`
2. **新增依赖必须先报备**：说明理由并对照主计划 §1 禁止清单——禁止微服务拆分、多 Agent 编排框架、A2A、前端构建工具链、独立 MQ
3. **工具方法统一返回** `ToolResult{code, msg, data}`，异常在工具内消化，不抛给模型
4. **密钥安全**：API Key 只走环境变量 `AI_DASHSCOPE_API_KEY`，任何代码 / 配置 / 测试不落盘真实密钥
5. **不过度设计**：无任务编号支撑的抽象不做；三处重复再抽，一处不抽
6. **测试口径**：`infra/` 和 `service/` 必须有单测；`tools/` 靠冒烟清单手工验证
7. **三个设计敏感点，先对齐再写码**：幂等语义（返回首次结果，不报错）、锁粒度（用户+订单级）、缓存一致性（先更库再删缓存）——详见主计划 §4/§5

## 技术栈（不可漂移，理由见主计划 §1）

JDK 17+ / Spring Boot 3.5.x / Spring AI Alibaba 1.x（当前 1.1.2.4-security-fix）/ 通义 qwen-plus / H2（W7 切 MySQL 8）/ Redis + Redisson（W3 起）/ Redis Stack 向量库（W5 起）/ JMeter / Docker Compose / 原生单页前端

## 代码约定

- 包结构：`com.shopagent.{config, controller, agent, tools, service, infra}`
- 工具类后缀 `Tool`，业务类后缀 `Service`
- tools/ 只做参数校验和编排，业务逻辑进 service/，横切能力进 infra/
- 注释只写 Why 不写 What；魔法数字进常量类
- 用户身份走 ToolContext 注入，工具内不硬编码 userId

## 已定决策（详见主计划 §8，勿推翻）

幂等返回首次结果而非报错（防 LLM 重试死循环）｜锁粒度=用户+订单｜InMemory 记忆 W6 才换 Redis｜向量库用 Redis Stack｜单体模块化不拆微服务｜幂等+锁是简历核心，功能可砍它不可砍

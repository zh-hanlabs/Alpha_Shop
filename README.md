# ShopAgent

对话式电商交易 Agent：用自然语言完成「查—问—办」全流程。把高并发交易系统的工程思维（幂等 / 分布式锁 / 限流）迁移到 LLM Agent 场景。

## 技术栈

JDK 17+ · Spring Boot 3.5.x · Spring AI 1.1.x · DeepSeek（当前接入）

## 快速启动

```bash
# 1. 配置模型 API Key，只走环境变量，勿写入任何文件
#    Windows PowerShell（当前会话）：
$env:DEEPSEEK_API_KEY = "sk-xxx"
#    Windows（永久，需新开终端生效）：
setx DEEPSEEK_API_KEY "sk-xxx"

# 2. 启动（Maven Wrapper 免安装，仅需 JDK）
./mvnw spring-boot:run

# 3. 验证第一个对话接口
curl "http://localhost:8080/api/chat?query=你好"
```

## 模型切换

当前接入 DeepSeek（OpenAI 兼容协议，`spring-ai-starter-model-openai`）。切回通义 qwen 时：

1. `pom.xml`：`spring-ai-starter-model-openai` 换回 `com.alibaba.cloud.ai:spring-ai-alibaba-starter-dashscope`（1.1.2.4-security-fix）
2. `application.yml`：`spring.ai.openai.*` 段换成 `spring.ai.dashscope.*`，环境变量改用 `AI_DASHSCOPE_API_KEY`

代码零改动——ChatClient / Advisor / Tool 均为 Spring AI 标准抽象。

## 当前进度

W1D1：工程骨架 + ChatClient 装配 + 第一个对话接口。路线图见 `shopagent-master-plan.md`。

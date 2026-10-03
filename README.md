# ShopAgent

对话式电商交易 Agent：用自然语言完成「查—问—办」全流程。基于 Spring AI Alibaba + 通义千问构建，把高并发交易系统的工程思维（幂等 / 分布式锁 / 限流）迁移到 LLM Agent 场景。

## 技术栈

JDK 17+ · Spring Boot 3.5.x · Spring AI Alibaba 1.1.2.x · 通义 qwen-plus

## 快速启动

```bash
# 1. 配置模型 API Key（阿里云百炼平台申请），只走环境变量，勿写入任何文件
#    Windows PowerShell（当前会话）：
$env:AI_DASHSCOPE_API_KEY = "sk-xxx"
#    Windows（永久，需新开终端生效）：
setx AI_DASHSCOPE_API_KEY "sk-xxx"

# 2. 启动（Maven Wrapper 免安装，仅需 JDK）
./mvnw spring-boot:run

# 3. 验证第一个对话接口
curl "http://localhost:8080/api/chat?query=你好"
```

## 当前进度

W1D1：工程骨架 + ChatClient 装配 + 第一个对话接口。路线图见 `shopagent-master-plan.md`。

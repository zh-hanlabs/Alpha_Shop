# W6D0 · T0.3 预核事实回填记录

> 日期：2026-10-04 · 工具：javap（JDK 21）对本地 .m2 实存 jar · 结论已回填 `shopagent-w6-tasks.md` §2.1/§2.3

## 1. ChatMemoryRepository 接口（spring-ai-model-1.1.2.jar）✓ 与清单预核一致

```
public interface org.springframework.ai.chat.memory.ChatMemoryRepository {
  List<String> findConversationIds();
  List<Message> findByConversationId(String);
  void saveAll(String, List<Message>);
  void deleteByConversationId(String);
}
```

## 2. MessageWindowChatMemory（同 jar）✓ builder 存在，换装点成立

```
public final class MessageWindowChatMemory implements ChatMemory {
  void add(String, List<Message>);
  List<Message> get(String);
  void clear(String);
  static Builder builder();
}
```

## 3. OpenAiChatOptions.streamUsage（spring-ai-openai-1.1.2.jar）✓ 字段在

```
public Boolean getStreamUsage();
public void setStreamUsage(Boolean);
```

（是否真返回 usage 取决于 DeepSeek 侧 stream_options.include_usage，D4 实测，口径见 w6 清单 §2.4）

## 4. RRateLimiter（redisson-3.52.0.jar）✓ extends RExpirable；四参重载存在

```
public interface RRateLimiter extends RRateLimiterAsync, RExpirable {
  boolean trySetRate(RateType, long, RateIntervalUnit);          // 三参·单位版
  boolean trySetRate(RateType, long, Duration);                  // 三参·Duration 版
  boolean trySetRate(RateType, long, Duration, Duration);        // 四参：第 3 个=interval，第 4 个=TTL（冒烟 T4 实证）
  void setRate(RateType, long, ...);
}
```

## 5. Spring AI 自带 Message codec 排查 → 结论：不可复用，自研 type-tag 两态 JSON

- `spring-ai-model-chat-memory-repository-jdbc-1.1.2.jar`：类清单只有 `JdbcChatMemoryRepository` + 8 个方言类，**无独立 codec/序列化类**——消息↔行转换内联在 saveAll/findByConversationId（javap -p 仅见 lambda$saveAll$0），按 type+content 列重建，不是 JSON payload。
- `spring-ai-model-1.1.2.jar`：全 jar 无 codec/serializ/jackson 命名的类（unzip -l grep 实测）。
- 故 §2.3 序列化路线定稿：**自研 type-tag 两态 JSON**（只存 UserMessage/AssistantMessage，与 persistMemory 现状对齐，无 ToolResponseMessage 多态坑）。

## 关联：T0.2 RRateLimiter 桶语义冒烟

- 可回归语义锁：`src/test/java/com/shopagent/infra/resilience/RRateLimiterSemanticsSmokeTest.java`（默认 `mvn test` 跳过，`-Dsmoke.redis=true` 才跑）
- 完整运行输出：`w6d0-rratelimiter-smoke.log`（Tests run: 7, Failures: 0, Errors: 0）
- 定稿结论五条见该测试类 javadoc；Redis 侧键结构 `{主键}:value` / `{主键}:permits`（PER_CLIENT 版再加 `.clientId` 后缀）

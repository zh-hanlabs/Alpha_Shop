package com.shopagent.infra.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会话记忆 Redis 化（W6D2，设计定稿 shopagent-w6-tasks.md §2.3，决策 D5 兑现）。
 * 结构：Redis hash chat:memory:{会话ID}，field=序号，value=Message 两态 JSON；
 * TTL 7 天写时刷新（每次 saveAll 重置 EXPIRE——活跃会话永续，沉默会话 7 天自然过期）；
 * saveAll 语义 = MessageWindowChatMemory 的整窗替换，与 hash 全量重写天然契合。
 * 序列化自研 type-tag 两态 JSON（D0 排查结论：Spring AI 无可复用 codec，JDBC 仓库按列重建同理）。
 * fail-open 分级（§2.3）：读失败 → 空历史照常聊，写失败 → 吞掉——记忆是体验不是正确性，
 * 与交易 fail-closed（W3）形成对照：交易错一笔是真金白银，记忆丢一轮是体验问题。
 */
@Component
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryRepository.class);

    private static final String KEY_PREFIX = "chat:memory:";
    private static final Duration MEMORY_TTL = Duration.ofDays(7);
    private static final String TYPE_USER = "USER";
    private static final String TYPE_ASSISTANT = "ASSISTANT";

    /** 只存 UserMessage/AssistantMessage 两态（persistMemory 现状只写这两类，无 ToolResponseMessage 多态坑） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessagePayload(String type, String content) {}

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;

    public RedisChatMemoryRepository(RedissonClient redisson, ObjectMapper objectMapper) {
        this.redisson = redisson;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> findConversationIds() {
        try {
            return redisson.getKeys().getKeysStreamByPattern(KEY_PREFIX + "*")
                    .map(key -> key.substring(KEY_PREFIX.length()))
                    .toList();
        } catch (Exception e) {
            log.warn("memory conversation scan failed, fail-open to empty list", e);
            return List.of();
        }
    }

    /** 按序号 field 升序重建消息序列；损坏条目跳过不拖垮整窗（记忆降级粒度 = 单条） */
    @Override
    public List<Message> findByConversationId(String conversationId) {
        try {
            Map<String, String> entries = hash(conversationId).readAllMap();
            return entries.entrySet().stream()
                    .sorted(Comparator.comparingInt(e -> fieldIndex(e.getKey())))
                    .map(e -> deserialize(e.getValue()))
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("memory read failed, fail-open to empty history: conversationId={}", conversationId, e);
            return List.of();
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        try {
            RMap<String, String> hash = hash(conversationId);
            hash.delete();
            if (messages.isEmpty()) {
                return;
            }
            Map<String, String> payload = new LinkedHashMap<>();
            for (Message message : messages) {
                String json = serialize(message);
                if (json != null) {
                    payload.put(String.valueOf(payload.size()), json);
                }
            }
            hash.putAll(payload);
            hash.expire(MEMORY_TTL);
        } catch (Exception e) {
            log.warn("memory write failed, dropped this turn's persistence: conversationId={}", conversationId, e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        try {
            hash(conversationId).delete();
        } catch (Exception e) {
            log.warn("memory delete failed: conversationId={}", conversationId, e);
        }
    }

    private RMap<String, String> hash(String conversationId) {
        return redisson.getMap(KEY_PREFIX + conversationId, StringCodec.INSTANCE);
    }

    private String serialize(Message message) {
        String type = null;
        if (message instanceof UserMessage) {
            type = TYPE_USER;
        } else if (message instanceof AssistantMessage) {
            type = TYPE_ASSISTANT;
        }
        if (type == null) {
            log.warn("unsupported message type dropped from memory: {}", message.getMessageType());
            return null;
        }
        try {
            return objectMapper.writeValueAsString(new MessagePayload(type, message.getText()));
        } catch (Exception e) {
            log.warn("message serialize failed, dropped: type={}", type, e);
            return null;
        }
    }

    private Message deserialize(String json) {
        try {
            MessagePayload payload = objectMapper.readValue(json, MessagePayload.class);
            return switch (payload.type()) {
                case TYPE_USER -> new UserMessage(payload.content());
                case TYPE_ASSISTANT -> new AssistantMessage(payload.content());
                default -> {
                    log.warn("unknown memory payload type skipped: {}", payload.type());
                    yield null;
                }
            };
        } catch (Exception e) {
            log.warn("memory payload corrupt, single entry skipped", e);
            return null;
        }
    }

    private int fieldIndex(String field) {
        try {
            return Integer.parseInt(field);
        } catch (NumberFormatException e) {
            log.warn("memory field not an index, pushed to tail: {}", field);
            return Integer.MAX_VALUE;
        }
    }
}

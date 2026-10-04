package com.shopagent.infra.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisChatMemoryRepositoryTest {

    private static final String KEY_C1 = "chat:memory:c1";

    private final RedissonClient redisson = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    private final RMap<String, String> hash = mock(RMap.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RedisChatMemoryRepository repository =
            new RedisChatMemoryRepository(redisson, objectMapper);

    @BeforeEach
    void setUp() {
        // getMap 有 Codec/MapOptions 双重载 + 泛型方法：doReturn 绕开 when/thenReturn 的推断落到 <Object,Object>
        doReturn(hash).when(redisson).getMap(anyString(), any(org.redisson.client.codec.Codec.class));
    }

    @Test
    void saveAll_整窗替换_先清后写_TTL7天写时刷新() throws Exception {
        repository.saveAll("c1", List.of(new UserMessage("q"), new AssistantMessage("a")));

        InOrder order = inOrder(hash);
        order.verify(hash).delete();
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("0", objectMapper.writeValueAsString(new RedisChatMemoryRepository.MessagePayload("USER", "q")));
        expected.put("1", objectMapper.writeValueAsString(new RedisChatMemoryRepository.MessagePayload("ASSISTANT", "a")));
        order.verify(hash).putAll(expected);
        order.verify(hash).expire(Duration.ofDays(7));
    }

    @Test
    void saveAll_空列表_只清空不写过期键() {
        repository.saveAll("c1", List.of());

        verify(hash).delete();
        verify(hash, never()).putAll(any());
        verify(hash, never()).expire(any(Duration.class));
    }

    @Test
    void findByConversationId_按序号升序往返两态消息() {
        // 刻意乱序：hash 读出顺序不代表存储顺序，必须按 field 序号重建
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put("1", "{\"type\":\"ASSISTANT\",\"content\":\"a\"}");
        stored.put("0", "{\"type\":\"USER\",\"content\":\"q\"}");
        when(hash.readAllMap()).thenReturn(stored);

        List<Message> messages = repository.findByConversationId("c1");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo("q");
        assertThat(messages.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(1).getText()).isEqualTo("a");
    }

    @Test
    void findByConversationId_未知类型与损坏条目单条跳过() {
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put("0", "{\"type\":\"SYSTEM\",\"content\":\"s\"}");
        stored.put("1", "not-json{");
        stored.put("2", "{\"type\":\"USER\",\"content\":\"q\"}");
        when(hash.readAllMap()).thenReturn(stored);

        List<Message> messages = repository.findByConversationId("c1");

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getText()).isEqualTo("q");
    }

    @Test
    void Redis读故障_failOpen空历史() {
        when(hash.readAllMap()).thenThrow(new RedisException("connection refused"));

        assertThat(repository.findByConversationId("c1")).isEmpty();
    }

    @Test
    void Redis写故障_failOpen不外抛() {
        doThrow(new RedisException("connection refused")).when(hash).putAll(any());

        assertThatNoException().isThrownBy(() ->
                repository.saveAll("c1", List.of(new UserMessage("q"))));
    }

    @Test
    void 跨会话按conversationId分键隔离() {
        when(hash.readAllMap()).thenReturn(Map.of());

        repository.findByConversationId("c1");
        repository.findByConversationId("c2");

        verify(redisson).getMap("chat:memory:c1", org.redisson.client.codec.StringCodec.INSTANCE);
        verify(redisson).getMap("chat:memory:c2", org.redisson.client.codec.StringCodec.INSTANCE);
    }

    @Test
    void findConversationIds_剥前缀_故障返回空() {
        RKeys keys = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.getKeysStreamByPattern(anyString()))
                .thenReturn(Stream.of("chat:memory:c1", "chat:memory:c2"));

        assertThat(repository.findConversationIds()).containsExactly("c1", "c2");

        when(keys.getKeysStreamByPattern(anyString())).thenThrow(new RedisException("down"));
        assertThat(repository.findConversationIds()).isEmpty();
    }

    @Test
    void 换装集成_窗口截断语义零改动_25条入窗后整窗20条落Redis() throws Exception {
        // 真框架 MessageWindowChatMemory + 本仓库：锁定「换装只换存储、窗口语义零改动」（§2.3）
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .build();

        List<Message> burst = new java.util.ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            burst.add(new UserMessage("m" + i));
        }
        memory.add("c1", burst);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, String>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(hash).putAll(captor.capture());
        assertThat(captor.getValue()).hasSize(20);
        assertThat(captor.getValue().keySet()).containsExactlyInAnyOrder(
                "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
                "10", "11", "12", "13", "14", "15", "16", "17", "18", "19");
        // 窗口保留最后 20 条：首个字段是最早被截进窗的 m6
        String first = captor.getValue().get("0");
        assertThat(objectMapper.readValue(first, RedisChatMemoryRepository.MessagePayload.class).content())
                .isEqualTo("m6");
    }
}

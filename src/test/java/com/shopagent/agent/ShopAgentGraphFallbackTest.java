package com.shopagent.agent;

import com.shopagent.infra.resilience.LlmCircuitBreaker;
import com.shopagent.infra.resilience.RuleFallbackService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * W6D3 T3.5：熔断 OPEN 短路不调 LLM（mock ChatClient），规则回复走 token 流、persistMemory 照跑。
 * 真实 Resilience4j 对象 + 真实图编排，只 mock LLM 客户端与记忆存储（测试口径：外部环境零依赖）。
 */
class ShopAgentGraphFallbackTest {

    private final ChatClient chatClient = mock(ChatClient.class);
    private final ChatMemory chatMemory = mock(ChatMemory.class);

    @Test
    void breakerOPEN_短路不调LLM_规则回复走token流_记忆照落() throws Exception {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(2, 50, 2, Duration.ofSeconds(20), 2);
        breaker.forceOpen();
        ShopAgentGraph graph = new ShopAgentGraph(chatClient, chatMemory, breaker, new RuleFallbackService());
        when(chatMemory.get(anyString())).thenReturn(List.of());

        List<String> tokens = graph.chatStream("c1", "帮我退款", java.util.Map.of())
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(tokens).isNotNull();
        String answer = String.join("", tokens);
        assertThat(answer).isEqualTo(new RuleFallbackService().reply("帮我退款"));
        verifyNoInteractions(chatClient);
        // 降级轮也进记忆（§2.2：LLM 恢复后知道降级期说过什么）
        verify(chatMemory, times(2)).add(anyString(), any(Message.class));
    }
}

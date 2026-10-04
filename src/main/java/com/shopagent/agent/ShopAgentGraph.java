package com.shopagent.agent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.alibaba.cloud.ai.graph.serializer.std.SpringAIStateSerializer;
import com.shopagent.infra.obs.TurnCollector;
import com.shopagent.infra.obs.TurnMetricsRecorder;
import com.shopagent.infra.resilience.LlmCircuitBreaker;
import com.shopagent.infra.resilience.RuleFallbackService;
import com.shopagent.tools.support.ToolContextKeys;
import com.shopagent.tools.support.ToolEventListener;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Graph 编排层（W5D4 §2.5 迁移形态最小化）：START → loadMemory → chat → persistMemory → END。
 * 记忆从 ChatClient Advisor 显式化为图节点（窗口语义沿用同一个 MessageWindowChatMemory，行为等价）；
 * token 级流式走节点内旁路（chatClient.stream() → Sinks.Many → SSE）——图状态只承载整段 answer。
 *
 * spike 结论（为什么 sink/toolContext 不进 state）：图在节点间用序列化器**克隆 state**
 * （JacksonStateSerializer.cloneObject），Sinks.Many/带 lambda 的 ToolContext 一进 state 就炸
 * JsonMappingException——响应式旁路对象必须走 invocation 持有表（UUID 键），state 只留可序列化值。
 * 这条边界本身就是「token 流不穿过图状态」的实证（§2.5 spike 核心问题）。
 *
 * 断连语义变化（优于直连版）：客户端 SSE 断开只取消订阅，图在后台跑到 END，记忆照常落盘——
 * 重发同句由交易幂等闸收束（W3D4「流式断连重放」场景的前置改进）。
 */
@Component
public class ShopAgentGraph {

    private static final Logger log = LoggerFactory.getLogger(ShopAgentGraph.class);

    static final String KEY_CONVERSATION_ID = "conversation_id";
    static final String KEY_MESSAGE = "message";
    static final String KEY_INVOCATION_ID = "invocation_id";
    static final String KEY_HISTORY = "history";
    static final String KEY_ANSWER = "answer";

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final LlmCircuitBreaker llmCircuitBreaker;
    private final RuleFallbackService ruleFallbackService;
    private final TurnMetricsRecorder turnMetricsRecorder;
    private final CompiledGraph compiledGraph;
    // 请求态旁路通道：sink/toolContext 不可序列化，只能随 invocation id 在持有表中传递（见类注释）
    private final Map<String, InvocationContext> invocations = new ConcurrentHashMap<>();

    private record InvocationContext(Sinks.Many<String> tokens, Map<String, Object> toolContext,
                                     TurnCollector collector) {}

    // 降级回复的流式体感：按小片推进模拟 token 流（§2.2：answer 流照发，前端打字机零改动）
    private static final int FALLBACK_CHUNK = 4;
    private static final long FALLBACK_CHUNK_DELAY_MS = 40;

    public ShopAgentGraph(ChatClient chatClient, ChatMemory chatMemory,
                          LlmCircuitBreaker llmCircuitBreaker, RuleFallbackService ruleFallbackService,
                          TurnMetricsRecorder turnMetricsRecorder) throws Exception {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.llmCircuitBreaker = llmCircuitBreaker;
        this.ruleFallbackService = ruleFallbackService;
        this.turnMetricsRecorder = turnMetricsRecorder;
        StateGraph graph = new StateGraph(() -> {
            Map<String, KeyStrategy> keys = new HashMap<>();
            keys.put(KEY_CONVERSATION_ID, new ReplaceStrategy());
            keys.put(KEY_MESSAGE, new ReplaceStrategy());
            keys.put(KEY_INVOCATION_ID, new ReplaceStrategy());
            keys.put(KEY_HISTORY, new ReplaceStrategy());
            keys.put(KEY_ANSWER, new ReplaceStrategy());
            return keys;
        }, new SpringAIStateSerializer());
        graph.addNode("loadMemory", AsyncNodeAction.node_async(this::loadMemory));
        graph.addNode("chat", AsyncNodeAction.node_async(this::chat));
        graph.addNode("persistMemory", AsyncNodeAction.node_async(this::persistMemory));
        graph.addEdge(StateGraph.START, "loadMemory");
        graph.addEdge("loadMemory", "chat");
        graph.addEdge("chat", "persistMemory");
        graph.addEdge("persistMemory", StateGraph.END);
        this.compiledGraph = graph.compile();
        log.info("ShopAgentGraph compiled: START -> loadMemory -> chat -> persistMemory -> END");
    }

    /**
     * 单轮对话 answer token 流（thinking/tool/done 等 SSE 事件类型由控制器组装，前端零改动）。
     * 图在 boundedElastic 上跑到 END 后关闭 token 流；LLM 异常经 token 流 error 传导给控制器。
     */
    public Flux<String> chatStream(String conversationId, String message, Map<String, Object> toolContext) {
        String invocationId = UUID.randomUUID().toString();
        Sinks.Many<String> tokens = Sinks.many().unicast().onBackpressureBuffer();
        TurnCollector collector = new TurnCollector();
        wrapToolListener(toolContext, collector);
        invocations.put(invocationId, new InvocationContext(tokens, toolContext, collector));
        Flux.defer(() -> {
            try {
                compiledGraph.stream(Map.of(
                                KEY_CONVERSATION_ID, conversationId,
                                KEY_MESSAGE, message,
                                KEY_INVOCATION_ID, invocationId))
                        .blockLast();
                tokens.tryEmitComplete();
            } catch (Exception e) {
                collector.markError();
                tokens.tryEmitError(e);
            } finally {
                invocations.remove(invocationId);
                // W6D4：图内轮在收尾记账（限流轮在 controller 记——观测是横切，跨层记账）
                turnMetricsRecorder.record(collector.materialize(conversationId,
                        String.valueOf(toolContext.getOrDefault(ToolContextKeys.USER_ID, ""))));
            }
            return Flux.empty();
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
        return tokens.asFlux();
    }

    /** 工具计数：包装既有 SSE 监听器（tools/ 零改动），计数后照常转发，前端事件零变化 */
    private void wrapToolListener(Map<String, Object> toolContext, TurnCollector collector) {
        Object existing = toolContext.get(ToolContextKeys.TOOL_EVENT_LISTENER);
        if (existing instanceof ToolEventListener listener) {
            toolContext.put(ToolContextKeys.TOOL_EVENT_LISTENER, (ToolEventListener) message -> {
                collector.toolEvent(message);
                listener.onToolEvent(message);
            });
        }
    }

    private Map<String, Object> loadMemory(OverAllState state) {
        String conversationId = (String) state.value(KEY_CONVERSATION_ID).orElse("");
        List<Message> history = chatMemory.get(conversationId);
        return Map.of(KEY_HISTORY, history);
    }

    /**
     * W6D3 熔断挂点（§2.2）：execute 包住整段 LLM 调用（token 流照常从 sink 旁路发出，
     * 熔断器只统计整次调用成败）；OPEN 时 CallNotPermittedException 短路——不发起 LLM 调用直走降级；
     * CLOSED 下的真实失败（连接拒绝/超时/5xx/流中断）由熔断器记入滑窗后透传，同样走降级。
     * 两条降级路径都把规则回复当 answer 聚合返回 → persistMemory 节点照跑（降级轮也进记忆，
     * LLM 恢复后知道降级期说过什么）。
     */
    private Map<String, Object> chat(OverAllState state) {
        String message = (String) state.value(KEY_MESSAGE).orElse("");
        List<Message> history = state.value(KEY_HISTORY, List.class).orElse(List.of());
        InvocationContext invocation = invocations.get(state.value(KEY_INVOCATION_ID).orElse(""));
        if (invocation == null) {
            throw new IllegalStateException("invocation context missing (broken graph invocation)");
        }
        String answer;
        long llmStart = System.nanoTime();
        try {
            answer = llmCircuitBreaker.execute(() -> streamAnswer(message, history, invocation));
        } catch (CallNotPermittedException e) {
            log.warn("llm breaker OPEN, rule fallback for this turn: conversationId={}",
                    state.value(KEY_CONVERSATION_ID).orElse(""));
            answer = streamFallback(message, invocation);
        } catch (Exception e) {
            log.warn("llm call failed, rule fallback for this turn: conversationId={}",
                    state.value(KEY_CONVERSATION_ID).orElse(""), e);
            answer = streamFallback(message, invocation);
        } finally {
            // llmMs 含失败轮（降级前的等待/尝试时长）：OPEN 短路≈0，一眼区分短路与真实尝试
            invocation.collector().llmFinished((System.nanoTime() - llmStart) / 1_000_000);
        }
        return Map.of(KEY_ANSWER, answer);
    }

    private String streamAnswer(String message, List<Message> history, InvocationContext invocation) {
        StringBuilder answer = new StringBuilder();
        chatClient.prompt()
                .messages(history)
                .user(message)
                .toolContext(invocation.toolContext())
                .stream()
                .chatResponse()
                // 流式 usage（stream-usage 开启）挂在最后一个 chunk 的 metadata 上，逐 chunk 捕获即可
                .doOnNext(response -> {
                    captureUsage(invocation.collector(), response);
                    String text = textOf(response);
                    if (text != null && !text.isEmpty()) {
                        answer.append(text);
                        invocation.collector().onToken();
                        invocation.tokens().tryEmitNext(text);
                    }
                })
                .blockLast();
        invocation.collector().answer(answer.toString());
        return answer.toString();
    }

    private void captureUsage(TurnCollector collector, ChatResponse response) {
        if (response.getMetadata() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage != null) {
            collector.usage(usage.getPromptTokens(), usage.getCompletionTokens());
        }
    }

    /** 降级回复按意图分类取话术，小片推进模拟 token 流，打字机体感与正常轮一致 */
    private String streamFallback(String message, InvocationContext invocation) {
        invocation.collector().markDegraded();
        String reply = ruleFallbackService.reply(message);
        char[] chars = reply.toCharArray();
        for (int i = 0; i < chars.length; i += FALLBACK_CHUNK) {
            int end = Math.min(i + FALLBACK_CHUNK, chars.length);
            invocation.tokens().tryEmitNext(new String(chars, i, end - i));
            invocation.collector().onToken();
            try {
                Thread.sleep(FALLBACK_CHUNK_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        invocation.collector().answer(reply);
        return reply;
    }

    private Map<String, Object> persistMemory(OverAllState state) {
        String conversationId = (String) state.value(KEY_CONVERSATION_ID).orElse("");
        String message = (String) state.value(KEY_MESSAGE).orElse("");
        String answer = (String) state.value(KEY_ANSWER).orElse("");
        chatMemory.add(conversationId, new UserMessage(message));
        if (!answer.isBlank()) {
            chatMemory.add(conversationId, new AssistantMessage(answer));
        }
        return Map.of();
    }

    private static String textOf(ChatResponse response) {
        var result = response.getResult();
        return (result == null || result.getOutput() == null) ? null : result.getOutput().getText();
    }
}

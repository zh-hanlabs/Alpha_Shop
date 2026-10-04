package com.shopagent.agent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.alibaba.cloud.ai.graph.serializer.std.SpringAIStateSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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
    private final CompiledGraph compiledGraph;
    // 请求态旁路通道：sink/toolContext 不可序列化，只能随 invocation id 在持有表中传递（见类注释）
    private final Map<String, InvocationContext> invocations = new ConcurrentHashMap<>();

    private record InvocationContext(Sinks.Many<String> tokens, Map<String, Object> toolContext) {}

    public ShopAgentGraph(ChatClient chatClient, ChatMemory chatMemory) throws Exception {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
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
        invocations.put(invocationId, new InvocationContext(tokens, toolContext));
        Flux.defer(() -> {
            try {
                compiledGraph.stream(Map.of(
                                KEY_CONVERSATION_ID, conversationId,
                                KEY_MESSAGE, message,
                                KEY_INVOCATION_ID, invocationId))
                        .blockLast();
                tokens.tryEmitComplete();
            } catch (Exception e) {
                tokens.tryEmitError(e);
            } finally {
                invocations.remove(invocationId);
            }
            return Flux.empty();
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
        return tokens.asFlux();
    }

    private Map<String, Object> loadMemory(OverAllState state) {
        String conversationId = (String) state.value(KEY_CONVERSATION_ID).orElse("");
        List<Message> history = chatMemory.get(conversationId);
        return Map.of(KEY_HISTORY, history);
    }

    private Map<String, Object> chat(OverAllState state) {
        String message = (String) state.value(KEY_MESSAGE).orElse("");
        List<Message> history = state.value(KEY_HISTORY, List.class).orElse(List.of());
        InvocationContext invocation = invocations.get(state.value(KEY_INVOCATION_ID).orElse(""));
        if (invocation == null) {
            throw new IllegalStateException("invocation context missing (broken graph invocation)");
        }
        StringBuilder answer = new StringBuilder();
        chatClient.prompt()
                .messages(history)
                .user(message)
                .toolContext(invocation.toolContext())
                .stream()
                .chatResponse()
                .map(ShopAgentGraph::textOf)
                .filter(text -> text != null && !text.isEmpty())
                // 节点内阻塞收集：persistMemory 必须等 answer 聚合完才有资格执行（图边序即因果序）
                .doOnNext(token -> {
                    answer.append(token);
                    invocation.tokens().tryEmitNext(token);
                })
                .blockLast();
        return Map.of(KEY_ANSWER, answer.toString());
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

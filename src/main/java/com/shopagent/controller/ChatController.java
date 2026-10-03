package com.shopagent.controller;

import com.shopagent.infra.idempotent.IdempotentKeys;
import com.shopagent.tools.support.ToolContextKeys;
import com.shopagent.tools.support.ToolEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    // W1 无鉴权：userId 由调用方直传（缺省即演示用户 u1001），W8+ 换登录态注入
    private static final String DEFAULT_USER_ID = "u1001";

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public record ChatRequest(String conversationId, String message, String userId) {}

    @PostMapping("/api/chat")
    public String chat(@RequestBody ChatRequest request) {
        return chatClient.prompt()
                .user(request.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, request.conversationId()))
                .toolContext(buildToolContext(request, null))
                .call().content();
    }

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
        // 工具执行在模型流内部发生（调用块不进入流），事件经 ToolContext 回调旁路推送
        Sinks.Many<ServerSentEvent<String>> toolEvents = Sinks.many().unicast().onBackpressureBuffer();
        Map<String, Object> toolContext = buildToolContext(request,
                msg -> toolEvents.tryEmitNext(ServerSentEvent.builder(msg).event("tool").build()));

        Flux<ServerSentEvent<String>> chatEvents = chatClient.prompt()
                .user(request.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, request.conversationId()))
                .toolContext(toolContext)
                .stream()
                .chatResponse()
                .map(ChatController::toAnswerEvent)
                .filter(sse -> sse.data() != null && !sse.data().isEmpty())
                // 工具事件先于其后的回答块（工具执行完才有最终回复），chat 流结束即可安全关闭事件通道
                .doFinally(signal -> toolEvents.tryEmitComplete());

        return Flux.concat(
                        Flux.just(ServerSentEvent.builder("思考中…").event("thinking").build()),
                        Flux.merge(chatEvents, toolEvents.asFlux()),
                        Flux.just(ServerSentEvent.builder("[DONE]").event("done").build()))
                .onErrorResume(e -> {
                    log.error("chat stream failed, conversationId={}", request.conversationId(), e);
                    return Flux.just(
                            ServerSentEvent.builder("服务开小差了，请稍后重试～").event("error").build(),
                            ServerSentEvent.builder("[DONE]").event("done").build());
                });
    }

    private Map<String, Object> buildToolContext(ChatRequest request, ToolEventListener listener) {
        Map<String, Object> context = new HashMap<>();
        context.put(ToolContextKeys.USER_ID, resolveUserId(request));
        // 会话与指令摘要是交易幂等键的组成部分（W3D3）：缺失时交易工具 fail-closed 拒绝执行
        context.put(ToolContextKeys.CONVERSATION_ID,
                Objects.requireNonNullElse(request.conversationId(), ""));
        context.put(ToolContextKeys.INSTRUCTION_DIGEST,
                IdempotentKeys.instructionDigest(request.message()));
        if (listener != null) {
            context.put(ToolContextKeys.TOOL_EVENT_LISTENER, listener);
        }
        return context;
    }

    private String resolveUserId(ChatRequest request) {
        return (request.userId() == null || request.userId().isBlank())
                ? DEFAULT_USER_ID
                : request.userId().trim();
    }

    private static ServerSentEvent<String> toAnswerEvent(ChatResponse chunk) {
        var result = chunk.getResult();
        String text = (result == null || result.getOutput() == null) ? null : result.getOutput().getText();
        return text == null ? ServerSentEvent.builder("").build()
                : ServerSentEvent.builder(text).event("answer").build();
    }
}

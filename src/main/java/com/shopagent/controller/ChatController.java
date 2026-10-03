package com.shopagent.controller;

import com.shopagent.tools.support.ToolContextKeys;
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
                .toolContext(Map.of(ToolContextKeys.USER_ID, resolveUserId(request)))
                .call().content();
    }

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
        // 工具执行在模型流内部发生（调用块不进入流），事件经 ToolContext 回调旁路推送
        Sinks.Many<ServerSentEvent<String>> toolEvents = Sinks.many().unicast().onBackpressureBuffer();
        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(ToolContextKeys.USER_ID, resolveUserId(request));
        toolContext.put(ToolContextKeys.TOOL_EVENT_LISTENER,
                (com.shopagent.tools.support.ToolEventListener) msg ->
                        toolEvents.tryEmitNext(ServerSentEvent.builder(msg).event("tool").build()));

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
                        Flux.just(ServerSentEvent.builder("[DONE]").event("done").build()));
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

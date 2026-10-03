package com.shopagent.controller;

import com.shopagent.tools.support.ToolContextKeys;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
public class ChatController {

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
        return chatClient.prompt()
                .user(request.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, request.conversationId()))
                .toolContext(Map.of(ToolContextKeys.USER_ID, resolveUserId(request)))
                .stream()
                .chatResponse()
                .map(ChatController::toAnswerEvent)
                .filter(sse -> sse.data() != null && !sse.data().isEmpty());
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

package com.shopagent.controller;

import com.shopagent.tools.support.ToolContextKeys;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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
        String userId = (request.userId() == null || request.userId().isBlank())
                ? DEFAULT_USER_ID
                : request.userId().trim();
        return chatClient.prompt()
                .user(request.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, request.conversationId()))
                .toolContext(Map.of(ToolContextKeys.USER_ID, userId))
                .call().content();
    }
}

package com.shopagent.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping("/api/chat")
    public String chat(@RequestParam String query, @RequestParam String conversationId) {
        return chatClient.prompt()
                .user(query)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call().content();
    }
}

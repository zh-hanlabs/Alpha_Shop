package com.shopagent.controller;

import com.shopagent.agent.ShopAgentGraph;
import com.shopagent.infra.idempotent.IdempotentKeys;
import com.shopagent.infra.resilience.RateLimitGuard;
import com.shopagent.tools.support.ToolContextKeys;
import com.shopagent.tools.support.ToolEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    // W1 无鉴权：userId 由调用方直传（缺省即演示用户 u1001），W8+ 换登录态注入
    private static final String DEFAULT_USER_ID = "u1001";

    // 限流话术（§2.1 定稿原文）：与内部错误的「服务开小差了」区分开，便于冒烟断言
    private static final String RATE_LIMITED_MSG = "当前咨询人数较多，请稍后再试～";

    private final ShopAgentGraph shopAgentGraph;
    private final RateLimitGuard rateLimitGuard;

    // W5D4 起接入层只做 SSE 事件组装，编排走 ShopAgentGraph（loadMemory→chat→persistMemory）；
    // W6D1 起入口前置 RateLimitGuard：被限流直接短路，不进图（零记忆读、零 LLM 调用）
    public ChatController(ShopAgentGraph shopAgentGraph, RateLimitGuard rateLimitGuard) {
        this.shopAgentGraph = shopAgentGraph;
        this.rateLimitGuard = rateLimitGuard;
    }

    public record ChatRequest(String conversationId, String message, String userId) {}

    @PostMapping("/api/chat")
    public ResponseEntity<String> chat(@RequestBody ChatRequest request) {
        String userId = resolveUserId(request);
        RateLimitGuard.Verdict verdict = rateLimitGuard.tryAcquire(userId);
        if (verdict != RateLimitGuard.Verdict.ALLOWED) {
            // D4 观测在此记 RATE_LIMITED 轮（限流轮不进图，controller 层记账）
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(RATE_LIMITED_MSG);
        }
        return ResponseEntity.ok(shopAgentGraph
                .chatStream(resolveConversationId(request), request.message(), buildToolContext(request, null))
                .collect(Collectors.joining())
                .block());
    }

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
        String userId = resolveUserId(request);
        RateLimitGuard.Verdict verdict = rateLimitGuard.tryAcquire(userId);
        if (verdict != RateLimitGuard.Verdict.ALLOWED) {
            // D4 观测在此记 RATE_LIMITED 轮；SSE error+done 前端零改动（事件契约同 onErrorResume）
            log.info("chat stream rate limited: userId={} verdict={}", userId, verdict);
            return Flux.just(
                    ServerSentEvent.builder(RATE_LIMITED_MSG).event("error").build(),
                    ServerSentEvent.builder("[DONE]").event("done").build());
        }

        // 工具执行在模型流内部发生（调用块不进入流），事件经 ToolContext 回调旁路推送
        Sinks.Many<ServerSentEvent<String>> toolEvents = Sinks.many().unicast().onBackpressureBuffer();
        Map<String, Object> toolContext = buildToolContext(request,
                msg -> toolEvents.tryEmitNext(ServerSentEvent.builder(msg).event("tool").build()));

        Flux<ServerSentEvent<String>> answerEvents = shopAgentGraph
                .chatStream(resolveConversationId(request), request.message(), toolContext)
                .map(text -> ServerSentEvent.builder(text).event("answer").build())
                // 工具事件先于其后的回答块（工具执行完才有最终回复），answer 流结束即可安全关闭事件通道
                .doFinally(signal -> toolEvents.tryEmitComplete());

        return Flux.concat(
                        Flux.just(ServerSentEvent.builder("思考中…").event("thinking").build()),
                        Flux.merge(answerEvents, toolEvents.asFlux()),
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

    private String resolveConversationId(ChatRequest request) {
        return Objects.requireNonNullElse(request.conversationId(), "");
    }

    private String resolveUserId(ChatRequest request) {
        return (request.userId() == null || request.userId().isBlank())
                ? DEFAULT_USER_ID
                : request.userId().trim();
    }
}

package com.shopagent.infra.obs;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单轮采集器：图节点与限流闸在轮内就地埋点，chatStream 收尾 materialize 成不可变 TurnMetrics。
 * 生命周期 = 一次图调用（invocation 持有），非线程安全（单轮单线程驱动）。
 */
public final class TurnCollector {

    private static final long NANOS_PER_MS = 1_000_000L;

    private final long startNanos = System.nanoTime();
    private String outcome = TurnMetrics.OUTCOME_OK;
    private long llmMs;
    private Long firstTokenMs;
    private Long promptTokens;
    private Long completionTokens;
    private String answer = "";
    private final Map<String, Integer> toolCalls = new LinkedHashMap<>();

    public void onToken() {
        if (firstTokenMs == null) {
            firstTokenMs = elapsedMs();
        }
    }

    public void llmFinished(long millis) {
        this.llmMs = millis;
    }

    /** usage 缺席（null/0）保持 null，即 N/A 口径；不可得时不硬凑 */
    public void usage(Integer promptTokens, Integer completionTokens) {
        if (promptTokens != null && promptTokens > 0) {
            this.promptTokens = promptTokens.longValue();
        }
        if (completionTokens != null && completionTokens > 0) {
            this.completionTokens = completionTokens.longValue();
        }
    }

    public void answer(String text) {
        this.answer = text == null ? "" : text;
    }

    public void markDegraded() {
        outcome = TurnMetrics.OUTCOME_DEGRADED;
    }

    public void markError() {
        outcome = TurnMetrics.OUTCOME_ERROR;
    }

    /**
     * 工具计数：工具入口发布的中文事件是给前端看的散文（W1 铁律 tools/ 零改动），
     * 这里按 9 个已知工具的入口话术前缀映射工具名；顺序敏感——物流查询事件包含
     * 「正在查询订单」前缀，必须先于订单查询判定。未知事件计 tool:unknown 不丢数。
     */
    public void toolEvent(String message) {
        toolCalls.merge(toolNameOf(message), 1, Integer::sum);
    }

    private static String toolNameOf(String message) {
        if (message == null) {
            return "tool:unknown";
        }
        if (message.startsWith("正在下单")) {
            return "PlaceOrderTool";
        }
        if (message.contains("申请退款")) {
            return "RefundOrderTool";
        }
        if (message.startsWith("正在取消订单")) {
            return "CancelOrderTool";
        }
        if (message.startsWith("正在查询知识库")) {
            return "KnowledgeSearchTool";
        }
        if (message.startsWith("正在搜索商品")) {
            return "ProductSearchTool";
        }
        if (message.startsWith("正在查询商品详情")) {
            return "ProductDetailTool";
        }
        if (message.startsWith("正在查询您的最近订单")) {
            return "RecentOrdersTool";
        }
        if (message.contains("的物流")) {
            return "LogisticsQueryTool";
        }
        if (message.startsWith("正在查询订单")) {
            return "OrderQueryTool";
        }
        return "tool:unknown";
    }

    public TurnMetrics materialize(String conversationId, String userId) {
        return new TurnMetrics(Instant.now().toString(), conversationId, userId, outcome,
                elapsedMs(), llmMs, firstTokenMs, promptTokens, completionTokens,
                answer.length(), Map.copyOf(toolCalls));
    }

    private long elapsedMs() {
        return (System.nanoTime() - startNanos) / NANOS_PER_MS;
    }
}

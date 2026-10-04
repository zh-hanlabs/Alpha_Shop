package com.shopagent.infra.obs;

import java.util.Map;

/**
 * 轮级指标（W6D4 §2.4 定稿）：一条对话轮的结构化决策记录，W7 JMeter 压测的数据源。
 * outcome 四态：OK / RATE_LIMITED / DEGRADED / ERROR——限流轮在 controller 记、图内轮在
 * chatStream 收尾记（观测是横切，跨层记账）。
 * token 口径：promptTokens/completionTokens 取流式 usage（stream-usage 开启后由模型回传），
 * 模型不可得时为 null（N/A），此时以 answerChars 字符数作估算代理——面试讲清口径比硬凑数字好。
 */
public record TurnMetrics(
        String ts,
        String conversationId,
        String userId,
        String outcome,
        long totalMs,
        long llmMs,
        Long firstTokenMs,
        Long promptTokens,
        Long completionTokens,
        Integer answerChars,
        Map<String, Integer> toolCalls) {

    public static final String OUTCOME_OK = "OK";
    public static final String OUTCOME_RATE_LIMITED = "RATE_LIMITED";
    public static final String OUTCOME_DEGRADED = "DEGRADED";
    public static final String OUTCOME_ERROR = "ERROR";
}

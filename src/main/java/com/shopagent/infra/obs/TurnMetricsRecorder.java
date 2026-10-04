package com.shopagent.infra.obs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轮级指标落点（W6D4 §2.4）：单行 JSON 日志（W7 JMeter 从日志文件取数的唯一事实源）
 * + 环形缓冲最近 100 轮（dev 端点查询用）。
 * 砍单线：不上 Micrometer/Prometheus/Grafana——主计划 §6 就是「决策日志结构化」，不做全链路 trace。
 */
@Component
public class TurnMetricsRecorder {

    private static final Logger log = LoggerFactory.getLogger(TurnMetricsRecorder.class);

    private static final int RING_CAPACITY = 100;

    private final ObjectMapper objectMapper;
    private final Deque<TurnMetrics> ring = new ArrayDeque<>();
    private final Object lock = new Object();

    public TurnMetricsRecorder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void record(TurnMetrics metrics) {
        synchronized (lock) {
            ring.addLast(metrics);
            while (ring.size() > RING_CAPACITY) {
                ring.removeFirst();
            }
        }
        log.info(turnJson(metrics));
    }

    /** 限流轮不进图（零记忆读零 LLM 调用），controller 层直接记账：totalMs≈0、无 token 无工具 */
    public void rateLimited(String conversationId, String userId) {
        record(new TurnMetrics(java.time.Instant.now().toString(), conversationId, userId,
                TurnMetrics.OUTCOME_RATE_LIMITED, 0, 0, null, null, null, 0, Map.of()));
    }

    public List<TurnMetrics> turns() {
        synchronized (lock) {
            return List.copyOf(ring);
        }
    }

    public Map<String, Object> stats() {
        List<TurnMetrics> snapshot = turns();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("turns", snapshot.size());
        Map<String, Long> byOutcome = new LinkedHashMap<>();
        Map<String, Integer> toolDistribution = new LinkedHashMap<>();
        long totalMs = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        int usageHits = 0;
        for (TurnMetrics turn : snapshot) {
            byOutcome.merge(turn.outcome(), 1L, Long::sum);
            totalMs += turn.totalMs();
            if (turn.promptTokens() != null) {
                promptTokens += turn.promptTokens();
                usageHits++;
            }
            if (turn.completionTokens() != null) {
                completionTokens += turn.completionTokens();
            }
            turn.toolCalls().forEach((tool, count) -> toolDistribution.merge(tool, count, Integer::sum));
        }
        stats.put("byOutcome", byOutcome);
        stats.put("avgTotalMs", snapshot.isEmpty() ? 0 : totalMs / snapshot.size());
        stats.put("totalPromptTokens", promptTokens);
        stats.put("totalCompletionTokens", completionTokens);
        // usage 覆盖率：N/A 口径的可视化（多少轮拿到了模型真 token 数）
        stats.put("usageHits", usageHits);
        stats.put("toolCallDistribution", toolDistribution);
        return stats;
    }

    private String turnJson(TurnMetrics metrics) {
        try {
            return objectMapper.writeValueAsString(metrics);
        } catch (Exception e) {
            // 观测是旁路：序列化失败不能拖垮对话轮，退化为最小可读行
            return "{\"outcome\":\"" + metrics.outcome() + "\",\"jsonError\":true}";
        }
    }
}

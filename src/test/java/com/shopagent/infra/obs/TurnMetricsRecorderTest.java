package com.shopagent.infra.obs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TurnMetricsRecorderTest {

    private final TurnMetricsRecorder recorder = new TurnMetricsRecorder(new ObjectMapper());

    private TurnMetrics turn(String outcome, long totalMs, Long prompt, Long completion,
                             Map<String, Integer> toolCalls) {
        return new TurnMetrics(Instant.now().toString(), "c1", "u1", outcome,
                totalMs, totalMs, totalMs > 0 ? 10L : null, prompt, completion, 42, toolCalls);
    }

    @Test
    void record入环_turns快照按序返回() {
        recorder.record(turn("OK", 100, null, null, Map.of()));
        recorder.record(turn("DEGRADED", 50, null, null, Map.of()));

        assertThat(recorder.turns()).hasSize(2);
        assertThat(recorder.turns().get(0).outcome()).isEqualTo("OK");
        assertThat(recorder.turns().get(1).outcome()).isEqualTo("DEGRADED");
    }

    @Test
    void 环形缓冲100_超容淘汰最旧() {
        for (int i = 1; i <= 101; i++) {
            recorder.record(turn("OK", i, null, null, Map.of()));
        }

        assertThat(recorder.turns()).hasSize(100);
        // 最旧的第 1 轮被淘汰，尾部保留最近 100 轮
        assertThat(recorder.turns().get(0).totalMs()).isEqualTo(2);
        assertThat(recorder.turns().get(99).totalMs()).isEqualTo(101);
    }

    @Test
    void rateLimited记账_轮级字段全空() {
        recorder.rateLimited("c9", "u9");

        TurnMetrics limited = recorder.turns().get(0);
        assertThat(limited.outcome()).isEqualTo("RATE_LIMITED");
        assertThat(limited.totalMs()).isZero();
        assertThat(limited.firstTokenMs()).isNull();
        assertThat(limited.toolCalls()).isEmpty();
    }

    @Test
    void stats_轮数outcome分布平均耗时token合计工具分布() {
        recorder.record(turn("OK", 100, 10L, 20L, Map.of("ProductSearchTool", 2)));
        recorder.record(turn("OK", 300, null, null, Map.of("ProductSearchTool", 1, "PlaceOrderTool", 1)));
        recorder.record(turn("RATE_LIMITED", 0, null, null, Map.of()));

        Map<String, Object> stats = recorder.stats();

        assertThat(stats.get("turns")).isEqualTo(3);
        assertThat((Map<String, Long>) stats.get("byOutcome"))
                .containsEntry("OK", 2L)
                .containsEntry("RATE_LIMITED", 1L);
        assertThat(stats.get("avgTotalMs")).isEqualTo(133L);
        assertThat(stats.get("totalPromptTokens")).isEqualTo(10L);
        assertThat(stats.get("totalCompletionTokens")).isEqualTo(20L);
        assertThat(stats.get("usageHits")).isEqualTo(1);
        assertThat((Map<String, Integer>) stats.get("toolCallDistribution"))
                .containsEntry("ProductSearchTool", 3)
                .containsEntry("PlaceOrderTool", 1);
    }

    @Test
    void 单行JSON日志可序列化_含观测关键字段() throws Exception {
        recorder.record(turn("OK", 100, 10L, 20L, Map.of("ProductSearchTool", 1)));
        String json = new ObjectMapper().writeValueAsString(recorder.turns().get(0));

        assertThat(json).contains("\"outcome\":\"OK\"")
                .contains("\"promptTokens\":10")
                .contains("\"toolCalls\":{\"ProductSearchTool\":1}")
                .doesNotContain("\n");
    }
}

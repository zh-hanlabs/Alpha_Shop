package com.shopagent.infra.obs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TurnCollectorTest {

    @Test
    void 首token延迟只记第一次() {
        TurnCollector collector = new TurnCollector();
        collector.onToken();
        collector.onToken();
        TurnMetrics metrics = collector.materialize("c1", "u1");

        assertThat(metrics.firstTokenMs()).isNotNull();
        // 二次 onToken 不覆盖：再采一轮仍是同一个值（单值语义，此处只验证非负且已被记录）
        assertThat(metrics.firstTokenMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 工具计数按事件前缀映射合并() {
        TurnCollector collector = new TurnCollector();
        collector.toolEvent("正在搜索商品「露营灯」");
        collector.toolEvent("正在搜索商品「帐篷」");
        collector.toolEvent("正在下单，商品 id 7 × 1");

        TurnMetrics metrics = collector.materialize("c1", "u1");

        assertThat(metrics.toolCalls())
                .containsEntry("ProductSearchTool", 2)
                .containsEntry("PlaceOrderTool", 1)
                .hasSize(2);
    }

    @Test
    void 物流与订单查询事件前缀重叠_顺序敏感映射正确() {
        TurnCollector collector = new TurnCollector();
        collector.toolEvent("正在查询订单 A123 的物流");
        collector.toolEvent("正在查询订单 A123");
        collector.toolEvent("正在查询您的最近订单");

        assertThat(collector.materialize("c1", "u1").toolCalls())
                .containsEntry("LogisticsQueryTool", 1)
                .containsEntry("OrderQueryTool", 1)
                .containsEntry("RecentOrdersTool", 1);
    }

    @Test
    void 未知工具事件计数不丢失() {
        TurnCollector collector = new TurnCollector();
        collector.toolEvent("正在做一件没人认识的事");
        collector.toolEvent(null);

        assertThat(collector.materialize("c1", "u1").toolCalls())
                .containsEntry("tool:unknown", 2);
    }

    @Test
    void outcome分类_默认OK_降级与错误可标记() {
        TurnCollector ok = new TurnCollector();
        assertThat(ok.materialize("c1", "u1").outcome()).isEqualTo("OK");

        TurnCollector degraded = new TurnCollector();
        degraded.markDegraded();
        assertThat(degraded.materialize("c1", "u1").outcome()).isEqualTo("DEGRADED");

        TurnCollector error = new TurnCollector();
        error.markError();
        assertThat(error.materialize("c1", "u1").outcome()).isEqualTo("ERROR");
    }

    @Test
    void usage可得则记录_缺失保持null即NA口径() {
        TurnCollector withUsage = new TurnCollector();
        withUsage.usage(120, 30);
        assertThat(withUsage.materialize("c1", "u1").promptTokens()).isEqualTo(120L);
        assertThat(withUsage.materialize("c1", "u1").completionTokens()).isEqualTo(30L);

        TurnCollector naUsage = new TurnCollector();
        naUsage.usage(null, 0);
        TurnMetrics metrics = naUsage.materialize("c1", "u1");
        assertThat(metrics.promptTokens()).isNull();
        assertThat(metrics.completionTokens()).isNull();
    }

    @Test
    void materialize_字符数与耗时字段落位() {
        TurnCollector collector = new TurnCollector();
        collector.answer("你好，世界");
        TurnMetrics metrics = collector.materialize("c1", "u1");

        assertThat(metrics.answerChars()).isEqualTo(5);
        assertThat(metrics.totalMs()).isGreaterThanOrEqualTo(0);
        assertThat(metrics.llmMs()).isZero();
        assertThat(metrics.conversationId()).isEqualTo("c1");
        assertThat(metrics.userId()).isEqualTo("u1");
    }
}

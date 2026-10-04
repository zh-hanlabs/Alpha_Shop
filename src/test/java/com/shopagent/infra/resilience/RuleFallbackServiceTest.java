package com.shopagent.infra.resilience;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleFallbackServiceTest {

    private final RuleFallbackService service = new RuleFallbackService();

    @Test
    void 交易类命中只引导_话术绝无执行性表述() {
        for (String message : new String[]{"帮我下单一个露营灯", "我要退款", "取消我的订单", "这个买下了"}) {
            assertThat(service.classify(message)).isEqualTo(RuleFallbackService.Intent.TRADE);
            String reply = service.reply(message);
            assertThat(reply).contains("稍后再");
            // 安全红线：降级话术绝不能让用户误以为交易已执行
            assertThat(reply).doesNotContain("已下单", "已退款", "已取消", "已为您");
        }
    }

    @Test
    void 交易类判定优先于其他意图() {
        // 同时命中交易动作词与商品名词词：红线要求宁可错杀
        assertThat(service.classify("下单买一个便宜的商品")).isEqualTo(RuleFallbackService.Intent.TRADE);
    }

    @Test
    void 订单物流商品三类命中() {
        assertThat(service.classify("查一下我的订单")).isEqualTo(RuleFallbackService.Intent.ORDER);
        assertThat(service.classify("快递到哪了")).isEqualTo(RuleFallbackService.Intent.LOGISTICS);
        assertThat(service.classify("这个多少钱")).isEqualTo(RuleFallbackService.Intent.PRODUCT);
    }

    @Test
    void 未命中走兜底_null安全() {
        assertThat(service.classify("今天天气怎么样")).isEqualTo(RuleFallbackService.Intent.DEFAULT);
        assertThat(service.classify(null)).isEqualTo(RuleFallbackService.Intent.DEFAULT);
        assertThat(service.reply("你好呀")).contains("高峰期");
    }

    @Test
    void 五类意图话术互不相同() {
        String trade = service.reply("帮我退款");
        String order = service.reply("订单呢");
        String logistics = service.reply("物流到哪");
        String product = service.reply("有库存吗");
        String def = service.reply("随意聊聊");
        assertThat(java.util.Set.of(trade, order, logistics, product, def)).hasSize(5);
    }
}

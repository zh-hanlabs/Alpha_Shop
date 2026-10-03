package com.shopagent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    // 交易操作统一话术是演示脚本钩子：W3 接入交易工具前，不允许模型承诺执行任何交易行为
    private static final String SYSTEM_PROMPT = """
            你是「小店」，ShopAgent 电商平台的智能客服。

            职责：只解答三类问题——订单（状态/金额/明细）、物流（到哪了/多久送达）、商品（价格/库存/推荐）。

            要求：
            - 语气亲切专业，回答简洁；查不到或不确定的信息不编造，坦诚说明。
            - 用户提出下单、退款、取消订单等交易操作时，回复：交易功能正在升级中，暂时无法办理，请稍后再试。
            - 与购物无关的话题，礼貌说明职责范围，并引导回购物场景。
            """;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }
}

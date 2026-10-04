package com.shopagent.infra.resilience;

import org.springframework.stereotype.Component;

/**
 * 降级规则回复（W6D3，设计定稿 §2.2：纯话术——查询工具直答不做，用户已裁）。
 * 意图分类 ≥5 类（交易/订单/物流/商品/其他兜底），关键词规则命中取话术模板。
 * 安全红线：交易类（下单/退款/取消）降级期绝不规则执行——交易的二次确认是 Prompt 行为链路，
 * 没有 LLM 就没有确认链路，只引导稍后再试；因此交易类必须最先判定，宁可错杀不过界。
 * Why 规则回复而不是报错（主计划 §6 面试深挖点）：用户体感 > 系统正确性——
 * 高峰期给一句有人味的话术，好过给用户一屏异常堆栈。
 */
@Component
public class RuleFallbackService {

    /** 交易类红线话术：只引导，绝不含「已下单/已退款」等执行性表述 */
    private static final String TRADE_GUIDE =
            "高峰期暂不能办理下单、退款、取消这类交易操作，您的账户和订单都不受影响，稍后再来找我，一定给您办好～";
    private static final String ORDER_REPLY =
            "当前咨询订单的朋友比较多，订单查询暂时排队中，请稍后再试～";
    private static final String LOGISTICS_REPLY =
            "物流信息查询高峰期排队中，稍后再来问我，包裹跑不了～";
    private static final String PRODUCT_REPLY =
            "商品咨询高峰期，稍后再来，我给您详细讲讲～";
    private static final String DEFAULT_REPLY =
            "高峰期，简单问题我直接答：我是小店，只服务购物相关咨询；您稍后再来，我给您详细答复～";

    /** 类别标记（观测记账 W6D4 用）：TRADE / ORDER / LOGISTICS / PRODUCT / DEFAULT */
    public enum Intent { TRADE, ORDER, LOGISTICS, PRODUCT, DEFAULT }

    public String reply(String message) {
        return switch (classify(message)) {
            case TRADE -> TRADE_GUIDE;
            case ORDER -> ORDER_REPLY;
            case LOGISTICS -> LOGISTICS_REPLY;
            case PRODUCT -> PRODUCT_REPLY;
            case DEFAULT -> DEFAULT_REPLY;
        };
    }

    public Intent classify(String message) {
        String text = message == null ? "" : message;
        // 判定顺序即安全设计：交易类最先（下单/退款/取消等动作词优先于一切名词类意图）
        if (matches(text, "下单", "退款", "退钱", "退货", "取消", "购买", "买下", "帮我买", "收货", "付款", "支付")) {
            return Intent.TRADE;
        }
        if (matches(text, "订单", "单号", "发货")) {
            return Intent.ORDER;
        }
        if (matches(text, "物流", "快递", "配送", "送货", "到货", "签收", "包裹")) {
            return Intent.LOGISTICS;
        }
        if (matches(text, "商品", "价格", "多少钱", "库存", "有货", "推荐", "规格", "参数", "便宜")) {
            return Intent.PRODUCT;
        }
        return Intent.DEFAULT;
    }

    private boolean matches(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}

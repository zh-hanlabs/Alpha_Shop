package com.shopagent.service;

import java.math.BigDecimal;

/**
 * 商品展示字段视图（W5D3 §2.4 方案A 缓存边界）：id/name/category/price/description 进两级缓存。
 * Why 无 stock：库存是交易正确性字段——每单扣减→每单失效，进缓存=失效风暴+超卖脏读风险；
 * stock 永远实时查库（TradeService 直连 ProductMapper 零接触缓存），W3 交易安全成果零风险。
 */
public record ProductDetailVO(Long id, String name, String category, BigDecimal price, String description) {
}

package com.shopagent.service;

import java.math.BigDecimal;

/**
 * 下单业务结果。
 * Why：下单有三种业务终态（成功/商品不存在/库存不足），业务层只报状态不产话术，
 * 给模型看的话术由工具层按状态映射——与 W1 查询服务「返回数据不返回文案」同一口径。
 */
public record PlaceOutcome(Status status, String orderNo, String productName,
                           int quantity, BigDecimal totalAmount) {

    public enum Status { PLACED, PRODUCT_NOT_FOUND, INSUFFICIENT_STOCK }

    public static PlaceOutcome placed(String orderNo, String productName, int quantity, BigDecimal totalAmount) {
        return new PlaceOutcome(Status.PLACED, orderNo, productName, quantity, totalAmount);
    }

    public static PlaceOutcome productNotFound(int quantity) {
        return new PlaceOutcome(Status.PRODUCT_NOT_FOUND, null, null, quantity, null);
    }

    public static PlaceOutcome insufficientStock(String productName, int quantity) {
        return new PlaceOutcome(Status.INSUFFICIENT_STOCK, null, productName, quantity, null);
    }
}

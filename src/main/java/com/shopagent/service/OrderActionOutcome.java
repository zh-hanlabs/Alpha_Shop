package com.shopagent.service;

import java.math.BigDecimal;

/**
 * 订单操作（退款/取消）业务结果，与 PlaceOutcome 同口径：业务层只报状态不产话术。
 * NOT_ALLOWED 携带订单当前状态，引导话术由工具层按状态映射（如待付款退款 → 引导取消）。
 */
public record OrderActionOutcome(Status status, String orderNo, String targetStatus,
                                 String currentStatus, BigDecimal amount) {

    public enum Status { DONE, ORDER_NOT_FOUND, NOT_ALLOWED }

    public static OrderActionOutcome done(String orderNo, String targetStatus, BigDecimal amount) {
        return new OrderActionOutcome(Status.DONE, orderNo, targetStatus, null, amount);
    }

    public static OrderActionOutcome notFound() {
        return new OrderActionOutcome(Status.ORDER_NOT_FOUND, null, null, null, null);
    }

    public static OrderActionOutcome notAllowed(String currentStatus) {
        return new OrderActionOutcome(Status.NOT_ALLOWED, null, null, currentStatus, null);
    }
}

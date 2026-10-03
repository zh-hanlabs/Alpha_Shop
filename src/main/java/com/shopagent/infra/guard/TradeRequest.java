package com.shopagent.infra.guard;

/**
 * 交易闸序请求（W4D3 审计埋点用）：三工具共用的编排入参。
 * userId/action/orderNo 是审计字段；lockKey/idempotentKey 是闸序键。
 */
public record TradeRequest(String userId, String action, String orderNo,
                           String lockKey, String idempotentKey) {}

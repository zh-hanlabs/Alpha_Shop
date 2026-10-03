package com.shopagent.infra.lock;

/**
 * 锁键构造（设计定稿 §2.2）。
 * 与幂等键不同不做 sha256：锁生命周期秒级、不落审计，可读键便于 redis-cli 观测演示。
 * 粒度 = 用户+资源（ADR D4）：同用户同资源串行化防双写，跨资源并行不互相阻塞。
 */
public final class LockKeys {

    private LockKeys() {}

    // 下单时订单号尚不存在，以商品维度代位（§2.2 粒度理由）
    public static String placeOrder(String userId, long productId) {
        return "lock:trade:" + userId + ":placeOrder:" + productId;
    }

    // 退款/取消：订单维度
    public static String order(String userId, String orderNo) {
        return "lock:trade:" + userId + ":order:" + orderNo;
    }
}

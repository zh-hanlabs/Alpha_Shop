package com.shopagent.infra.lock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LockKeysTest {

    @Test
    void placeOrder_lock_serializes_same_user_same_product() {
        String base = LockKeys.placeOrder("u1001", 1L);
        assertThat(base)
                .isEqualTo(LockKeys.placeOrder("u1001", 1L))
                .isEqualTo("lock:trade:u1001:placeOrder:1")
                .isNotEqualTo(LockKeys.placeOrder("u1001", 2L))   // 跨商品并行
                .isNotEqualTo(LockKeys.placeOrder("u1002", 1L));  // 跨用户并行
    }

    @Test
    void order_lock_scoped_by_user_and_order() {
        assertThat(LockKeys.order("u1001", "10001"))
                .isEqualTo("lock:trade:u1001:order:10001")
                .isNotEqualTo(LockKeys.order("u1001", "10002"))
                .isNotEqualTo(LockKeys.order("u1002", "10001"));
    }
}

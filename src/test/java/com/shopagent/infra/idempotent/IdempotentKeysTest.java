package com.shopagent.infra.idempotent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotentKeysTest {

    @Test
    void placeOrder_key_is_sha256_and_stable() {
        String key = IdempotentKeys.placeOrder("u1001", 1L, 1, "conv-1", "digest-1");
        String same = IdempotentKeys.placeOrder("u1001", 1L, 1, "conv-1", "digest-1");
        assertThat(key).isEqualTo(same).matches("[0-9a-f]{64}");
    }

    @Test
    void placeOrder_key_differs_by_each_component() {
        String base = IdempotentKeys.placeOrder("u1001", 1L, 1, "conv-1", "digest-1");
        // 换会话/换指令 = 新意图放行；换用户/商品/数量 = 不同交易
        assertThat(base).isNotEqualTo(IdempotentKeys.placeOrder("u1001", 1L, 1, "conv-2", "digest-1"));
        assertThat(base).isNotEqualTo(IdempotentKeys.placeOrder("u1001", 1L, 1, "conv-1", "digest-2"));
        assertThat(base).isNotEqualTo(IdempotentKeys.placeOrder("u1002", 1L, 1, "conv-1", "digest-1"));
        assertThat(base).isNotEqualTo(IdempotentKeys.placeOrder("u1001", 2L, 1, "conv-1", "digest-1"));
        assertThat(base).isNotEqualTo(IdempotentKeys.placeOrder("u1001", 1L, 2, "conv-1", "digest-1"));
    }

    @Test
    void orderAction_key_is_scoped_by_action_user_order() {
        String refund = IdempotentKeys.orderAction("refund", "u1001", "10001");
        assertThat(refund)
                .isNotEqualTo(IdempotentKeys.orderAction("cancel", "u1001", "10001"))
                .isNotEqualTo(IdempotentKeys.orderAction("refund", "u1002", "10001"))
                .isNotEqualTo(IdempotentKeys.orderAction("refund", "u1001", "10002"))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void instruction_digest_is_stable_and_message_sensitive() {
        assertThat(IdempotentKeys.instructionDigest("确认下单"))
                .isEqualTo(IdempotentKeys.instructionDigest("确认下单"))
                .isNotEqualTo(IdempotentKeys.instructionDigest("再买一个"));
    }
}

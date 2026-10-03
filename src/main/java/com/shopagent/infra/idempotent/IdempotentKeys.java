package com.shopagent.infra.idempotent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等键构造（设计定稿见 shopagent-w3w4-tasks.md §2.1）。
 * Why：placeOrder 执行时订单号还不存在，用「用户+动作+参数+会话+指令摘要」区分
 * 重放（同句重发）与正常复购（换句/换会话）；退款/取消已有订单号，直接用户+动作+订单号，
 * 跨会话也拦同一订单同一动作的重放。
 * 键整体 sha256：长度归一 + 不向 Redis 暴露业务字段 + 免疫分隔符注入。
 */
public final class IdempotentKeys {

    private IdempotentKeys() {}

    public static String placeOrder(String userId, long productId, int quantity,
                                    String conversationId, String instructionDigest) {
        return sha256(String.join("|",
                "placeOrder", userId, String.valueOf(productId), String.valueOf(quantity),
                conversationId, instructionDigest));
    }

    public static String orderAction(String action, String userId, String orderNo) {
        return sha256(String.join("|", action, userId, orderNo));
    }

    // controller 注入 toolContext 用：指令摘要 = 触发本次工具调用的用户消息原文的 sha256
    public static String instructionDigest(String message) {
        return sha256(message == null ? "" : message);
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM must provide SHA-256", e);
        }
    }
}

package com.shopagent.tools.support;

import org.springframework.ai.chat.model.ToolContext;

/**
 * ToolContext 键常量与取值口径。
 * Why：userId 由接入层注入（W1 无鉴权直传，W8+ 换登录态），工具内不硬编码；
 * W3 幂等键 = userId + action + 参数摘要，依赖这里的取值口径统一。
 */
public final class ToolContextKeys {

    public static final String USER_ID = "userId";
    public static final String TOOL_EVENT_LISTENER = "toolEventListener";

    private ToolContextKeys() {}

    // 缺失/空白统一返回 null，调用方只需判空（数据归属校验的三处调用共用）
    public static String userId(ToolContext toolContext) {
        Object userId = toolContext.getContext().get(USER_ID);
        return (userId == null || userId.toString().isBlank()) ? null : userId.toString();
    }
}

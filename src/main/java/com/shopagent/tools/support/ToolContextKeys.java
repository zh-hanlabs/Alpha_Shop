package com.shopagent.tools.support;

/**
 * ToolContext 键常量。
 * Why：userId 由接入层注入（W1 无鉴权直传，W8+ 换登录态），工具内不硬编码；
 * W3 幂等键 = userId + action + 参数摘要，依赖这里的取值口径统一。
 */
public final class ToolContextKeys {

    public static final String USER_ID = "userId";
    public static final String TOOL_EVENT_LISTENER = "toolEventListener";

    private ToolContextKeys() {}
}

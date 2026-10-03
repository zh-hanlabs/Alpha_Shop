package com.shopagent.tools.support;

import org.springframework.ai.chat.model.ToolContext;

public final class ToolEvents {

    private ToolEvents() {}

    // 监听器缺席时静默跳过：非流式调用方（/api/chat、单测）没有事件通道
    public static void publish(ToolContext toolContext, String message) {
        Object listener = toolContext.getContext().get(ToolContextKeys.TOOL_EVENT_LISTENER);
        if (listener instanceof ToolEventListener eventListener) {
            eventListener.onToolEvent(message);
        }
    }
}

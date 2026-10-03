package com.shopagent.tools.support;

/**
 * 工具执行事件回调：接入层把 SSE 事件推送注入 ToolContext，工具执行时上报状态。
 * Why：内部工具执行模式下工具调用块不进入流，前端看不到「正在查询订单…」；
 * W3 交易审计日志可复用同一埋点（谁、何时、调了什么工具）。
 */
@FunctionalInterface
public interface ToolEventListener {

    void onToolEvent(String message);
}

package com.shopagent.tools.query;

import com.shopagent.service.OrderService;
import com.shopagent.tools.support.ToolContextKeys;
import com.shopagent.tools.support.ToolEvents;
import com.shopagent.tools.support.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class RecentOrdersTool {

    private static final Logger log = LoggerFactory.getLogger(RecentOrdersTool.class);

    private final OrderService orderService;

    public RecentOrdersTool(OrderService orderService) {
        this.orderService = orderService;
    }

    // userId 不由模型传入：从 ToolContext 取接入层注入的身份，防越权（工具内不硬编码）
    @Tool(description = "查询当前用户最近的订单列表（最多5条，按下单时间倒序）。当用户说「我的订单」「我最近的订单」「我都买过什么」，" +
            "或提到某个订单但没提供订单号时调用。无需任何参数。")
    public ToolResult recentOrders(ToolContext toolContext) {
        String userId = ToolContextKeys.userId(toolContext);
        if (userId == null) {
            return ToolResult.error("用户身份缺失，无法查询订单");
        }
        ToolEvents.publish(toolContext, "正在查询您的最近订单");
        try {
            return ToolResult.ok(orderService.recentOrders(userId));
        } catch (Exception e) {
            log.error("recentOrders failed, userId={}", userId, e);
            return ToolResult.error("订单查询失败，请稍后再试");
        }
    }
}

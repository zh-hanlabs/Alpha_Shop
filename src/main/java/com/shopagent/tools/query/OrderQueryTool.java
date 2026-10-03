package com.shopagent.tools.query;

import com.shopagent.service.OrderDetail;
import com.shopagent.service.OrderService;
import com.shopagent.tools.support.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class OrderQueryTool {

    private static final Logger log = LoggerFactory.getLogger(OrderQueryTool.class);

    // 白名单校验：不信任模型传参（踩坑清单 #4）
    private static final Pattern ORDER_NO_PATTERN = Pattern.compile("\\d{1,20}");

    private final OrderService orderService;

    public OrderQueryTool(OrderService orderService) {
        this.orderService = orderService;
    }

    @Tool(description = "查询订单详情。当用户询问某个订单的金额、商品明细、状态、下单时间、买了什么时调用。" +
            "参数 orderId 为订单号，纯数字字符串。用户没给订单号但提到「我的订单」时，先用 recentOrders 查最近订单。")
    public ToolResult queryOrder(@ToolParam(description = "订单号，纯数字，例如 10001") String orderId) {
        if (orderId == null || !ORDER_NO_PATTERN.matcher(orderId).matches()) {
            return ToolResult.badParam("订单号格式不正确，应为纯数字");
        }
        try {
            OrderDetail detail = orderService.queryOrderDetail(orderId);
            if (detail == null) {
                return ToolResult.notFound("未找到订单 " + orderId + "，请确认订单号是否正确");
            }
            return ToolResult.ok(detail);
        } catch (Exception e) {
            log.error("queryOrder failed, orderId={}", orderId, e);
            return ToolResult.error("订单查询失败，请稍后再试");
        }
    }
}

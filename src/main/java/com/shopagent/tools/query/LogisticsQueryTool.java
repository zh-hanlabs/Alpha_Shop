package com.shopagent.tools.query;

import com.shopagent.service.LogisticsService;
import com.shopagent.tools.support.ToolContextKeys;
import com.shopagent.tools.support.ToolEvents;
import com.shopagent.tools.support.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class LogisticsQueryTool {

    private static final Logger log = LoggerFactory.getLogger(LogisticsQueryTool.class);

    // 白名单校验：不信任模型传参
    private static final Pattern ORDER_NO_PATTERN = Pattern.compile("\\d{1,20}");

    private final LogisticsService logisticsService;

    public LogisticsQueryTool(LogisticsService logisticsService) {
        this.logisticsService = logisticsService;
    }

    @Tool(description = "查询订单的物流轨迹。当用户问「到哪了」「什么时候送达」「快递单号」「物流/快递状态」时调用。" +
            "参数 orderId 为订单号，纯数字字符串。仅限当前用户本人的订单。用户问物流前如果不知道订单号，先用 recentOrders 查最近订单，或礼貌询问订单号。")
    public ToolResult queryLogistics(@ToolParam(description = "订单号，纯数字，例如 10001") String orderId,
                                     ToolContext toolContext) {
        if (orderId == null || !ORDER_NO_PATTERN.matcher(orderId).matches()) {
            return ToolResult.badParam("订单号格式不正确，应为纯数字");
        }
        String userId = ToolContextKeys.userId(toolContext);
        if (userId == null) {
            return ToolResult.error("用户身份缺失，无法查询物流");
        }
        ToolEvents.publish(toolContext, "正在查询订单 " + orderId + " 的物流");
        try {
            LogisticsService.LogisticsDetail detail = logisticsService.queryLogistics(orderId, userId);
            if (detail == null) {
                return ToolResult.notFound("未找到您的订单 " + orderId + " 的物流记录，请确认订单号是否正确");
            }
            return ToolResult.ok(detail);
        } catch (Exception e) {
            log.error("queryLogistics failed, orderId={}", orderId, e);
            return ToolResult.error("物流查询失败，请稍后再试");
        }
    }
}

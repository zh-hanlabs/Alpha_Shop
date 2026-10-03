package com.shopagent.tools.trade;

import com.shopagent.infra.guard.TradeGuard;
import com.shopagent.infra.guard.TradeRequest;
import com.shopagent.infra.idempotent.IdempotentKeys;
import com.shopagent.infra.lock.LockKeys;
import com.shopagent.service.OrderActionOutcome;
import com.shopagent.service.TradeService;
import com.shopagent.tools.support.ToolContextKeys;
import com.shopagent.tools.support.ToolEvents;
import com.shopagent.tools.support.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.regex.Pattern;

@Component
public class CancelOrderTool {

    private static final Logger log = LoggerFactory.getLogger(CancelOrderTool.class);

    private static final Pattern ORDER_NO_PATTERN = Pattern.compile("\\d{1,20}");

    private final TradeService tradeService;
    private final TradeGuard tradeGuard;

    public CancelOrderTool(TradeService tradeService, TradeGuard tradeGuard) {
        this.tradeService = tradeService;
        this.tradeGuard = tradeGuard;
    }

    // 幂等键 = 用户+动作+订单号（§2.1，跨会话拦重复取消）；锁键 = 用户+订单级（ADR D4）
    @Tool(description = "为当前用户取消订单。仅当你已向用户复述订单号、商品明细、金额，且用户明确同意后才调用。" +
            "orderNo 必须来自查询工具返回的真实订单号，禁止猜测或编造。" +
            "仅待付款订单可取消；已发货或已送达订单请引导用户走退款，不要调用本工具。" +
            "若返回「已取消」或相同结果，说明取消已受理，直接告知用户即可，不要重复操作。")
    public ToolResult cancelOrder(@ToolParam(description = "订单号，纯数字，例如 10003") String orderNo,
                                  ToolContext toolContext) {
        if (orderNo == null || !ORDER_NO_PATTERN.matcher(orderNo).matches()) {
            return ToolResult.badParam("订单号格式不正确，应为纯数字");
        }
        String userId = ToolContextKeys.userId(toolContext);
        if (userId == null) {
            return ToolResult.error("用户身份缺失，无法取消订单");
        }
        ToolEvents.publish(toolContext, "正在取消订单 " + orderNo);
        String idempotentKey = IdempotentKeys.orderAction("cancel", userId, orderNo);
        return tradeGuard.execute(
                new TradeRequest(userId, "cancel", orderNo,
                        LockKeys.order(userId, orderNo), idempotentKey),
                () -> {
            try {
                OrderActionOutcome outcome = tradeService.cancel(userId, orderNo);
                return switch (outcome.status()) {
                    case DONE -> ToolResult.ok(new CancelledData(outcome.orderNo(), outcome.amount()));
                    case ORDER_NOT_FOUND -> ToolResult.notFound("未找到您的订单 " + orderNo + "，请确认订单号是否正确");
                    case NOT_ALLOWED -> ToolResult.reject(cancelNotAllowedMsg(outcome.currentStatus()));
                };
            } catch (Exception e) {
                log.error("cancelOrder failed, orderNo={}", orderNo, e);
                return ToolResult.error("取消订单失败，请稍后再试");
            }
        });
    }

    private String cancelNotAllowedMsg(String currentStatus) {
        return switch (currentStatus) {
            case "CANCELLED" -> "订单已取消，请勿重复操作";
            case "REFUNDING" -> "订单已在退款流程中，无法取消";
            case "REFUNDED" -> "订单已退款，无法取消";
            default -> "仅待付款订单可取消；已发货或已送达的订单可以申请退款";
        };
    }

    record CancelledData(String orderNo, BigDecimal totalAmount, String status) {
        CancelledData(String orderNo, BigDecimal totalAmount) {
            this(orderNo, totalAmount, "CANCELLED");
        }
    }
}

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
public class RefundOrderTool {

    private static final Logger log = LoggerFactory.getLogger(RefundOrderTool.class);

    // 白名单校验：不信任模型传参（踩坑清单 #4），与订单查询同一口径
    private static final Pattern ORDER_NO_PATTERN = Pattern.compile("\\d{1,20}");

    private final TradeService tradeService;
    private final TradeGuard tradeGuard;

    public RefundOrderTool(TradeService tradeService, TradeGuard tradeGuard) {
        this.tradeService = tradeService;
        this.tradeGuard = tradeGuard;
    }

    // 幂等键 = 用户+动作+订单号（§2.1，跨会话拦同一订单的重复退款）；锁键 = 用户+订单级（ADR D4）
    @Tool(description = "为当前用户申请订单退款。仅当你已向用户复述订单号、商品明细、退款金额，且用户明确同意后才调用。" +
            "orderNo 必须来自查询工具返回的真实订单号，禁止猜测或编造。" +
            "仅已发货或已送达的订单可退款；待付款订单应引导用户取消而非退款；已在退款流程中的订单不要重复申请。" +
            "若返回「已完成退款」或相同结果，说明退款已受理，直接告知用户即可，不要重复申请。")
    public ToolResult refundOrder(@ToolParam(description = "订单号，纯数字，例如 10001") String orderNo,
                                  ToolContext toolContext) {
        if (orderNo == null || !ORDER_NO_PATTERN.matcher(orderNo).matches()) {
            return ToolResult.badParam("订单号格式不正确，应为纯数字");
        }
        String userId = ToolContextKeys.userId(toolContext);
        if (userId == null) {
            return ToolResult.error("用户身份缺失，无法申请退款");
        }
        ToolEvents.publish(toolContext, "正在为订单 " + orderNo + " 申请退款");
        String idempotentKey = IdempotentKeys.orderAction("refund", userId, orderNo);
        return tradeGuard.execute(
                new TradeRequest(userId, "refund", orderNo,
                        LockKeys.order(userId, orderNo), idempotentKey),
                () -> {
            try {
                OrderActionOutcome outcome = tradeService.refund(userId, orderNo);
                return switch (outcome.status()) {
                    case DONE -> ToolResult.ok(new RefundedData(outcome.orderNo(), outcome.amount()));
                    case ORDER_NOT_FOUND -> ToolResult.notFound("未找到您的订单 " + orderNo + "，请确认订单号是否正确");
                    case NOT_ALLOWED -> ToolResult.reject(refundNotAllowedMsg(outcome.currentStatus()));
                };
            } catch (Exception e) {
                log.error("refundOrder failed, orderNo={}", orderNo, e);
                return ToolResult.error("退款申请失败，请稍后再试");
            }
        });
    }

    // 状态机拒绝话术（T5.2）：业务层只报状态，引导话术在工具层映射
    private String refundNotAllowedMsg(String currentStatus) {
        return switch (currentStatus) {
            case "REFUNDING" -> "订单已在退款流程中，请耐心等待到账，请勿重复申请";
            case "PENDING_PAYMENT" -> "订单尚未支付，无需退款；如不想要了，我可以帮您取消订单";
            case "CANCELLED" -> "订单已取消，未收取款项，无需退款";
            case "REFUNDED" -> "该订单已完成退款，请勿重复申请";
            default -> "当前订单状态不支持退款，如有疑问请联系人工客服";
        };
    }

    record RefundedData(String orderNo, BigDecimal refundAmount, String status) {
        RefundedData(String orderNo, BigDecimal refundAmount) {
            this(orderNo, refundAmount, "REFUNDED");
        }
    }
}

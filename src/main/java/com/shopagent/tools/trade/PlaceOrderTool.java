package com.shopagent.tools.trade;

import com.shopagent.infra.idempotent.IdempotentExecutor;
import com.shopagent.infra.idempotent.IdempotentKeys;
import com.shopagent.service.PlaceOutcome;
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
public class PlaceOrderTool {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderTool.class);

    // 白名单校验：不信任模型传参（踩坑清单 #4），productId 只认 searchProduct 给过的形态
    private static final Pattern PRODUCT_ID_PATTERN = Pattern.compile("\\d{1,10}");
    private static final int MIN_QUANTITY = 1;
    private static final int MAX_QUANTITY = 99;
    private static final int DEFAULT_QUANTITY = 1;

    private final TradeService tradeService;
    private final IdempotentExecutor idempotentExecutor;

    public PlaceOrderTool(TradeService tradeService, IdempotentExecutor idempotentExecutor) {
        this.tradeService = tradeService;
        this.idempotentExecutor = idempotentExecutor;
    }

    // 二次确认靠 Prompt 层约束（体验层），防重复下单靠幂等+锁（安全边界）——与 W1D8 两层安全同构；
    // 幂等键含会话+指令摘要：同句重发=重放拦截，换句/换会话=新意图放行（设计定稿 §2.1）
    @Tool(description = "为当前用户下单购买商品。仅当你已向用户复述商品名、数量、总价，且用户明确同意后才调用。" +
            "productId 必须来自 searchProduct 返回的真实 id，禁止猜测或编造；用户没说数量时 quantity 传 1。" +
            "成功后 data 含订单号与总价，请告知用户订单号和待付款状态；库存不足或商品不存在时如实转告用户，不得擅自换商品下单。" +
            "若返回「已办理过」或相同订单号，说明此单已受理，直接告知用户订单号即可，不要重复下单。")
    public ToolResult placeOrder(@ToolParam(description = "商品 id，必须来自 searchProduct 返回结果") String productId,
                                 @ToolParam(description = "购买数量，1-99 的整数，用户未说明则传 1", required = false) Integer quantity,
                                 ToolContext toolContext) {
        if (productId == null || !PRODUCT_ID_PATTERN.matcher(productId).matches()) {
            return ToolResult.badParam("商品 id 格式不正确，应为纯数字");
        }
        int qty = (quantity == null) ? DEFAULT_QUANTITY : quantity;
        if (qty < MIN_QUANTITY || qty > MAX_QUANTITY) {
            return ToolResult.badParam("购买数量必须在 1-99 之间");
        }
        String userId = ToolContextKeys.userId(toolContext);
        if (userId == null) {
            return ToolResult.error("用户身份缺失，无法下单");
        }
        String conversationId = ToolContextKeys.conversationId(toolContext);
        String instructionDigest = ToolContextKeys.instructionDigest(toolContext);
        if (conversationId == null || instructionDigest == null) {
            // 幂等键组成部分缺失 → 无幂等保护的交易不做（fail-closed）
            return ToolResult.error("会话上下文缺失，无法安全下单，请重新发起对话");
        }
        long productIdValue = Long.parseLong(productId);
        ToolEvents.publish(toolContext, "正在下单，商品 id " + productId + " × " + qty);
        String idempotentKey = IdempotentKeys.placeOrder(userId, productIdValue, qty, conversationId, instructionDigest);
        return idempotentExecutor.execute(idempotentKey, () -> {
            try {
                PlaceOutcome outcome = tradeService.place(userId, productIdValue, qty);
                return switch (outcome.status()) {
                    case PLACED -> ToolResult.ok(new PlacedData(outcome.orderNo(), outcome.productName(),
                            outcome.quantity(), outcome.totalAmount()));
                    case PRODUCT_NOT_FOUND -> ToolResult.notFound("商品不存在或已下架，请重新搜索确认商品");
                    case INSUFFICIENT_STOCK -> ToolResult.reject("库存不足，「" + outcome.productName()
                            + "」剩余库存不够，请减少数量或换个商品");
                };
            } catch (Exception e) {
                log.error("placeOrder failed, productId={}, quantity={}", productId, qty, e);
                return ToolResult.error("下单失败，请稍后再试");
            }
        });
    }

    record PlacedData(String orderNo, String productName, int quantity, BigDecimal totalAmount, String status) {
        PlacedData(String orderNo, String productName, int quantity, BigDecimal totalAmount) {
            this(orderNo, productName, quantity, totalAmount, "PENDING_PAYMENT");
        }
    }
}

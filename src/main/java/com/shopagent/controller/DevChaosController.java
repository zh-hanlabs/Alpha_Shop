package com.shopagent.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shopagent.entity.Order;
import com.shopagent.entity.Product;
import com.shopagent.entity.TradeAuditLog;
import com.shopagent.infra.idempotent.IdempotentKeys;
import com.shopagent.mapper.OrderMapper;
import com.shopagent.mapper.ProductMapper;
import com.shopagent.mapper.TradeAuditLogMapper;
import com.shopagent.tools.query.OrderQueryTool;
import com.shopagent.tools.support.ToolContextKeys;
import com.shopagent.tools.support.ToolResult;
import com.shopagent.tools.trade.CancelOrderTool;
import com.shopagent.tools.trade.PlaceOrderTool;
import com.shopagent.tools.trade.RefundOrderTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 混沌测试直连端点（W4D1-2，任务清单 §五）：绕过 LLM 直打工具层完整闸序
 * （参数校验 → TradeGuard 抢锁/幂等 → 业务），并发场景确定性。
 * Why dev-only：端点可任意构造 userId/会话/指令摘要，仅本地混沌与复现用；
 * @Profile("dev") 保证演示/生产运行（默认无 profile）不注册此端点。
 */
@RestController
@RequestMapping("/api/dev/chaos")
@Profile("dev")
public class DevChaosController {

    private final PlaceOrderTool placeOrderTool;
    private final RefundOrderTool refundOrderTool;
    private final CancelOrderTool cancelOrderTool;
    private final OrderQueryTool orderQueryTool;
    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final TradeAuditLogMapper auditLogMapper;

    public DevChaosController(PlaceOrderTool placeOrderTool, RefundOrderTool refundOrderTool,
                              CancelOrderTool cancelOrderTool, OrderQueryTool orderQueryTool,
                              ProductMapper productMapper, OrderMapper orderMapper,
                              TradeAuditLogMapper auditLogMapper) {
        this.placeOrderTool = placeOrderTool;
        this.refundOrderTool = refundOrderTool;
        this.cancelOrderTool = cancelOrderTool;
        this.orderQueryTool = orderQueryTool;
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.auditLogMapper = auditLogMapper;
    }

    public record ChaosPlaceRequest(String userId, long productId, int quantity,
                                    String conversationId, String message) {}

    public record ChaosOrderActionRequest(String userId, String orderNo,
                                          String conversationId, String message) {}

    public record ChaosQueryRequest(String userId, String orderNo) {}

    @PostMapping("/place")
    public ToolResult place(@RequestBody ChaosPlaceRequest req) {
        return placeOrderTool.placeOrder(String.valueOf(req.productId()),
                req.quantity() <= 0 ? 1 : req.quantity(),
                buildContext(req.userId(), req.conversationId(), req.message()));
    }

    @PostMapping("/refund")
    public ToolResult refund(@RequestBody ChaosOrderActionRequest req) {
        return refundOrderTool.refundOrder(req.orderNo(),
                buildContext(req.userId(), req.conversationId(), req.message()));
    }

    @PostMapping("/cancel")
    public ToolResult cancel(@RequestBody ChaosOrderActionRequest req) {
        return cancelOrderTool.cancelOrder(req.orderNo(),
                buildContext(req.userId(), req.conversationId(), req.message()));
    }

    // C4 用：查询链路纯 H2 不经 Redis，停机场景下证明查询不受影响
    @PostMapping("/query-order")
    public ToolResult queryOrder(@RequestBody ChaosQueryRequest req) {
        return orderQueryTool.queryOrder(req.orderNo(), buildContext(req.userId(), "", ""));
    }

    // 混沌结果硬核验证：库存与订单数直接读库（不经工具/模型，作为独立口径）
    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam String userId, @RequestParam long productId) {
        Product product = productMapper.selectById(productId);
        Long orderCount = orderMapper.selectCount(
                Wrappers.<Order>lambdaQuery().eq(Order::getUserId, userId));
        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("productId", productId);
        result.put("productName", product == null ? null : product.getName());
        result.put("stock", product == null ? null : product.getStock());
        result.put("orderCount", orderCount);
        return result;
    }

    // 审计查询：按用户倒序取最近 N 条（重放场景两条记录同 idempotent_key 的一手验证）
    @GetMapping("/audit")
    public List<TradeAuditLog> audit(@RequestParam String userId,
                                     @RequestParam(defaultValue = "10") int limit) {
        int bounded = Math.min(Math.max(limit, 1), 100);
        return auditLogMapper.selectList(Wrappers.<TradeAuditLog>lambdaQuery()
                .eq(TradeAuditLog::getUserId, userId)
                .orderByDesc(TradeAuditLog::getId)
                .last("LIMIT " + bounded));
    }

    // 镜像 ChatController.buildToolContext：同样的键构造，保证混沌路径与 LLM 路径幂等键口径一致
    private ToolContext buildContext(String userId, String conversationId, String message) {
        Map<String, Object> context = new HashMap<>();
        context.put(ToolContextKeys.USER_ID, userId);
        context.put(ToolContextKeys.CONVERSATION_ID, conversationId == null ? "" : conversationId);
        context.put(ToolContextKeys.INSTRUCTION_DIGEST, IdempotentKeys.instructionDigest(message));
        return new ToolContext(context);
    }
}

package com.shopagent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shopagent.entity.Order;
import com.shopagent.entity.OrderItem;
import com.shopagent.entity.Product;
import com.shopagent.mapper.OrderItemMapper;
import com.shopagent.mapper.OrderMapper;
import com.shopagent.mapper.ProductMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TradeService {

    // 状态口径与 mock 数据一致（data.sql 英文枚举），中文话术由模型翻译
    private static final String STATUS_PENDING_PAYMENT = "PENDING_PAYMENT";
    private static final String STATUS_SHIPPED = "SHIPPED";
    private static final String STATUS_DELIVERED = "DELIVERED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_REFUNDED = "REFUNDED";
    // 状态机（T5.2）：退款仅已发货/已签收可办；取消仅待付款可办
    private static final Set<String> REFUNDABLE_FROM = Set.of(STATUS_SHIPPED, STATUS_DELIVERED);
    private static final Set<String> CANCELLABLE_FROM = Set.of(STATUS_PENDING_PAYMENT);

    // 订单号 = yyyyMMddHHmmss(14) + 6 位后缀 = 20 位纯数字，配合工具层 \d{1,20} 白名单
    private static final DateTimeFormatter ORDER_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int ORDER_NO_SUFFIX_BOUND = 1_000_000;
    // 后缀用「随机种子 + JVM 内自增序列」而非纯随机：同秒并发线程间保证不重号；
    // 跨重启同秒撞号由 uk_order_no 唯一索引兜底（撞号 → 事务回滚 → 工具层返回可重试的失败）
    private static final AtomicInteger ORDER_NO_SEQ =
            new AtomicInteger(ThreadLocalRandom.current().nextInt(ORDER_NO_SUFFIX_BOUND));

    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    public TradeService(ProductMapper productMapper, OrderMapper orderMapper, OrderItemMapper orderItemMapper) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
    }

    @Transactional
    public PlaceOutcome place(String userId, long productId, int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        Product product = productMapper.selectById(productId);
        if (product == null) {
            return PlaceOutcome.productNotFound(quantity);
        }
        // 原子条件扣减（quantity 已验证为正整数，拼接无注入风险）：影响行数=0 即库存不足，
        // 从 SQL 层杜绝「先查后改」的并发超卖窗口——W4 混沌测试 C2 的验证点
        int updated = productMapper.update(null, Wrappers.<Product>lambdaUpdate()
                .setSql("stock = stock - " + quantity)
                .eq(Product::getId, productId)
                .ge(Product::getStock, quantity));
        if (updated == 0) {
            return PlaceOutcome.insufficientStock(product.getName(), quantity);
        }

        String orderNo = generateOrderNo();
        BigDecimal total = product.getPrice().multiply(BigDecimal.valueOf(quantity));

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setStatus(STATUS_PENDING_PAYMENT);
        order.setTotalAmount(total);
        orderMapper.insert(order);

        // 快照商品名/单价：退款金额不随商品后续改价漂移（schema 设计时预留）
        OrderItem item = new OrderItem();
        item.setOrderNo(orderNo);
        item.setProductId(productId);
        item.setProductName(product.getName());
        item.setQuantity(quantity);
        item.setUnitPrice(product.getPrice());
        orderItemMapper.insert(item);

        return PlaceOutcome.placed(orderNo, product.getName(), quantity, total);
    }

    // 退款：已发货/已签收 → 已退款（T5.2 状态机）
    @Transactional
    public OrderActionOutcome refund(String userId, String orderNo) {
        return transition(userId, orderNo, STATUS_REFUNDED, REFUNDABLE_FROM);
    }

    // 取消：仅待付款可取消
    @Transactional
    public OrderActionOutcome cancel(String userId, String orderNo) {
        return transition(userId, orderNo, STATUS_CANCELLED, CANCELLABLE_FROM);
    }

    private OrderActionOutcome transition(String userId, String orderNo, String targetStatus,
                                          Set<String> allowedFrom) {
        // 归属条件并入同一条查询：他人订单与不存在不可区分（W1D8 口径，不泄露存在性）
        Order order = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getOrderNo, orderNo)
                .eq(Order::getUserId, userId));
        if (order == null) {
            return OrderActionOutcome.notFound();
        }
        if (!allowedFrom.contains(order.getStatus())) {
            return OrderActionOutcome.notAllowed(order.getStatus());
        }
        // 条件迁移：status IN (allowedFrom) 防并发双改——工具层用户+订单锁之外的第二道防线
        int updated = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .set(Order::getStatus, targetStatus)
                .set(Order::getUpdatedAt, LocalDateTime.now())
                .eq(Order::getOrderNo, orderNo)
                .eq(Order::getUserId, userId)
                .in(Order::getStatus, allowedFrom));
        if (updated == 0) {
            // 锁外并发已改走状态（如另一请求先取消）：以库内最新状态回话术
            Order fresh = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                    .eq(Order::getOrderNo, orderNo)
                    .eq(Order::getUserId, userId));
            return OrderActionOutcome.notAllowed(fresh == null ? order.getStatus() : fresh.getStatus());
        }
        restoreStock(orderNo);
        return OrderActionOutcome.done(orderNo, targetStatus, order.getTotalAmount());
    }

    // 还库存：按下单时的快照逐商品原子 +N（不做读改写），退款/取消共用
    private void restoreStock(String orderNo) {
        List<OrderItem> items = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderNo, orderNo));
        for (OrderItem item : items) {
            productMapper.update(null, Wrappers.<Product>lambdaUpdate()
                    .setSql("stock = stock + " + item.getQuantity())
                    .eq(Product::getId, item.getProductId()));
        }
    }

    private String generateOrderNo() {
        String suffix = String.format("%06d",
                Math.floorMod(ORDER_NO_SEQ.getAndIncrement(), ORDER_NO_SUFFIX_BOUND));
        return LocalDateTime.now().format(ORDER_NO_TIME) + suffix;
    }
}

package com.shopagent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shopagent.entity.Order;
import com.shopagent.entity.OrderItem;
import com.shopagent.entity.Product;
import com.shopagent.mapper.OrderItemMapper;
import com.shopagent.mapper.OrderMapper;
import com.shopagent.mapper.ProductMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易测试是首个有写副作用的测试类：Spring 上下文按配置缓存共享 H2，
 * 这里用独立库名隔离，避免污染 MockDataQueryTest 等只读断言（6 单 / 8 商品 / 库存 45）。
 * 每个测试自建商品、自用独立 userId，测试间零耦合。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:shopdb-trade-test;DB_CLOSE_DELAY=-1;MODE=MySQL")
class TradeServiceTest {

    @Autowired
    private TradeService tradeService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;

    @Test
    void place_creates_pending_payment_order_with_price_snapshot() {
        long productId = insertProduct("测试充电宝", "99.00", 10);

        PlaceOutcome outcome = tradeService.place("u9001", productId, 2);

        assertThat(outcome.status()).isEqualTo(PlaceOutcome.Status.PLACED);
        assertThat(outcome.orderNo()).matches("\\d{20}");
        assertThat(outcome.totalAmount()).isEqualByComparingTo("198.00");

        Order order = orderMapper.selectOne(
                Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, outcome.orderNo()));
        assertThat(order.getUserId()).isEqualTo("u9001");
        assertThat(order.getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("198.00");

        OrderItem item = orderItemMapper.selectOne(
                Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderNo, outcome.orderNo()));
        assertThat(item.getProductName()).isEqualTo("测试充电宝");
        assertThat(item.getQuantity()).isEqualTo(2);
        assertThat(item.getUnitPrice()).isEqualByComparingTo("99.00");

        assertThat(productMapper.selectById(productId).getStock()).isEqualTo(8);

        // 新订单立即可被查询链路看到（归属本人）——下单与查询共用同一套数据口径
        assertThat(orderService.queryOrderDetail(outcome.orderNo(), "u9001")).isNotNull();
    }

    @Test
    void place_rejects_when_stock_insufficient() {
        long productId = insertProduct("测试露营灯", "59.00", 3);

        PlaceOutcome outcome = tradeService.place("u9002", productId, 5);

        assertThat(outcome.status()).isEqualTo(PlaceOutcome.Status.INSUFFICIENT_STOCK);
        // 库存不动、订单不落
        assertThat(productMapper.selectById(productId).getStock()).isEqualTo(3);
        assertThat(orderMapper.selectCount(
                Wrappers.<Order>lambdaQuery().eq(Order::getUserId, "u9002"))).isZero();
    }

    @Test
    void place_returns_not_found_for_unknown_product() {
        PlaceOutcome outcome = tradeService.place("u9003", 99999L, 1);

        assertThat(outcome.status()).isEqualTo(PlaceOutcome.Status.PRODUCT_NOT_FOUND);
    }

    @Test
    void place_rejects_non_positive_quantity() {
        assertThatThrownBy(() -> tradeService.place("u9004", 1L, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrent_place_never_oversells() throws Exception {
        long productId = insertProduct("并发测试手表", "199.00", 10);
        int threads = 30;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<String> orderNos = new HashSet<>();
        int placed = 0;
        int insufficient = 0;
        try {
            java.util.List<Future<PlaceOutcome>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final String userId = "u9c" + i;
                futures.add(pool.submit(() -> tradeService.place(userId, productId, 1)));
            }
            for (Future<PlaceOutcome> future : futures) {
                PlaceOutcome outcome = future.get(30, TimeUnit.SECONDS);
                if (outcome.status() == PlaceOutcome.Status.PLACED) {
                    placed++;
                    orderNos.add(outcome.orderNo());
                } else if (outcome.status() == PlaceOutcome.Status.INSUFFICIENT_STOCK) {
                    insufficient++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // 10 件库存恰好成交 10 单，其余 20 次库存不足——零超卖、零少卖
        assertThat(placed).isEqualTo(10);
        assertThat(insufficient).isEqualTo(20);
        assertThat(productMapper.selectById(productId).getStock()).isZero();
        assertThat(orderMapper.selectCount(
                Wrappers.<Order>lambdaQuery().likeRight(Order::getUserId, "u9c"))).isEqualTo(10L);
        // 订单号无重号（序列后缀 + 唯一索引兜底）
        assertThat(orderNos).hasSize(10);
    }

    // ===== 退款状态机（T5.2：已发货/已签收 → 已退款）=====

    @Test
    void refund_shipped_or_delivered_transitions_to_refunded_and_restores_stock() {
        long productId = insertProduct("测试耳机", "199.00", 5);

        PlaceOutcome shippedOrder = tradeService.place("u9101", productId, 2);
        forceStatus(shippedOrder.orderNo(), "SHIPPED");
        PlaceOutcome deliveredOrder = tradeService.place("u9102", productId, 1);
        forceStatus(deliveredOrder.orderNo(), "DELIVERED");

        OrderActionOutcome refundShipped = tradeService.refund("u9101", shippedOrder.orderNo());
        OrderActionOutcome refundDelivered = tradeService.refund("u9102", deliveredOrder.orderNo());

        assertThat(refundShipped.status()).isEqualTo(OrderActionOutcome.Status.DONE);
        assertThat(refundShipped.targetStatus()).isEqualTo("REFUNDED");
        assertThat(refundShipped.amount()).isEqualByComparingTo("398.00");
        assertThat(refundDelivered.status()).isEqualTo(OrderActionOutcome.Status.DONE);
        assertThat(orderStatus(shippedOrder.orderNo())).isEqualTo("REFUNDED");
        assertThat(orderStatus(deliveredOrder.orderNo())).isEqualTo("REFUNDED");
        // 下单共扣 3（2+1），退款按快照全还：5
        assertThat(productMapper.selectById(productId).getStock()).isEqualTo(5);
    }

    @Test
    void refund_rejects_non_refundable_states_with_current_status() {
        long productId = insertProduct("测试台灯", "89.00", 10);

        PlaceOutcome pendingOrder = tradeService.place("u9102", productId, 1);
        PlaceOutcome refundingOrder = tradeService.place("u9102", productId, 1);
        forceStatus(refundingOrder.orderNo(), "REFUNDING");

        OrderActionOutcome refundPending = tradeService.refund("u9102", pendingOrder.orderNo());
        OrderActionOutcome refundRefunding = tradeService.refund("u9102", refundingOrder.orderNo());

        // 待付款 → 工具层引导取消；退款中 → 已在流程（业务层只报当前状态）
        assertThat(refundPending.status()).isEqualTo(OrderActionOutcome.Status.NOT_ALLOWED);
        assertThat(refundPending.currentStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(refundRefunding.status()).isEqualTo(OrderActionOutcome.Status.NOT_ALLOWED);
        assertThat(refundRefunding.currentStatus()).isEqualTo("REFUNDING");
        // 拒绝时状态与库存都不动
        assertThat(productMapper.selectById(productId).getStock()).isEqualTo(8);
    }

    @Test
    void refund_twice_second_is_rejected_without_double_stock_restore() {
        long productId = insertProduct("测试风扇", "129.00", 6);
        PlaceOutcome placed = tradeService.place("u9103", productId, 1);
        forceStatus(placed.orderNo(), "SHIPPED");

        tradeService.refund("u9103", placed.orderNo());
        OrderActionOutcome again = tradeService.refund("u9103", placed.orderNo());

        assertThat(again.status()).isEqualTo(OrderActionOutcome.Status.NOT_ALLOWED);
        assertThat(again.currentStatus()).isEqualTo("REFUNDED");
        // 库存只还一次
        assertThat(productMapper.selectById(productId).getStock()).isEqualTo(6);
    }

    // ===== 取消状态机（T5.2：仅待付款可取消）=====

    @Test
    void cancel_pending_payment_succeeds_and_restores_stock() {
        long productId = insertProduct("测试鼠标", "49.00", 7);
        PlaceOutcome placed = tradeService.place("u9106", productId, 3);

        OrderActionOutcome outcome = tradeService.cancel("u9106", placed.orderNo());

        assertThat(outcome.status()).isEqualTo(OrderActionOutcome.Status.DONE);
        assertThat(outcome.targetStatus()).isEqualTo("CANCELLED");
        assertThat(outcome.amount()).isEqualByComparingTo("147.00");
        assertThat(orderStatus(placed.orderNo())).isEqualTo("CANCELLED");
        assertThat(productMapper.selectById(productId).getStock()).isEqualTo(7);
    }

    @Test
    void cancel_rejects_non_pending_and_repeat_cancel() {
        long productId = insertProduct("测试显示器", "899.00", 4);
        PlaceOutcome shipped = tradeService.place("u9107", productId, 1);
        forceStatus(shipped.orderNo(), "SHIPPED");
        PlaceOutcome pending = tradeService.place("u9107", productId, 1);

        OrderActionOutcome cancelShipped = tradeService.cancel("u9107", shipped.orderNo());
        tradeService.cancel("u9107", pending.orderNo());
        OrderActionOutcome cancelTwice = tradeService.cancel("u9107", pending.orderNo());

        assertThat(cancelShipped.status()).isEqualTo(OrderActionOutcome.Status.NOT_ALLOWED);
        assertThat(cancelShipped.currentStatus()).isEqualTo("SHIPPED");
        assertThat(cancelTwice.status()).isEqualTo(OrderActionOutcome.Status.NOT_ALLOWED);
        assertThat(cancelTwice.currentStatus()).isEqualTo("CANCELLED");
        // 库存 4 → 下两单 2 → 取消待付款单还 1 = 3，只还一次
        assertThat(productMapper.selectById(productId).getStock()).isEqualTo(3);
    }

    // ===== 归属校验（W1D8 口径：他人订单与不存在同话术）=====

    @Test
    void refund_and_cancel_unknown_or_foreign_order_same_not_found() {
        long productId = insertProduct("测试键盘", "299.00", 5);
        PlaceOutcome mine = tradeService.place("u9104", productId, 1);
        forceStatus(mine.orderNo(), "SHIPPED");

        assertThat(tradeService.refund("u9104", "99999999999999999999").status())
                .isEqualTo(OrderActionOutcome.Status.ORDER_NOT_FOUND);
        assertThat(tradeService.refund("u9105", mine.orderNo()).status())
                .isEqualTo(OrderActionOutcome.Status.ORDER_NOT_FOUND);
        assertThat(tradeService.cancel("u9104", "99999999999999999999").status())
                .isEqualTo(OrderActionOutcome.Status.ORDER_NOT_FOUND);
        assertThat(tradeService.cancel("u9105", mine.orderNo()).status())
                .isEqualTo(OrderActionOutcome.Status.ORDER_NOT_FOUND);
        // 越权尝试后库存与状态不变
        assertThat(productMapper.selectById(productId).getStock()).isEqualTo(4);
        assertThat(orderStatus(mine.orderNo())).isEqualTo("SHIPPED");
    }

    private void forceStatus(String orderNo, String status) {
        Order order = orderMapper.selectOne(
                Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, orderNo));
        order.setStatus(status);
        orderMapper.updateById(order);
    }

    private String orderStatus(String orderNo) {
        return orderMapper.selectOne(
                Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, orderNo)).getStatus();
    }

    private long insertProduct(String name, String price, int stock) {
        Product product = new Product();
        product.setName(name);
        product.setCategory("测试类目");
        product.setPrice(new BigDecimal(price));
        product.setStock(stock);
        product.setDescription("trade test fixture");
        productMapper.insert(product);
        return product.getId();
    }
}

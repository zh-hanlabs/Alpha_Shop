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

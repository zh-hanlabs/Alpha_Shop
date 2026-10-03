package com.shopagent.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shopagent.entity.Logistics;
import com.shopagent.entity.Order;
import com.shopagent.entity.OrderItem;
import com.shopagent.entity.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MockDataQueryTest {

    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private LogisticsMapper logisticsMapper;

    @Test
    void orders_load_with_demo_story() {
        assertThat(orderMapper.selectCount(null)).isEqualTo(6L);

        Order o10001 = orderMapper.selectOne(
                Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, "10001"));
        assertThat(o10001).isNotNull();
        assertThat(o10001.getUserId()).isEqualTo("u1001");
        assertThat(o10001.getStatus()).isEqualTo("SHIPPED");
        // 相对时间防回归：更新时间在 6 小时前 ± 容差，写死日期会出现「物流明年」
        assertThat(o10001.getUpdatedAt()).isAfter(LocalDateTime.now().minusHours(24));
    }

    @Test
    void order_10001_contains_power_bank() {
        List<OrderItem> items = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderNo, "10001"));
        assertThat(items).hasSize(2);
        assertThat(items).anyMatch(i -> i.getProductName().contains("移动电源"));
        assertThat(items).anyMatch(i -> i.getProductName().contains("数据线"));
    }

    @Test
    void order_10006_belongs_to_other_user() {
        Order o10006 = orderMapper.selectOne(
                Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, "10006"));
        assertThat(o10006.getUserId()).isEqualTo("u1002");
    }

    @Test
    void logistics_tracks_load_as_json() {
        Logistics lg = logisticsMapper.selectOne(
                Wrappers.<Logistics>lambdaQuery().eq(Logistics::getOrderNo, "10001"));
        assertThat(lg).isNotNull();
        assertThat(lg.getStatus()).isEqualTo("IN_TRANSIT");
        assertThat(lg.getTracks()).startsWith("[{").contains("desc", "location");
    }

    @Test
    void products_load_with_price_and_stock() {
        List<Product> products = productMapper.selectList(null);
        assertThat(products).hasSize(8);

        Product powerBank = products.stream()
                .filter(p -> p.getId() == 1L).findFirst().orElseThrow();
        assertThat(powerBank.getPrice()).isEqualByComparingTo("129.00");
        assertThat(powerBank.getStock()).isEqualTo(45);
    }
}

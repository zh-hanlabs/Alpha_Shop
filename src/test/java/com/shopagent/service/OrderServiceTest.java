package com.shopagent.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderServiceTest {

    @Autowired
    private OrderService orderService;

    @Test
    void queryOrderDetail_returns_order_with_items() {
        OrderDetail detail = orderService.queryOrderDetail("10001", "u1001");

        assertThat(detail).isNotNull();
        assertThat(detail.status()).isEqualTo("SHIPPED");
        assertThat(detail.totalAmount()).isEqualByComparingTo("148.90");
        assertThat(detail.items()).hasSize(2);
        assertThat(detail.items())
                .anySatisfy(i -> {
                    assertThat(i.productName()).contains("移动电源");
                    assertThat(i.quantity()).isEqualTo(1);
                })
                .anySatisfy(i -> assertThat(i.productName()).contains("数据线"));
        assertThat(detail.createdAt()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
    }

    @Test
    void queryOrderDetail_returns_null_when_not_found() {
        assertThat(orderService.queryOrderDetail("99999", "u1001")).isNull();
    }

    @Test
    void queryOrderDetail_rejects_other_users_order() {
        // 10006 属于 u1002：u1001 查询必须返回 null（与不存在不可区分，不泄露存在性）
        assertThat(orderService.queryOrderDetail("10006", "u1001")).isNull();
        assertThat(orderService.queryOrderDetail("10006", "u1002")).isNotNull();
    }

    @Test
    void recentOrders_returns_latest_first_for_user() {
        List<OrderService.OrderSummary> orders = orderService.recentOrders("u1001");

        assertThat(orders).hasSize(5);
        // 10003 是 3 小时前下的单，应排最前
        assertThat(orders.get(0).orderNo()).isEqualTo("10003");
        assertThat(orders.get(0).status()).isEqualTo("PENDING_PAYMENT");
        assertThat(orders).allSatisfy(o -> assertThat(o.createdAt()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"));
    }

    @Test
    void recentOrders_is_scoped_by_user() {
        List<OrderService.OrderSummary> orders = orderService.recentOrders("u1002");

        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).orderNo()).isEqualTo("10006");
    }

    @Test
    void recentOrders_returns_empty_for_unknown_user() {
        assertThat(orderService.recentOrders("nobody")).isEmpty();
    }
}

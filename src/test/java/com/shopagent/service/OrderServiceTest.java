package com.shopagent.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderServiceTest {

    @Autowired
    private OrderService orderService;

    @Test
    void queryOrderDetail_returns_order_with_items() {
        OrderDetail detail = orderService.queryOrderDetail("10001");

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
        assertThat(orderService.queryOrderDetail("99999")).isNull();
    }
}

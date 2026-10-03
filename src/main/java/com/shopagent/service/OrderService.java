package com.shopagent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shopagent.entity.Order;
import com.shopagent.entity.OrderItem;
import com.shopagent.mapper.OrderItemMapper;
import com.shopagent.mapper.OrderMapper;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class OrderService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    public OrderService(OrderMapper orderMapper, OrderItemMapper orderItemMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
    }

    public OrderDetail queryOrderDetail(String orderNo) {
        Order order = orderMapper.selectOne(
                Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, orderNo));
        if (order == null) {
            return null;
        }
        List<OrderItem> items = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderNo, orderNo));
        return new OrderDetail(
                order.getOrderNo(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCreatedAt().format(TIME_FORMAT),
                items.stream()
                        .map(i -> new OrderDetail.Item(i.getProductName(), i.getQuantity(), i.getUnitPrice()))
                        .toList());
    }
}

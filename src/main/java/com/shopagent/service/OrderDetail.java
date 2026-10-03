package com.shopagent.service;

import java.math.BigDecimal;
import java.util.List;

// 时间格式化成字符串：工具序列化对 LocalDateTime 不友好，且模型要的是可读文本
public record OrderDetail(
        String orderNo,
        String status,
        BigDecimal totalAmount,
        String createdAt,
        List<Item> items) {

    public record Item(String productName, int quantity, BigDecimal unitPrice) {}
}

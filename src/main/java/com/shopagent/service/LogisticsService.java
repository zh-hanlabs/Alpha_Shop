package com.shopagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopagent.entity.Logistics;
import com.shopagent.entity.Order;
import com.shopagent.mapper.LogisticsMapper;
import com.shopagent.mapper.OrderMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LogisticsService {

    private final LogisticsMapper logisticsMapper;
    private final OrderMapper orderMapper;
    private final ObjectMapper objectMapper;

    public LogisticsService(LogisticsMapper logisticsMapper, OrderMapper orderMapper, ObjectMapper objectMapper) {
        this.logisticsMapper = logisticsMapper;
        this.orderMapper = orderMapper;
        this.objectMapper = objectMapper;
    }

    // 轨迹保持结构化（time/desc/location），自然语言摘要由模型生成——工具给事实，措辞交给 LLM
    public record TrackPoint(String time, String desc, String location) {}

    public record LogisticsDetail(
            String orderNo,
            String carrier,
            String trackingNo,
            String status,
            List<TrackPoint> tracks) {}

    public LogisticsDetail queryLogistics(String orderNo, String userId) {
        // 物流轨迹按订单归属放行：他人订单与不存在同样返回 null，不泄露订单存在性
        Order order = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getOrderNo, orderNo)
                .eq(Order::getUserId, userId));
        if (order == null) {
            return null;
        }
        Logistics logistics = logisticsMapper.selectOne(
                Wrappers.<Logistics>lambdaQuery().eq(Logistics::getOrderNo, orderNo));
        if (logistics == null) {
            return null;
        }
        List<TrackPoint> tracks = parseTracks(logistics.getTracks());
        return new LogisticsDetail(
                logistics.getOrderNo(),
                logistics.getCarrier(),
                logistics.getTrackingNo(),
                logistics.getStatus(),
                tracks);
    }

    private List<TrackPoint> parseTracks(String tracksJson) {
        try {
            return objectMapper.readValue(tracksJson, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("物流轨迹解析失败: " + e.getMessage(), e);
        }
    }
}

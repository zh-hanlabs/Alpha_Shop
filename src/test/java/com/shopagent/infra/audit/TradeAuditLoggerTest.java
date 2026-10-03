package com.shopagent.infra.audit;

import com.shopagent.entity.TradeAuditLog;
import com.shopagent.infra.guard.TradeRequest;
import com.shopagent.mapper.TradeAuditLogMapper;
import com.shopagent.tools.support.ToolResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeAuditLoggerTest {

    private final TradeAuditLogMapper auditLogMapper = mock(TradeAuditLogMapper.class);
    private final TradeAuditLogger logger = new TradeAuditLogger(auditLogMapper);

    private TradeRequest request() {
        return new TradeRequest("u1001", "refund", "10005",
                "lock:trade:u1001:order:10005", "abc123");
    }

    @Test
    void log_writes_full_audit_fields() {
        ToolResult result = ToolResult.ok(Map.of("orderNo", "10005"));

        logger.log(request(), result);

        ArgumentCaptor<TradeAuditLog> captor = ArgumentCaptor.forClass(TradeAuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        TradeAuditLog entry = captor.getValue();
        assertThat(entry.getUserId()).isEqualTo("u1001");
        assertThat(entry.getAction()).isEqualTo("refund");
        assertThat(entry.getOrderNo()).isEqualTo("10005");
        assertThat(entry.getIdempotentKey()).isEqualTo("abc123");
        assertThat(entry.getResultCode()).isEqualTo(ToolResult.CODE_SUCCESS);
        assertThat(entry.getResultMsg()).isEqualTo("success");
    }

    @Test
    void log_place_order_allows_null_order_no() {
        TradeRequest placeReq = new TradeRequest("u1001", "placeOrder", null,
                "lock:trade:u1001:placeOrder:7", "def456");

        logger.log(placeReq, ToolResult.ok(Map.of("orderNo", "20261003120000000001")));

        ArgumentCaptor<TradeAuditLog> captor = ArgumentCaptor.forClass(TradeAuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        // 下单时订单号尚不存在：审计留空，经幂等键关联
        assertThat(captor.getValue().getOrderNo()).isNull();
        assertThat(captor.getValue().getAction()).isEqualTo("placeOrder");
    }

    @Test
    void log_failure_is_swallowed_not_propagated() {
        when(auditLogMapper.insert(any(TradeAuditLog.class)))
                .thenThrow(new RuntimeException("db down"));

        // 审计是物证不是闸门：写失败不阻断交易主流程
        assertThatCode(() -> logger.log(request(), ToolResult.ok(null)))
                .doesNotThrowAnyException();
    }

    @Test
    void log_truncates_overlong_msg() {
        String longMsg = "x".repeat(300);

        logger.log(request(), ToolResult.reject(longMsg));

        ArgumentCaptor<TradeAuditLog> captor = ArgumentCaptor.forClass(TradeAuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getResultMsg()).hasSize(255);
    }
}

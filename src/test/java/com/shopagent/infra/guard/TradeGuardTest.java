package com.shopagent.infra.guard;

import com.shopagent.infra.audit.TradeAuditLogger;
import com.shopagent.infra.idempotent.IdempotentExecutor;
import com.shopagent.infra.lock.LockExecutor;
import com.shopagent.tools.support.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeGuardTest {

    private final LockExecutor lockExecutor = mock(LockExecutor.class);
    private final IdempotentExecutor idempotentExecutor = mock(IdempotentExecutor.class);
    private final TradeAuditLogger auditLogger = mock(TradeAuditLogger.class);
    private final AtomicInteger executions = new AtomicInteger();
    private TradeGuard guard;

    @BeforeEach
    void setUp() {
        guard = new TradeGuard(lockExecutor, idempotentExecutor, auditLogger);
        executions.set(0);
    }

    private ToolResult refundAction() {
        executions.incrementAndGet();
        return ToolResult.ok(Map.of("orderNo", "10001"));
    }

    private TradeRequest request(String idempotentKey) {
        return new TradeRequest("u1001", "refund", "10001",
                "lock:trade:u1001:order:10001", idempotentKey);
    }

    @Test
    void replay_returns_first_result_without_locking_and_audits() {
        ToolResult first = ToolResult.ok(Map.of("orderNo", "10001"));
        when(idempotentExecutor.peekResult("key-1")).thenReturn(first);

        ToolResult result = guard.execute(request("key-1"), this::refundAction);

        // 重放免抢锁：锁外快查直接命中首次结果，不占临界区
        assertThat(result).isSameAs(first);
        assertThat(executions.get()).isZero();
        verify(lockExecutor, never()).withLock(anyString(), any());
        // 重放也留审计痕：重放场景两条记录同幂等键（W4D3 完成标志）
        verify(auditLogger).log(argThat(req -> req.idempotentKey().equals("key-1")), eq(first));
    }

    @Test
    @SuppressWarnings("unchecked")
    void first_execution_goes_through_lock_then_idempotent_and_audits_in_lock() {
        when(idempotentExecutor.peekResult("key-2")).thenReturn(null);
        when(lockExecutor.withLock(eq("lock:trade:u1001:order:10001"), any()))
                .thenAnswer(inv -> ((Supplier<ToolResult>) inv.getArgument(1)).get());
        when(idempotentExecutor.execute(eq("key-2"), any()))
                .thenAnswer(inv -> ((Supplier<ToolResult>) inv.getArgument(1)).get());

        ToolResult result = guard.execute(request("key-2"), this::refundAction);

        assertThat(result.code()).isEqualTo(ToolResult.CODE_SUCCESS);
        assertThat(executions.get()).isEqualTo(1);
        verify(idempotentExecutor).execute(eq("key-2"), any());
        // 锁内随 result 同步写审计（W4D3）：时间线与业务执行顺序一致
        verify(auditLogger).log(argThat(req -> req.idempotentKey().equals("key-2")), eq(result));
    }

    @Test
    void peek_failure_degrades_to_full_gate_and_fails_closed() {
        // 快查故障表现为 null → 走完整闸序；Redis 仍故障时 withLock fail-closed
        when(idempotentExecutor.peekResult("key-3")).thenReturn(null);
        ToolResult failClosed = ToolResult.error("交易暂不可用，请稍后再试");
        when(lockExecutor.withLock(anyString(), any())).thenReturn(failClosed);

        ToolResult result = guard.execute(request("key-3"), this::refundAction);

        assertThat(result).isSameAs(failClosed);
        assertThat(executions.get()).isZero();
        // 审计在锁内随 result 写：抢锁失败/Redis 故障没进临界区，交易未发生不记审计（应用日志兜底）
        verify(auditLogger, never()).log(any(), any());
    }

    @Test
    void trade_request_carries_audit_fields() {
        TradeRequest req = request("key-5");
        assertThat(req.userId()).isEqualTo("u1001");
        assertThat(req.action()).isEqualTo("refund");
        assertThat(req.orderNo()).isEqualTo("10001");
        assertThat(req.lockKey()).isEqualTo("lock:trade:u1001:order:10001");
    }
}

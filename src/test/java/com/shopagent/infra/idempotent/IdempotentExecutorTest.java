package com.shopagent.infra.idempotent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopagent.tools.support.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotentExecutorTest {

    private final RedissonClient redissonClient = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    private final RBucket<String> resultBucket = mock(RBucket.class);
    @SuppressWarnings("unchecked")
    private final RBucket<String> markBucket = mock(RBucket.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger executions = new AtomicInteger();
    private IdempotentExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new IdempotentExecutor(redissonClient, objectMapper);
        doReturn(resultBucket).when(redissonClient).getBucket(startsWith("idempotent:result:"));
        doReturn(markBucket).when(redissonClient).getBucket(startsWith("idempotent:mark:"));
        executions.set(0);
    }

    private ToolResult placeAction() {
        executions.incrementAndGet();
        return ToolResult.ok(Map.of("orderNo", "20261003120000000001"));
    }

    @Test
    void replay_returns_first_result_without_re_execute() throws Exception {
        // 直接构造首次结果写入 Redis，不经 placeAction()——计数器只统计执行器触发的业务调用
        ToolResult first = ToolResult.ok(Map.of("orderNo", "20261003120000000001"));
        when(resultBucket.get()).thenReturn(objectMapper.writeValueAsString(first));

        ToolResult replayed = executor.execute("key-1", this::placeAction);

        // 幂等语义（ADR D3）：同键重放返回首次结果而非报错，业务零重复执行
        assertThat(replayed.code()).isEqualTo(ToolResult.CODE_SUCCESS);
        assertThat(replayed.msg()).isEqualTo("success");
        assertThat(replayed.data()).isEqualTo(first.data());
        assertThat(executions.get()).isEqualTo(0);
        verify(markBucket, never()).trySet(any(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void mark_hit_without_result_means_in_flight() {
        when(resultBucket.get()).thenReturn(null);
        when(markBucket.trySet(any(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        ToolResult result = executor.execute("key-2", this::placeAction);

        // 在途或前次崩溃残留：宁可拒绝不可重复
        assertThat(result.code()).isEqualTo(ToolResult.CODE_BUSINESS_REJECT);
        assertThat(result.msg()).contains("处理中");
        assertThat(executions.get()).isEqualTo(0);
    }

    @Test
    void first_execution_stores_result_and_returns_action_result() {
        when(resultBucket.get()).thenReturn(null);
        when(markBucket.trySet(any(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        ToolResult result = executor.execute("key-3", this::placeAction);

        assertThat(result.code()).isEqualTo(ToolResult.CODE_SUCCESS);
        assertThat(result.data()).isEqualTo(Map.of("orderNo", "20261003120000000001"));
        assertThat(executions.get()).isEqualTo(1);
        verify(resultBucket).set(anyString(), eq(TimeUnit.DAYS.toSeconds(1)), eq(TimeUnit.SECONDS));
        verify(markBucket, never()).delete();
    }

    @Test
    void business_reject_is_deterministic_and_stored_for_replay() {
        when(resultBucket.get()).thenReturn(null);
        when(markBucket.trySet(any(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        ToolResult result = executor.execute("key-4", () -> ToolResult.reject("库存不足"));

        // 业务拒绝也是确定结果：同句重放应拿到同一答复而非反复扣库存试探
        assertThat(result.code()).isEqualTo(ToolResult.CODE_BUSINESS_REJECT);
        verify(resultBucket).set(anyString(), anyLong(), any(TimeUnit.class));
        verify(markBucket, never()).delete();
    }

    @Test
    void transient_error_releases_mark_for_retry() {
        when(resultBucket.get()).thenReturn(null);
        when(markBucket.trySet(any(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        ToolResult result = executor.execute("key-5", () -> ToolResult.error("下单失败"));

        // 瞬时系统错误不留痕：删 mark 放行下次重试，不把偶发故障固化 24h
        assertThat(result.code()).isEqualTo(ToolResult.CODE_ERROR);
        verify(markBucket).delete();
        verify(resultBucket, never()).set(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void action_exception_deletes_mark_and_returns_unavailable() {
        when(resultBucket.get()).thenReturn(null);
        when(markBucket.trySet(any(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        ToolResult result = executor.execute("key-6", () -> {
            throw new IllegalStateException("db down");
        });

        assertThat(result.code()).isEqualTo(ToolResult.CODE_ERROR);
        assertThat(result.msg()).contains("暂不可用");
        verify(markBucket).delete();
    }

    @Test
    void redis_failure_fails_closed() {
        when(resultBucket.get()).thenThrow(new RuntimeException("connection refused"));

        ToolResult result = executor.execute("key-7", this::placeAction);

        // fail-closed：Redis 不可用时交易工具拒绝执行而非裸跑业务
        assertThat(result.code()).isEqualTo(ToolResult.CODE_ERROR);
        assertThat(result.msg()).contains("暂不可用");
        assertThat(executions.get()).isEqualTo(0);
        verify(markBucket).delete();
    }

    // ===== 锁外快查（TradeGuard 闸序用）=====

    @Test
    void peek_returns_cached_result() throws Exception {
        ToolResult first = ToolResult.ok(Map.of("orderNo", "20261003120000000001"));
        when(resultBucket.get()).thenReturn(objectMapper.writeValueAsString(first));

        ToolResult peeked = executor.peekResult("key-8");

        assertThat(peeked.code()).isEqualTo(ToolResult.CODE_SUCCESS);
        assertThat(peeked.data()).isEqualTo(first.data());
    }

    @Test
    void peek_returns_null_on_miss() {
        when(resultBucket.get()).thenReturn(null);

        assertThat(executor.peekResult("key-9")).isNull();
    }

    @Test
    void peek_failure_degrades_to_null_without_throwing() {
        when(resultBucket.get()).thenThrow(new RuntimeException("connection refused"));

        // 快查故障不抛异常：降级 null 走完整闸序，fail-closed 由 withLock 兜底
        assertThat(executor.peekResult("key-10")).isNull();
    }
}

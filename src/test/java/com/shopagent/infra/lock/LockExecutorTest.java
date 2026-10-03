package com.shopagent.infra.lock;

import com.shopagent.tools.support.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LockExecutorTest {

    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final AtomicInteger executions = new AtomicInteger();
    private LockExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new LockExecutor(redissonClient);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        executions.set(0);
    }

    private ToolResult placeAction() {
        executions.incrementAndGet();
        return ToolResult.ok(Map.of("orderNo", "20261003120000000001"));
    }

    @Test
    void lock_acquired_runs_action_and_unlocks() throws Exception {
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        ToolResult result = executor.withLock("lock:trade:u1001:placeOrder:1", this::placeAction);

        assertThat(result.code()).isEqualTo(ToolResult.CODE_SUCCESS);
        assertThat(executions.get()).isEqualTo(1);
        // 定稿 §2.2：只传 waitTime=3s 不传 leaseTime → 看门狗续期
        verify(lock).tryLock(LockExecutor.WAIT_MILLIS, TimeUnit.MILLISECONDS);
        verify(lock).unlock();
    }

    @Test
    void lock_busy_rejects_without_running_action() throws Exception {
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);

        ToolResult result = executor.withLock("lock:trade:u1001:placeOrder:1", this::placeAction);

        // 3s 内没抢到：不排队堆积，直接「操作处理中」
        assertThat(result.code()).isEqualTo(ToolResult.CODE_BUSINESS_REJECT);
        assertThat(result.msg()).contains("处理中");
        assertThat(executions.get()).isEqualTo(0);
        verify(lock, never()).unlock();
    }

    @Test
    void action_failure_still_unlocks() throws Exception {
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        ToolResult result = executor.withLock("k", () -> {
            throw new IllegalStateException("db down");
        });

        // finally 解锁防泄漏：业务挂了锁必须还回去，否则后续请求全被「处理中」卡死
        assertThat(result.code()).isEqualTo(ToolResult.CODE_ERROR);
        verify(lock).unlock();
    }

    @Test
    void unlock_skipped_when_lock_not_held_anymore() throws Exception {
        // 锁已被 Redis 过期回收/易主：unlock 会抛 IllegalMonitorStateException，防御性跳过
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        ToolResult result = executor.withLock("k", this::placeAction);

        assertThat(result.code()).isEqualTo(ToolResult.CODE_SUCCESS);
        verify(lock, never()).unlock();
    }

    @Test
    void redis_failure_fails_closed() {
        when(redissonClient.getLock(anyString())).thenThrow(new RuntimeException("connection refused"));

        ToolResult result = executor.withLock("k", this::placeAction);

        // fail-closed：Redis 不可用时不裸跑业务（与幂等组件同语义）
        assertThat(result.code()).isEqualTo(ToolResult.CODE_ERROR);
        assertThat(result.msg()).contains("暂不可用");
        assertThat(executions.get()).isEqualTo(0);
    }

    @Test
    void interrupt_restores_flag_and_rejects() throws Exception {
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("wait cut"));

        ToolResult result = executor.withLock("k", this::placeAction);

        assertThat(result.code()).isEqualTo(ToolResult.CODE_ERROR);
        assertThat(executions.get()).isEqualTo(0);
        // 中断标记必须复位（不吞线程池的关停信号）
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted(); // 清理，避免污染 JUnit 同线程的后续用例
    }
}

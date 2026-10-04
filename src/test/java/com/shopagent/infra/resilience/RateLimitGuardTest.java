package com.shopagent.infra.resilience;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitGuardTest {

    private final RedissonClient redisson = mock(RedissonClient.class);
    private final Map<String, RRateLimiter> limiterMocks = new HashMap<>();
    private RateLimitGuard guard;

    private RRateLimiter user;
    private RRateLimiter global;

    @BeforeEach
    void setUp() {
        // 按 key 名分发独立 limiter mock，用户桶/全局桶行为可分别编排；
        // 两个桶先入 map 再 stub（thenAnswer 只在 getRateLimiter 调用时才填充）
        user = limiterMocks.computeIfAbsent(
                RateLimitGuard.USER_KEY_PREFIX + "u1001", k -> mock(RRateLimiter.class));
        global = limiterMocks.computeIfAbsent(RateLimitGuard.GLOBAL_KEY, k -> mock(RRateLimiter.class));
        when(redisson.getRateLimiter(anyString()))
                .thenAnswer(inv -> limiterMocks.computeIfAbsent(
                        inv.getArgument(0), k -> mock(RRateLimiter.class)));
        when(user.trySetRate(any(RateType.class), anyLong(), any(Duration.class), any(Duration.class)))
                .thenReturn(true);
        when(global.trySetRate(any(RateType.class), anyLong(), any(Duration.class))).thenReturn(true);
        guard = new RateLimitGuard(redisson,
                2, Duration.ofSeconds(1), Duration.ofHours(1),
                10, Duration.ofSeconds(1));
    }

    @Test
    void 用户桶拒绝_即短路_全局桶零消耗() {
        when(user.tryAcquire()).thenReturn(false);

        RateLimitGuard.Verdict verdict = guard.tryAcquire("u1001");

        assertThat(verdict).isEqualTo(RateLimitGuard.Verdict.USER_LIMITED);
        verify(global, never()).tryAcquire();
    }

    @Test
    void 全局桶拒绝_返回全局限流() {
        when(user.tryAcquire()).thenReturn(true);
        when(global.tryAcquire()).thenReturn(false);

        RateLimitGuard.Verdict verdict = guard.tryAcquire("u1001");

        assertThat(verdict).isEqualTo(RateLimitGuard.Verdict.GLOBAL_LIMITED);
    }

    @Test
    void 双桶都通过_放行() {
        when(user.tryAcquire()).thenReturn(true);
        when(global.tryAcquire()).thenReturn(true);

        assertThat(guard.tryAcquire("u1001")).isEqualTo(RateLimitGuard.Verdict.ALLOWED);
    }

    @Test
    void Redis故障_failOpen放行() {
        when(redisson.getRateLimiter(anyString())).thenThrow(new RedisException("connection refused"));

        RateLimitGuard.Verdict verdict = guard.tryAcquire("u1001");

        assertThat(verdict).isEqualTo(RateLimitGuard.Verdict.ALLOWED);
    }

    @Test
    void 每次请求都先trySetRate_幂等重建_TTL到期自愈() {
        when(user.tryAcquire()).thenReturn(true);
        when(global.tryAcquire()).thenReturn(true);

        guard.tryAcquire("u1001");
        guard.tryAcquire("u1001");

        // D0 定稿：无配置桶 tryAcquire 抛 RedisException，故每次请求先 trySetRate（幂等）；
        // 用户桶只走四参重载，三参是全局桶专属
        verify(user, times(2)).trySetRate(any(RateType.class), anyLong(), any(Duration.class), any(Duration.class));
        verify(user, never()).trySetRate(any(RateType.class), anyLong(), any(Duration.class));
        verify(global, times(2)).trySetRate(any(RateType.class), anyLong(), any(Duration.class));
    }

    @Test
    void 参数锁定_用户桶四参TTL版_全局桶三参常驻版() {
        when(user.tryAcquire()).thenReturn(true);
        when(global.tryAcquire()).thenReturn(true);

        guard.tryAcquire("u1001");

        // D0 冒烟定稿：用户桶四参带 TTL=1h（低频自动回收），全局桶三参常驻
        verify(user).trySetRate(
                eq(RateType.OVERALL), eq(2L), eq(Duration.ofSeconds(1)), eq(Duration.ofHours(1)));
        verify(global).trySetRate(
                eq(RateType.OVERALL), eq(10L), eq(Duration.ofSeconds(1)));
    }
}

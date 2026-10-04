package com.shopagent.infra.resilience;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W6D0 T0.2 RRateLimiter 桶语义单机冒烟（真 Redis，不进默认 mvn test，见 w6 清单 §五）。
 * 单独跑：mvn test -Dtest=RRateLimiterSemanticsSmokeTest -Dsmoke.redis=true
 * 实测定稿（w6 清单 §2.1 回填依据）：
 * ① trySetRate 只首次生效（重复设 false），setRate 才覆盖；
 * ② 四参签名 = (type, rate, interval, ttl)，TTL 连内部键 {key}:value/{key}:permits 一起覆盖
 *    → 桶键 TTL 用四参一步到位；三参建的桶主键+内部键永不过期，只能靠 setRate 前置清理或运维删键；
 * ③ RExpirable.expire 同样覆盖内部键（手动续期方案可行，但四参已够用）；
 * ④ PER_CLIENT 按 Redisson 客户端实例分桶（内部键带 clientId 后缀），非终端用户 → 设计选 OVERALL+userId 键名；
 * ⑤ 无配置桶 tryAcquire 抛 RedisException → Guard 必须先 trySetRate（幂等）再 tryAcquire。
 */
@EnabledIfSystemProperty(named = "smoke.redis", matches = "true")
class RRateLimiterSemanticsSmokeTest {

    private static final String PREFIX = "rlimit:smoke:w6d0:";

    private static RedissonClient clientA;
    private static RedissonClient clientB;

    @BeforeAll
    static void setUp() {
        clientA = singleClient();
        clientB = singleClient();
    }

    @AfterAll
    static void tearDown() {
        // 内部键形如 {主键}:value（花括号 hash-tag 开头），模式须含前导 * 才能连内部键一起清
        clientA.getKeys().deleteByPattern("*" + PREFIX + "*");
        clientA.shutdown();
        clientB.shutdown();
    }

    private static RedissonClient singleClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        return Redisson.create(config);
    }

    @Test
    void t1_trySetRateOnlyFirstEffective_setRateOverrides() {
        RRateLimiter limiter = clientA.getRateLimiter(PREFIX + "t1");
        boolean first = limiter.trySetRate(RateType.OVERALL, 2, Duration.ofSeconds(1));
        boolean second = limiter.trySetRate(RateType.OVERALL, 5, Duration.ofSeconds(1));
        int passedAt2 = acquireN(limiter, 5);
        System.out.println("[T1] trySetRate 首次=" + first + " 重复设=" + second
                + " → 5 连发通过=" + passedAt2 + "（配额仍是 2 则只首次生效实证）");
        assertTrue(first);
        assertFalse(second);
        assertEquals(2, passedAt2);

        sleep(1100);
        limiter.setRate(RateType.OVERALL, 5, Duration.ofSeconds(1));
        int passedAfterOverride = acquireN(limiter, 5);
        System.out.println("[T1] setRate 覆盖为 5/1s → 5 连发通过=" + passedAfterOverride);
        assertEquals(5, passedAfterOverride);
    }

    @Test
    void t2_rate2PerSec_fiveCalls_twoPassThreeReject() {
        RRateLimiter limiter = clientA.getRateLimiter(PREFIX + "t2");
        limiter.trySetRate(RateType.OVERALL, 2, Duration.ofSeconds(1));
        int passed = acquireN(limiter, 5);
        System.out.println("[T2] 2/1s 连发 5 次：通过=" + passed + " 拒绝=" + (5 - passed));
        assertEquals(2, passed);
    }

    @Test
    void t3_threeParamTrySetRate_noTtl() {
        RRateLimiter limiter = clientA.getRateLimiter(PREFIX + "t3");
        limiter.trySetRate(RateType.OVERALL, 2, Duration.ofSeconds(1));
        limiter.tryAcquire();
        long ttl = ttlOf(PREFIX + "t3");
        System.out.println("[T3] 三参 trySetRate 后主键 PTTL=" + ttl + "ms（-1=无过期）");
        dumpKeys("T3");
        assertEquals(-1L, ttl);
    }

    @Test
    void t4_fourParamTrySetRate_ttlSemantics() {
        RRateLimiter limiter = clientA.getRateLimiter(PREFIX + "t4");
        // 传参刻意错开 interval=1s / ttl=60s，靠 PTTL 读数判定第 4 参到底是哪个语义
        limiter.trySetRate(RateType.OVERALL, 2, Duration.ofSeconds(1), Duration.ofSeconds(60));
        long ttl = ttlOf(PREFIX + "t4");
        System.out.println("[T4] 四参 trySetRate(interval=1s, ttl=60s) 主键 PTTL=" + ttl + "ms");
        int passed = acquireN(limiter, 5);
        System.out.println("[T4] 5 连发通过=" + passed + "（interval 语义校验，应为 2）");
        dumpKeys("T4");
        assertEquals(2, passed);
        assertTrue(ttl > 0 && ttl <= 60_000, "四参带 TTL 语义，主键 PTTL 应在 (0, 60s]");
        // 四参 TTL 对内部键的覆盖：实测 {key}:value/{key}:permits 同步带 TTL
        assertTrue(ttlOf("{" + PREFIX + "t4}:value") > 55_000, "四参 TTL 应覆盖内部 value 键");
        assertTrue(ttlOf("{" + PREFIX + "t4}:permits") > 55_000, "四参 TTL 应覆盖内部 permits 键");
    }

    @Test
    void t5_expireCoverageOnInnerKeys() {
        RRateLimiter limiter = clientA.getRateLimiter(PREFIX + "t5");
        limiter.trySetRate(RateType.OVERALL, 2, Duration.ofSeconds(1));
        limiter.tryAcquire();
        limiter.expire(Duration.ofSeconds(60));
        System.out.println("[T5] 三参建桶 + 手动 expire(60s) 后各键 PTTL（含内部键）：");
        dumpKeys("T5");
        long ttl = ttlOf(PREFIX + "t5");
        assertTrue(ttl > 55_000 && ttl <= 60_000, "主键 PTTL 应≈60s，实测=" + ttl);
        // RExpirable.expire 对内部键的覆盖范围：实测连 {key}:value/{key}:permits 一起带上
        assertTrue(ttlOf("{" + PREFIX + "t5}:value") > 55_000, "expire 应覆盖内部 value 键");
        assertTrue(ttlOf("{" + PREFIX + "t5}:permits") > 55_000, "expire 应覆盖内部 permits 键");
    }

    @Test
    void t6_perClientVsOverall() {
        RRateLimiter perClientA = clientA.getRateLimiter(PREFIX + "t6per");
        RRateLimiter perClientB = clientB.getRateLimiter(PREFIX + "t6per");
        boolean first = perClientA.trySetRate(RateType.PER_CLIENT, 2, Duration.ofSeconds(1));
        boolean second = perClientB.trySetRate(RateType.PER_CLIENT, 2, Duration.ofSeconds(1));
        int a = acquireN(perClientA, 3);
        int b = acquireN(perClientB, 3);
        System.out.println("[T6] PER_CLIENT 2/1s：A首设=" + first + " B重复设=" + second
                + " → A通过=" + a + " B通过=" + b + "（各客户端独立桶则 2/2）");
        assertTrue(first);
        assertFalse(second);
        assertEquals(2, a);
        assertEquals(2, b);

        RRateLimiter overallA = clientA.getRateLimiter(PREFIX + "t6all");
        RRateLimiter overallB = clientB.getRateLimiter(PREFIX + "t6all");
        overallA.trySetRate(RateType.OVERALL, 2, Duration.ofSeconds(1));
        // 两客户端交替取，规避 1s 窗口翻转带来的偶发
        int totalPassed = 0;
        for (int i = 0; i < 6; i++) {
            RRateLimiter turn = i % 2 == 0 ? overallA : overallB;
            if (turn.tryAcquire()) {
                totalPassed++;
            }
        }
        System.out.println("[T6] OVERALL 2/1s 两客户端交替 6 次：合计通过=" + totalPassed + "（共享桶则 2）");
        assertEquals(2, totalPassed);
    }

    @Test
    void t7_acquireWithoutConfig() {
        RRateLimiter limiter = clientA.getRateLimiter(PREFIX + "t7");
        // 定稿结论：无配置桶 tryAcquire 直接抛 RedisException（"RateLimiter is not initialized"），
        // 故 RateLimitGuard 必须每次先 trySetRate（幂等）再 tryAcquire，顺带覆盖桶 TTL 到期后的重建
        assertThrows(RedisException.class, limiter::tryAcquire);
    }

    private int acquireN(RRateLimiter limiter, int n) {
        int passed = 0;
        for (int i = 0; i < n; i++) {
            if (limiter.tryAcquire()) {
                passed++;
            }
        }
        return passed;
    }

    private long ttlOf(String key) {
        RBucket<String> probe = clientA.getBucket(key);
        Long ttl = probe.remainTimeToLive();
        return ttl == null ? -2L : ttl;
    }

    private void dumpKeys(String tag) {
        RKeys keys = clientA.getKeys();
        // 内部键名形如 {主键}:value / {主键}:permits（花括号 hash-tag 开头），
        // 模式须含前导 * 才能扫到
        List<String> names = keys.getKeysStreamByPattern("*" + PREFIX + "*").toList();
        names.stream().sorted().forEach(k ->
                System.out.println("    [" + tag + "] key=" + k + " PTTL=" + ttlOf(k) + "ms"));
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

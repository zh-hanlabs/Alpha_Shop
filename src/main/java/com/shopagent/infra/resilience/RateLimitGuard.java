package com.shopagent.infra.resilience;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 双层限流闸（W6D1，设计定稿见 shopagent-w6-tasks.md §2.1）：用户桶 + 全局桶，护 LLM API。
 * Why 分布式令牌桶：Resilience4j RateLimiter 是进程内实现，多实例下单用户桶配额 ×N 放大，
 * 与 W6「接入层无状态扩容」主题矛盾——配额是共享资源语义（账号配额/用户公平性），状态必须放 Redis。
 * 与熔断的分工：熔断是实例自保可进程内（W6D3 接线），限流必须全局一致。
 */
@Component
public class RateLimitGuard {

    private static final Logger log = LoggerFactory.getLogger(RateLimitGuard.class);

    static final String USER_KEY_PREFIX = "rlimit:chat:user:";
    static final String GLOBAL_KEY = "rlimit:chat:global";

    /** Verdict 区分两层来源：controller 日志与 W6D4 观测记账用，对前端两者同罚 */
    public enum Verdict { ALLOWED, USER_LIMITED, GLOBAL_LIMITED }

    private final RedissonClient redisson;
    private final long userRate;
    private final Duration userInterval;
    private final Duration userBucketTtl;
    private final long globalRate;
    private final Duration globalInterval;

    public RateLimitGuard(
            RedissonClient redisson,
            @Value("${resilience.rate-limit.user-rate:2}") long userRate,
            @Value("${resilience.rate-limit.user-interval:1s}") Duration userInterval,
            @Value("${resilience.rate-limit.user-bucket-ttl:1h}") Duration userBucketTtl,
            @Value("${resilience.rate-limit.global-rate:10}") long globalRate,
            @Value("${resilience.rate-limit.global-interval:1s}") Duration globalInterval) {
        this.redisson = redisson;
        this.userRate = userRate;
        this.userInterval = userInterval;
        this.userBucketTtl = userBucketTtl;
        this.globalRate = globalRate;
        this.globalInterval = globalInterval;
    }

    /**
     * 检查顺序定稿：先用户桶后全局桶——被用户桶拒绝的请求不消耗全局配额
     * （单用户刷子不该挤占全局限额）；两层都 tryAcquire() 即时返回，不排队堆积
     * （与 W3 拿锁失败即返回同哲学）。
     * 每次先 trySetRate（幂等，D0 T7 实证：无配置桶 tryAcquire 直接抛 RedisException），
     * 顺带覆盖桶键 TTL 到期后的重建：用户桶四参 TTL 版低频自动回收，全局桶三参常驻。
     */
    public Verdict tryAcquire(String userId) {
        try {
            if (!userLimiter(userId).tryAcquire()) {
                log.info("rate limited at user bucket: userId={} rate={}/{}", userId, userRate, userInterval);
                return Verdict.USER_LIMITED;
            }
            if (!globalLimiter().tryAcquire()) {
                log.info("rate limited at global bucket: userId={}", userId);
                return Verdict.GLOBAL_LIMITED;
            }
            return Verdict.ALLOWED;
        } catch (Exception e) {
            // fail-open 定稿（§2.1）：限流器是保护器不是正确性来源，Redis 不可用时放行聊天
            // （与缓存/知识检索同级）；交易安全不经过这里，fail-closed 语义在 W3 链路不受影响
            log.warn("rate limit check failed, fail-open: userId={}", userId, e);
            return Verdict.ALLOWED;
        }
    }

    private RRateLimiter userLimiter(String userId) {
        RRateLimiter limiter = redisson.getRateLimiter(USER_KEY_PREFIX + userId);
        limiter.trySetRate(RateType.OVERALL, userRate, userInterval, userBucketTtl);
        return limiter;
    }

    private RRateLimiter globalLimiter() {
        RRateLimiter limiter = redisson.getRateLimiter(GLOBAL_KEY);
        limiter.trySetRate(RateType.OVERALL, globalRate, globalInterval);
        return limiter;
    }
}

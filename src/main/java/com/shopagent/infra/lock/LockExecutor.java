package com.shopagent.infra.lock;

import com.shopagent.tools.support.ToolResult;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁执行器（设计定稿 §2.2）。
 * tryLock 只传 waitTime 不传 leaseTime → 看门狗续期：业务执行再久锁不过期（防临界区失守），
 * 进程崩溃后锁由 Redis 默认 30s 过期回收。
 * 与幂等的双保险（§2.3）：锁防并发双写（mark SETNX 检查窗口），幂等防锁释放后的重放。
 */
@Component
public class LockExecutor {

    private static final Logger log = LoggerFactory.getLogger(LockExecutor.class);

    // 抢锁等待上限 3s：LLM 工具调用不做长排队，等不到就拒（「处理中」话术），防请求堆积
    static final long WAIT_MILLIS = 3000;
    private static final String MSG_LOCK_BUSY = "操作处理中，请稍后再试";
    private static final String MSG_UNAVAILABLE = "交易暂不可用，请稍后再试";

    private final RedissonClient redissonClient;

    public LockExecutor(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public ToolResult withLock(String lockKey, Supplier<ToolResult> action) {
        RLock lock = null;
        boolean locked = false;
        try {
            lock = redissonClient.getLock(lockKey);
            locked = lock.tryLock(WAIT_MILLIS, TimeUnit.MILLISECONDS);
            if (!locked) {
                return ToolResult.reject(MSG_LOCK_BUSY);
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("lock wait interrupted, key={}", lockKey);
            return ToolResult.error(MSG_UNAVAILABLE);
        } catch (Exception e) {
            // Redis 故障等：fail-closed，与幂等组件同语义（宁可不做交易不可失去保护）
            log.error("lock execute failed, key={}", lockKey, e);
            return ToolResult.error(MSG_UNAVAILABLE);
        } finally {
            // isHeldByCurrentThread 防误删：锁已被 Redis 过期回收/易主时 unlock 抛 IllegalMonitorStateException
            if (locked && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception e) {
                    log.warn("unlock failed, key={}", lockKey, e);
                }
            } else if (locked) {
                log.warn("lock no longer held by current thread, skip unlock, key={}", lockKey);
            }
        }
    }
}

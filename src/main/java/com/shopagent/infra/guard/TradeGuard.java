package com.shopagent.infra.guard;

import com.shopagent.infra.idempotent.IdempotentExecutor;
import com.shopagent.infra.lock.LockExecutor;
import com.shopagent.tools.support.ToolResult;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 交易闸序编排（设计定稿 §2.3）：result 快查（锁外）→ 抢锁(3s) → 锁内幂等四态 → finally 解锁。
 * Why 抽象：placeOrder/refundOrder/cancelOrder 三处同构编排（AGENTS.md 三处重复再抽）。
 * Why 快查在锁外：重放请求直接返回首次结果，不付抢锁开销，也不占用临界区；
 * 快查与抢锁之间的空窗由 execute 锁内二次 result 快查兜底（双检）。
 */
@Component
public class TradeGuard {

    private final LockExecutor lockExecutor;
    private final IdempotentExecutor idempotentExecutor;

    public TradeGuard(LockExecutor lockExecutor, IdempotentExecutor idempotentExecutor) {
        this.lockExecutor = lockExecutor;
        this.idempotentExecutor = idempotentExecutor;
    }

    public ToolResult execute(String lockKey, String idempotentKey, Supplier<ToolResult> action) {
        ToolResult cached = idempotentExecutor.peekResult(idempotentKey);
        if (cached != null) {
            return cached;
        }
        return lockExecutor.withLock(lockKey, () ->
                idempotentExecutor.execute(idempotentKey, action));
    }
}

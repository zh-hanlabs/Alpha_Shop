package com.shopagent.infra.guard;

import com.shopagent.infra.audit.TradeAuditLogger;
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
 * W4D3 审计：每个出口（重放命中/锁内完整闸序）各写一条审计——重放场景两条记录同幂等键。
 */
@Component
public class TradeGuard {

    private final LockExecutor lockExecutor;
    private final IdempotentExecutor idempotentExecutor;
    private final TradeAuditLogger auditLogger;

    public TradeGuard(LockExecutor lockExecutor, IdempotentExecutor idempotentExecutor,
                      TradeAuditLogger auditLogger) {
        this.lockExecutor = lockExecutor;
        this.idempotentExecutor = idempotentExecutor;
        this.auditLogger = auditLogger;
    }

    public ToolResult execute(TradeRequest request, Supplier<ToolResult> action) {
        ToolResult cached = idempotentExecutor.peekResult(request.idempotentKey());
        if (cached != null) {
            auditLogger.log(request, cached);
            return cached;
        }
        // 审计在锁内写：并发下审计时间线与业务实际执行顺序一致，不因锁外乱序误导排查
        return lockExecutor.withLock(request.lockKey(), () -> {
            ToolResult result = idempotentExecutor.execute(request.idempotentKey(), action);
            auditLogger.log(request, result);
            return result;
        });
    }
}

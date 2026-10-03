package com.shopagent.infra.idempotent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopagent.tools.support.ToolResult;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 显式幂等执行器（不用注解 AOP：@Tool 方法由 Spring AI 反射调用，代理有兼容风险）。
 * 四态语义（设计定稿 §2.1）：
 *  1. result 命中 → 返回首次结果。不报错：LLM 见错会重试成死循环（ADR D3）
 *  2. mark 命中无 result → 在途或前次崩溃 → 「操作处理中」。宁可拒绝不可重复
 *  3. 确定性结果（成功/参数错/查无/业务拒绝）→ 照存 result，同键重放返回同结果
 *  4. 瞬时错误（CODE_ERROR 或异常）→ 删 mark 放行下次重试。mark 只为「已开始」负责
 */
@Component
public class IdempotentExecutor {

    private static final Logger log = LoggerFactory.getLogger(IdempotentExecutor.class);

    static final String MARK_PREFIX = "idempotent:mark:";
    static final String RESULT_PREFIX = "idempotent:result:";
    // 86400s：覆盖「当天重放」窗口；误判边缘场景（同句隔天复购被拦）记为已知局限（§2.1）
    static final long TTL_SECONDS = TimeUnit.DAYS.toSeconds(1);

    private static final String MARK_VALUE = "1";
    static final String MSG_IN_FLIGHT = "操作处理中，请稍后再试";
    static final String MSG_UNAVAILABLE = "交易暂不可用，请稍后再试";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    public IdempotentExecutor(RedissonClient redissonClient, ObjectMapper objectMapper) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
    }

    public ToolResult execute(String idempotentKey, Supplier<ToolResult> action) {
        String resultKey = RESULT_PREFIX + idempotentKey;
        String markKey = MARK_PREFIX + idempotentKey;
        try {
            RBucket<String> resultBucket = redissonClient.getBucket(resultKey);
            String cached = resultBucket.get();
            if (cached != null) {
                return objectMapper.readValue(cached, ToolResult.class);
            }
            RBucket<String> markBucket = redissonClient.getBucket(markKey);
            // trySet = SETNX + EX 原子置位
            if (!markBucket.trySet(MARK_VALUE, TTL_SECONDS, TimeUnit.SECONDS)) {
                return ToolResult.reject(MSG_IN_FLIGHT);
            }
            ToolResult result = action.get();
            if (result.code() == ToolResult.CODE_ERROR) {
                markBucket.delete();
            } else {
                resultBucket.set(objectMapper.writeValueAsString(result), TTL_SECONDS, TimeUnit.SECONDS);
            }
            return result;
        } catch (Exception e) {
            // Redis 故障或业务未消化异常：fail-closed（宁可不做交易不可重复），删 mark 保可重试
            log.error("idempotent execute failed, key={}", idempotentKey, e);
            try {
                redissonClient.getBucket(markKey).delete();
            } catch (Exception cleanupFailure) {
                log.warn("idempotent mark cleanup failed, key={}", idempotentKey, cleanupFailure);
            }
            return ToolResult.error(MSG_UNAVAILABLE);
        }
    }
}

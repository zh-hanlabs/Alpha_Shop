package com.shopagent.infra.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.Metrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * LLM 熔断器（W6D3，设计定稿 shopagent-w6-tasks.md §2.2）：Resilience4j programmatic 封装，
 * 单实例全局一个（name=llmChat）——护的是同一个 DeepSeek API，所有会话共享命运。
 * Why 熔断进程内而限流分布式（面试核心点）：熔断是实例自保——各实例独立探测独立降级，
 * 半开探测流量有限（3 次/实例）可控；限流配额是共享资源语义（账号配额/用户公平性），
 * 必须全局一致，状态放 Redis 换一致性。
 * 失败判定：execute 内的调用抛异常即记 failure（连接拒绝/超时/5xx/流中断）；
 * 工具失败到不了这层（W1 铁律：工具内消化不外抛）；慢调用阈值不开（保守起步，只算失败率）。
 */
@Component
public class LlmCircuitBreaker {

    public static final String NAME = "llmChat";

    private final CircuitBreaker breaker;

    public LlmCircuitBreaker(
            @Value("${resilience.circuit-breaker.sliding-window-size:10}") int slidingWindowSize,
            @Value("${resilience.circuit-breaker.failure-rate-threshold:50}") float failureRateThreshold,
            @Value("${resilience.circuit-breaker.minimum-number-of-calls:5}") int minimumNumberOfCalls,
            @Value("${resilience.circuit-breaker.wait-duration-in-open-state:20s}") Duration waitDurationInOpenState,
            @Value("${resilience.circuit-breaker.permitted-calls-in-half-open-state:3}") int permittedCallsInHalfOpenState) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .failureRateThreshold(failureRateThreshold)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(waitDurationInOpenState)
                .permittedNumberOfCallsInHalfOpenState(permittedCallsInHalfOpenState)
                .build();
        this.breaker = CircuitBreaker.of(NAME, config);
    }

    /** LLM 调用经此执行：CLOSED 下失败照常透传给调用方做降级，同时被记录进滑窗；OPEN 直接抛 CallNotPermittedException 短路 */
    public <T> T execute(Supplier<T> llmCall) {
        return breaker.executeSupplier(llmCall);
    }

    public String state() {
        return breaker.getState().name();
    }

    /** dev 端点观测快照：失败率/滑窗计数/短路次数（W7 压测可对照） */
    public Map<String, Object> snapshot() {
        Metrics metrics = breaker.getMetrics();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("state", state());
        snapshot.put("failureRate", metrics.getFailureRate());
        snapshot.put("bufferedCalls", metrics.getNumberOfBufferedCalls());
        snapshot.put("failedCalls", metrics.getNumberOfFailedCalls());
        snapshot.put("successfulCalls", metrics.getNumberOfSuccessfulCalls());
        snapshot.put("notPermittedCalls", metrics.getNumberOfNotPermittedCalls());
        return snapshot;
    }

    /** demo 口径（§2.2）：reset 模拟「API 已恢复」时刻——真实场景靠半开探测自动闭合 */
    public void reset() {
        breaker.reset();
    }

    /** demo 口径：无故障注入时演示 OPEN 降级形态 */
    public void forceOpen() {
        breaker.transitionToForcedOpenState();
    }
}

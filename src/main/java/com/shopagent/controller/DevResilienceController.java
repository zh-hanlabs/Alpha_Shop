package com.shopagent.controller;

import com.shopagent.infra.resilience.LlmCircuitBreaker;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 熔断演示控制端点（W6D3，§2.2 定稿）。Why dev-only：reset/force-open 是演示动作，
 * 真实场景 API 恢复后半开探测自动闭合（waitDurationInOpenState 后放行探测，成功即闭合）；
 * demo 用 reset 模拟「API 已恢复」时刻，force-open 在无故障注入时演示 OPEN 降级形态。
 * @Profile("dev") 保证演示/生产运行（默认无 profile）不注册此端点。
 */
@RestController
@RequestMapping("/api/dev/resilience")
@Profile("dev")
public class DevResilienceController {

    private final LlmCircuitBreaker breaker;

    public DevResilienceController(LlmCircuitBreaker breaker) {
        this.breaker = breaker;
    }

    /** 熔断器观测快照：状态 + 失败率 + 滑窗计数 + 短路次数 */
    @GetMapping("/breaker")
    public Map<String, Object> breaker() {
        return breaker.snapshot();
    }

    /** 模拟「API 已恢复」时刻：清空滑窗回 CLOSED（README 说明口径） */
    @PostMapping("/breaker/reset")
    public Map<String, Object> reset() {
        breaker.reset();
        return breaker.snapshot();
    }

    /** 无故障注入时的降级形态演示：强制 OPEN */
    @PostMapping("/breaker/force-open")
    public Map<String, Object> forceOpen() {
        breaker.forceOpen();
        return breaker.snapshot();
    }
}

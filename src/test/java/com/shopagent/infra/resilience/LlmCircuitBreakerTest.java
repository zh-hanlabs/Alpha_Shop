package com.shopagent.infra.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmCircuitBreakerTest {

    private static RuntimeException llmDown() {
        return new RuntimeException("connection refused");
    }

    @Test
    void CLOSED下失败透传调用方且记入滑窗() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(10, 50, 5, Duration.ofSeconds(20), 3);

        assertThatThrownBy(() -> breaker.execute(() -> { throw llmDown(); }))
                .isInstanceOf(RuntimeException.class);

        assertThat(breaker.state()).isEqualTo("CLOSED");
        assertThat(breaker.snapshot().get("failedCalls")).isEqualTo(1);
    }

    @Test
    void 失败率过阈值_自动跳OPEN() {
        // min 2 次 / 阈值 50%：连续 2 失败 = 100% → OPEN
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(2, 50, 2, Duration.ofSeconds(20), 2);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> breaker.execute(() -> { throw llmDown(); }));
        }

        assertThat(breaker.state()).isEqualTo("OPEN");
    }

    @Test
    void OPEN_短路不发起LLM调用() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(2, 50, 2, Duration.ofSeconds(20), 2);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> breaker.execute(() -> { throw llmDown(); }));
        }
        boolean[] invoked = {false};

        assertThatThrownBy(() -> breaker.execute(() -> {
            invoked[0] = true;
            return "ok";
        })).isInstanceOf(CallNotPermittedException.class);

        assertThat(invoked[0]).isFalse();
        assertThat(breaker.snapshot().get("notPermittedCalls")).isEqualTo(1L);
    }

    @Test
    void 半开放行探测_探测成功_闭合() throws Exception {
        // waitDuration 压到 150ms 便于单测：OPEN 后等待，放行 2 次探测，全成功 → CLOSED
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(2, 50, 2, Duration.ofMillis(150), 2);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> breaker.execute(() -> { throw llmDown(); }));
        }
        assertThat(breaker.state()).isEqualTo("OPEN");
        Thread.sleep(250);

        assertThat(breaker.execute(() -> "probe1")).isEqualTo("probe1");
        assertThat(breaker.state()).isEqualTo("HALF_OPEN");
        assertThat(breaker.execute(() -> "probe2")).isEqualTo("probe2");
        assertThat(breaker.state()).isEqualTo("CLOSED");
    }

    @Test
    void 半开探测仍失败_回OPEN继续降级() throws Exception {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(2, 50, 2, Duration.ofMillis(150), 2);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> breaker.execute(() -> { throw llmDown(); }));
        }
        Thread.sleep(250);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> breaker.execute(() -> { throw llmDown(); }));
        }
        assertThat(breaker.state()).isEqualTo("OPEN");
    }

    @Test
    void reset_清滑窗回CLOSED_forceOpen_强制短路() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(2, 50, 2, Duration.ofSeconds(20), 2);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> breaker.execute(() -> { throw llmDown(); }));
        }
        assertThat(breaker.state()).isEqualTo("OPEN");
        breaker.reset();
        assertThat(breaker.state()).isEqualTo("CLOSED");

        breaker.forceOpen();
        assertThat(breaker.state()).isEqualTo("FORCED_OPEN");
        assertThatThrownBy(() -> breaker.execute(() -> "ok"))
                .isInstanceOf(CallNotPermittedException.class);
    }
}

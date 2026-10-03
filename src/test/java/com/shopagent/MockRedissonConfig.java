package com.shopagent;

import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.mockito.Mockito.mock;

/**
 * 单测环境的 Redisson 替身。
 * Why：RedissonAutoConfiguration 对用户定义的 RedissonClient bean 退避（@ConditionalOnMissingBean），
 * 本类在 com.shopagent 包下、位于 test classpath，会被主应用的组件扫描拾取——
 * 于是 @SpringBootTest 全量启动也不依赖真实 Redis（测试口径：外部环境零依赖）。
 * 运行真实应用时 test 类不编译，自动配置照常创建真客户端。
 */
@Configuration
public class MockRedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        return mock(RedissonClient.class);
    }
}

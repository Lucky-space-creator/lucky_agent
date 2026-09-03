package com.lucky.agent.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.cache.CacheProvider;
import com.lucky.agent.common.cache.InMemoryCacheProvider;
import com.lucky.agent.common.concurrent.ReadWriteLockGuard;
import com.lucky.agent.common.concurrent.SemaphoreGuard;
import com.lucky.agent.common.concurrent.TokenBucket;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * agent-common 配置：统一提供并发守卫 / 限流 / 缓存 / JSON 序列化 Bean。
 *
 * <p>所有并发手段封装为抽象，未来换 Redis/Sentinel 实现不动业务代码（§2.2 / R10）。</p>
 */
@Configuration
@EnableConfigurationProperties(CommonProperties.class)
public class CommonConfig {

    /** 模型在途数信号量守卫（R6），默认上限见 {@link CommonProperties#modelSemaphore()}。 */
    @Bean
    public SemaphoreGuard modelSemaphoreGuard(CommonProperties properties) {
        return new SemaphoreGuard("model-inflight", properties.modelSemaphore());
    }

    /** 全局入口限流（Guava 令牌桶）。 */
    @Bean
    public TokenBucket globalRateLimiter(CommonProperties properties) {
        return new TokenBucket("global", properties.globalRateLimit());
    }

    /** 记忆读写锁（防 .memory 并发写）。 */
    @Bean
    public ReadWriteLockGuard memoryLockGuard() {
        return new ReadWriteLockGuard("memory");
    }

    /** 默认内存缓存（演进时替换 Caffeine/远端缓存）。 */
    @Bean
    public CacheProvider defaultCacheProvider() {
        return new InMemoryCacheProvider("default");
    }

    /** 全局 ObjectMapper（JavaTime 模块）。 */
    @Bean
    public ObjectMapper agentObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}

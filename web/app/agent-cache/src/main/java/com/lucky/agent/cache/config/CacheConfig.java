package com.lucky.agent.cache.config;

import com.lucky.agent.common.cache.CacheProvider;
import com.lucky.agent.common.cache.InMemoryCacheProvider;
import com.lucky.agent.cache.session.SessionMessageCache;
import com.lucky.agent.cache.session.TtlSweeper;
import com.lucky.agent.cache.toolresult.FingerprintCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;

/**
 * 缓存模块配置（§4.6 / D5）。
 */
@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    @Bean
    @Primary
    public CacheProvider cacheProvider() {
        return new InMemoryCacheProvider("agent-cache");
    }

    @Bean
    public SessionMessageCache sessionMessageCache(CacheProvider provider, CacheProperties props) {
        return new SessionMessageCache(provider,
                java.util.concurrent.TimeUnit.DAYS.toSeconds(props.sessionTtlDays()));
    }

    @Bean
    public FingerprintCache fingerprintCache(CacheProvider provider) {
        return new FingerprintCache(provider);
    }

    @Bean
    public TtlSweeper ttlSweeper(CacheProvider provider, CacheProperties props) {
        return new TtlSweeper(provider,
                java.util.concurrent.TimeUnit.HOURS.toSeconds(props.sweepHours()));
    }
}

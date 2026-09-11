package com.lucky.agent.cache.config;

import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 缓存配置项（§4.6）。
 */
@Setter
@ConfigurationProperties(prefix = "cache")
public class CacheProperties {

    /** 会话缓存 TTL（天）。 */
    private int sessionTtlDays = 3;
    /** 工具结果缓存 TTL（天）。 */
    private int toolTtlDays = 3;
    /** 清理周期（小时）。 */
    private int sweepHours = 6;

    public int sessionTtlDays() {
        return sessionTtlDays;
    }

    public int toolTtlDays() {
        return toolTtlDays;
    }

    public int sweepHours() {
        return sweepHours;
    }

}

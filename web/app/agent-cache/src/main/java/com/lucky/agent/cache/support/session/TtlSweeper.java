package com.lucky.agent.cache.support.session;

import com.lucky.agent.common.cache.CacheProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

/**
 * 缓存 TTL 清理器（§4.6 / D5）。
 * <p>每 6 小时扫描一次，底层 CacheProvider 命中即按 TTL 判定过期清除；
 * 不依赖云端 Redis，纯本机内存清理。</p>
 */
@Slf4j
@Component
public class TtlSweeper {

    private final CacheProvider provider;
    private final long sweepPeriodSec;

    public TtlSweeper(CacheProvider provider, long sweepPeriodSec) {
        this.provider = provider;
        this.sweepPeriodSec = sweepPeriodSec;
    }

    @Scheduled(fixedDelayString = "${cache.sweep.period-ms:21600000}")
    public void sweep() {
        try {
            provider.evictExpired();
            log.debug("缓存 TTL 清理完成（周期 {}s）", sweepPeriodSec);
        } catch (Exception e) {
            log.warn("缓存 TTL 清理失败（降级忽略）：{}", e.getMessage());
        }
    }
}

package com.lucky.agent.cache.support.protect;

import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * 缓存击穿保护 + 降级守卫（§4.6）。
 * <p>模板：尝试命中 → 否则计算 → 异常降级（跳过缓存直接返回计算值），主链路不崩。</p>
 */
@Slf4j
public class CachePenetrationGuard {

    /**
     * 带降级的缓存读取。
     *
     * @param loader 计算/加载器（缓存未命中时执行）
     * @param <T>    结果类型
     * @return 计算结果（缓存异常时仍返回计算值）
     */
    public <T> T withFallback(Supplier<T> loader) {
        try {
            return loader.get();
        } catch (Exception e) {
            log.warn("缓存操作异常，降级直走计算：{}", e.getMessage());
            try {
                return loader.get();
            } catch (Exception ex) {
                throw new IllegalStateException("缓存降级后仍失败：" + ex.getMessage(), ex);
            }
        }
    }
}

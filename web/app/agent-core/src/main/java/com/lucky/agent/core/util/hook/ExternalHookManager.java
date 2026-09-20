package com.lucky.agent.core.util.hook;

import com.lucky.agent.common.contract.LifecycleHook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 外部 Hook 管理器：持有配置 → 适配器实例，供裁决链使用与外部 CRUD。
 * <p>配置变更（save/delete）后立即重建适配器列表；失败条目跳过并记录，不影响其余 Hook。</p>
 */
@Slf4j
@Component
public class ExternalHookManager {

    private final ExternalHookStore store;
    private final ExternalHookFactory factory;
    private volatile List<LifecycleHook> hooks;

    public ExternalHookManager(ExternalHookStore store, ExternalHookFactory factory) {
        this.store = store;
        this.factory = factory;
        this.hooks = buildHooks(store.load());
        log.info("外部 Hook 已加载，共 {} 个", hooks.size());
    }

    /** 当前生效的外部 Hook 适配器列表（供裁决链合并）。 */
    public List<LifecycleHook> hooks() {
        return hooks;
    }

    /** 全部外部 Hook 配置。 */
    public synchronized List<ExternalHookConfig> list() {
        return store.load();
    }

    /** 新增或更新外部 Hook 配置；保存后即时生效。 */
    public synchronized ExternalHookConfig save(ExternalHookConfig config) {
        String id = (config.id() == null || config.id().isBlank())
                ? UUID.randomUUID().toString() : config.id();
        ExternalHookConfig normalized = new ExternalHookConfig(
                id, config.name(), config.type(), config.eventName(), config.order(),
                config.enabled(), config.command(), config.url(), config.method(),
                config.headers(), config.timeoutSec(), config.serverName(), config.toolName());
        List<ExternalHookConfig> configs = new ArrayList<>(store.load());
        configs.removeIf(c -> id.equals(c.id()));
        configs.add(normalized);
        store.save(configs);
        hooks = buildHooks(configs);
        return normalized;
    }

    /** 删除外部 Hook 配置；返回是否实际删除。 */
    public synchronized boolean delete(String id) {
        List<ExternalHookConfig> configs = new ArrayList<>(store.load());
        boolean removed = configs.removeIf(c -> id.equals(c.id()));
        if (removed) {
            store.save(configs);
            hooks = buildHooks(configs);
        }
        return removed;
    }

    private List<LifecycleHook> buildHooks(List<ExternalHookConfig> configs) {
        List<LifecycleHook> result = new ArrayList<>();
        for (ExternalHookConfig config : configs) {
            try {
                result.add(factory.create(config));
            } catch (Exception e) {
                log.warn("外部 Hook 创建失败，跳过：{}", config.name(), e);
            }
        }
        return result;
    }
}

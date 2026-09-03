package com.lucky.agent.web.controller;

import com.lucky.agent.core.hook.ExternalHookConfig;
import com.lucky.agent.core.hook.ExternalHookManager;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 外部 Hook 管理接口（Shell / Webhook / MCP Tool，配置后即时生效）。
 */
@RestController
@RequestMapping("/api/hooks/external")
public class ExternalHookController {

    private final ExternalHookManager hookManager;

    public ExternalHookController(ExternalHookManager hookManager) {
        this.hookManager = hookManager;
    }

    /** 全部外部 Hook 配置。 */
    @GetMapping
    public List<ExternalHookConfig> list() {
        return hookManager.list();
    }

    /** 新增或更新外部 Hook 配置。 */
    @PostMapping
    public ExternalHookConfig save(@RequestBody ExternalHookConfig config) {
        return hookManager.save(config);
    }

    /** 删除外部 Hook 配置。 */
    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@PathVariable String id) {
        return Map.of("removed", hookManager.delete(id));
    }
}

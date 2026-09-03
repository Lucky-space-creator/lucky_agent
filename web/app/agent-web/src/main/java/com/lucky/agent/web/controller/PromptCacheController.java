package com.lucky.agent.web.controller;

import com.lucky.agent.model.prompt.PromptCacheService;
import com.lucky.agent.model.prompt.PromptCacheStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提示词缓存明细接口（命中率/覆盖率/继承统计）。
 */
@RestController
@RequestMapping("/api/prompt-cache")
public class PromptCacheController {

    private final PromptCacheService promptCacheService;

    public PromptCacheController(PromptCacheService promptCacheService) {
        this.promptCacheService = promptCacheService;
    }

    /** 缓存运行明细。 */
    @GetMapping("/status")
    public PromptCacheStatus status() {
        return promptCacheService.status();
    }
}

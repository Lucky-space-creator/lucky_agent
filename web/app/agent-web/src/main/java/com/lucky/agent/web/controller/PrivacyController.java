package com.lucky.agent.web.controller;

import com.lucky.agent.memory.api.MemoryStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 隐私合规接口（§5.2）：清记忆 / 隐私声明。
 */
@RestController
@RequestMapping("/api/privacy")
public class PrivacyController {

    private final MemoryStore userMemoryStore;

    public PrivacyController(@Qualifier("userMemoryStore") MemoryStore userMemoryStore) {
        this.userMemoryStore = userMemoryStore;
    }

    /** 清除用户本机记忆（.memory/ + 内存）。 */
    @PostMapping("/clear-memory")
    public Map<String, Object> clearMemory(@RequestParam(defaultValue = "local-user") String userId) {
        userMemoryStore.clear(userId);
        return Map.of("ok", true, "message", "本机记忆已清除");
    }

    /** 隐私声明。 */
    @GetMapping("/statement")
    public Map<String, String> statement() {
        return Map.of(
                "storage", "会话/记忆/文件只在用户本机，平台不存储、不管理、不回传",
                "modelRisk", "第三方模型风险由用户 Key 责任方承担",
                "memoryUpstream", "用户记忆只写本机文件，无上行通道");
    }
}

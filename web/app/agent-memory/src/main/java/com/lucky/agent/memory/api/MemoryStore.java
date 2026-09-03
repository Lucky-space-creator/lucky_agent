package com.lucky.agent.memory.api;

import com.lucky.agent.memory.api.dto.MemoryEntry;

import java.time.Instant;
import java.util.List;

/**
 * 记忆读写契约（双轨）。
 *
 * <p>用户轨可读写（本机文件持久化），平台轨只读（写入即拒绝）。
 * 用户记忆只写本机文件、无上行通道（隐私红线 R4）。</p>
 */
@com.lucky.agent.common.contract.Remote(serviceName = "memory-store")
public interface MemoryStore {

    /**
     * 追加一条记忆（用户轨落盘 JSONL；平台轨写入被拒绝）。
     *
     * @param entry 记忆条目
     */
    void append(MemoryEntry entry);

    /**
     * 追加用户记忆（便捷方法）。
     *
     * @param userId     用户 ID
     * @param content    内容
     * @param confidence 置信度
     * @param source     来源（user / observation / ask）
     */
    default void appendUser(String userId, String content, double confidence, String source) {
        append(MemoryEntry.user(Instant.now().toString(), userId, content, confidence, source));
    }

    /**
     * 加载记忆（用户轨按 userId 加载；平台轨忽略 userId）。
     *
     * @param userId 用户 ID
     * @return 记忆条目列表
     */
    List<MemoryEntry> loadAll(String userId);

    /**
     * 清除记忆（用户轨清除本机文件；平台轨只读不可清）。
     *
     * @param userId 用户 ID
     */
    void clear(String userId);
}

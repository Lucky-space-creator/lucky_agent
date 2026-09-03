package com.lucky.agent.memory.api;

import com.lucky.agent.common.contract.Remote;
import com.lucky.agent.memory.api.dto.MemoryEntry;
import com.lucky.agent.memory.api.dto.RecallResult;

import java.util.List;

/**
 * 记忆检索召回契约（双轨合并）。
 *
 * <p>用户偏好优先于平台建议（除非平台项标 FORCE）；召回结果带 {@code track} 标记，
 * 注入 core 的 system prompt 召回上下文层。</p>
 */
@Remote(serviceName = "memory-retriever")
public interface MemoryRetriever {

    /**
     * 双轨召回（用户轨 + 平台轨，用户偏好优先）。
     *
     * @param userId 用户 ID
     * @param query  查询文本
     * @param topK   返回条数
     * @return 召回结果
     */
    RecallResult recall(String userId, String query, int topK);

    /** 仅用户轨召回。 */
    List<MemoryEntry> recallUser(String userId, String query, int topK);

    /** 仅平台轨召回。 */
    List<MemoryEntry> recallPlatform(String query, int topK);
}

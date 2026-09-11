package com.lucky.agent.memory.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 记忆条目（双轨：用户/平台）。
 *
 * @param ts          时间戳
 * @param track       记忆轨（USER / PLATFORM）
 * @param userId      归属用户（平台轨可为空）
 * @param workspaceId 归属工作空间（JSONL 用户轨按项目分组，null 视为旧版全局记忆/平台轨）
 * @param content     内容
 * @param confidence  置信度（0~1）
 * @param source      来源：user / observation / ask / platform
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemoryEntry(
        @JsonProperty("ts") String ts,
        @JsonProperty("track") MemoryTrack track,
        @JsonProperty("userId") String userId,
        @JsonProperty("workspaceId") String workspaceId,
        @JsonProperty("content") String content,
        @JsonProperty("confidence") double confidence,
        @JsonProperty("source") String source) {

    public static MemoryEntry user(String ts, String userId, String content, double confidence, String source) {
        return new MemoryEntry(ts, MemoryTrack.USER, userId, null, content, confidence, source);
    }

    public static MemoryEntry user(String ts, String userId, String workspaceId,
                                   String content, double confidence, String source) {
        return new MemoryEntry(ts, MemoryTrack.USER, userId, workspaceId, content, confidence, source);
    }

    public static MemoryEntry platform(String ts, String content, double confidence) {
        return new MemoryEntry(ts, MemoryTrack.PLATFORM, null, null, content, confidence, "platform");
    }

    /** 记忆轨枚举。 */
    public enum MemoryTrack {
        /** 用户层面记忆（可读写，本机文件）。 */
        USER("USER"),
        /** 平台层面记忆（随包只读）。 */
        PLATFORM("PLATFORM");

        private final String code;

        MemoryTrack(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}

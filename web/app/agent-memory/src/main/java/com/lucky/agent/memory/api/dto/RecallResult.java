package com.lucky.agent.memory.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 召回结果（双轨合并，带 track 标记）。
 *
 * @param query   查询文本
 * @param entries 召回条目（按相关度排序）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecallResult(
        @JsonProperty("query") String query,
        @JsonProperty("entries") List<MemoryEntry> entries) {

    public static RecallResult of(String query, List<MemoryEntry> entries) {
        return new RecallResult(query, entries);
    }
}

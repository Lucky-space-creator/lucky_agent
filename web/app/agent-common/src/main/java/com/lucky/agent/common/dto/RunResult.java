package com.lucky.agent.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 运行结果（通道 → 前端）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@Accessors(chain = true, fluent = true)
public class RunResult {

    /** 运行结束状态。 */
    public enum RunStatus {
        SUCCESS("success"),
        MAX_TURNS("max_turns"),
        MAX_BUDGET("max_budget"),
        ERROR("error");

        private final String code;

        RunStatus(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    @JsonProperty("sessionId")
    private final String sessionId;
    @JsonProperty("status")
    private RunStatus status;
    @JsonProperty("summary")
    private String summary;
    @JsonProperty("error")
    private String error;
    @JsonProperty("tokenUsed")
    private long tokenUsed;
    @JsonProperty("model")
    private String model;

    public RunResult(String sessionId) {
        this.sessionId = sessionId;
    }

    public static RunResult of(String sessionId) {
        return new RunResult(sessionId);
    }
}

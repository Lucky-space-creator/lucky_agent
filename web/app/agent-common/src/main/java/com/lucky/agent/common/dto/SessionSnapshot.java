package com.lucky.agent.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话快照（通道只读视图）。
 *
 * <p>通道可经 {@code snapshot} 查询会话状态；所有状态查询走 ConversationStateManager，
 * 通道不得自行缓存会话。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@Accessors(chain = true, fluent = true)
public class SessionSnapshot {

    @JsonProperty("sessionId")
    private final String sessionId;
    @JsonProperty("userId")
    private final String userId;
    @JsonProperty("workspaceId")
    private final String workspaceId;
    @JsonProperty("messages")
    private List<MessageRecord> messages = new ArrayList<>();
    @JsonProperty("state")
    private Map<String, Object> state = new HashMap<>();

    public SessionSnapshot(String sessionId, String userId, String workspaceId) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.workspaceId = workspaceId;
    }

    /**
     * 会话消息记录。
     *
     * @param role          角色：user / assistant / system / observation
     * @param content       内容
     * @param ts            时间戳
     * @param checkpointIds 该消息执行期间创建的文件检查点 ID（按创建顺序），用于消息级回溯
     */
    public record MessageRecord(String role, String content, String ts, java.util.List<String> checkpointIds) {

        public MessageRecord(String role, String content, String ts) {
            this(role, content, ts, null);
        }
    }
}

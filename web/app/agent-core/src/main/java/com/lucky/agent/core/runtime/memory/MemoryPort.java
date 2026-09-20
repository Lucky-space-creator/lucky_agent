package com.lucky.agent.core.runtime.memory;

import com.lucky.agent.common.dto.SessionRef;

import java.util.Map;

/**
 * 记忆端口：把「压缩上下文 / 长期记忆沉淀」从主循环外置为可插拔能力。
 *
 * <p>策略约定（对应重构目标 7）：</p>
 * <ul>
 *   <li><b>成功才沉淀</b>：{@link #recordLongTerm} 仅在任务达成时调用。</li>
 *   <li><b>失败只压缩</b>：{@link #compact} 用于未达成时的上下文压缩，不做长期沉淀。</li>
 *   <li>压缩保留结构化字段：goal / constraints / attempts / openQuestions / toolUsage。</li>
 * </ul>
 *
 * <p>{@link SessionRef} 重载为推荐入口：分层记忆（全局/项目/会话）需要工作空间与会话双定位，
 * 仅靠 sessionId 无法定位；默认实现退化为 sessionId 版本以保持向后兼容。</p>
 */
public interface MemoryPort {

    /** 召回相关记忆（会话/项目长期），失败返回空串。 */
    String recall(String sessionId, String query);

    /** 沉淀长期记忆（仅成功路径调用）。 */
    void recordLongTerm(String sessionId, String content, Map<String, Object> structured);

    /** 压缩当前上下文（未达成路径调用），保留结构化字段。 */
    void compact(String sessionId, Map<String, Object> structuredFields);

    /** 召回（带完整会话定位）。 */
    default String recall(SessionRef ref, String query) {
        return recall(ref == null ? null : ref.sessionId(), query);
    }

    /** 沉淀长期记忆（带完整会话定位）。 */
    default void recordLongTerm(SessionRef ref, String content, Map<String, Object> structured) {
        recordLongTerm(ref == null ? null : ref.sessionId(), content, structured);
    }

    /** 压缩上下文（带完整会话定位）。 */
    default void compact(SessionRef ref, Map<String, Object> structuredFields) {
        compact(ref == null ? null : ref.sessionId(), structuredFields);
    }

    /** 结构化字段键名常量。 */
    final class Fields {
        public static final String GOAL = "goal";
        public static final String CONSTRAINTS = "constraints";
        public static final String ATTEMPTS = "attempts";
        public static final String OPEN_QUESTIONS = "openQuestions";
        public static final String TOOL_USAGE = "toolUsage";

        private Fields() {
        }
    }
}

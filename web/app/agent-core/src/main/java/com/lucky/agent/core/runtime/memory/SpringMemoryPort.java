package com.lucky.agent.core.runtime.memory;

import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.memory.support.md.HierarchyMemoryRetriever;
import com.lucky.agent.memory.support.md.MarkdownMemoryWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记忆端口的生产实现：桥接既有的分层 Markdown 记忆（{@link MarkdownMemoryWriter} 写入 +
 * {@link HierarchyMemoryRetriever} 召回），不新建第二套记忆存储。
 *
 * <p>语义边界：</p>
 * <ul>
 *   <li>{@link #recordLongTerm}：<b>仅成功路径</b>调用，把本轮结果交给分层记忆写入器做长期沉淀；</li>
 *   <li>{@link #compact}：<b>不写长期记忆</b>（否则失败路径的中间状态会污染长期记忆）。
 *       真实上下文压缩由既有 {@code CompactionPipeline} 在消息层完成；本方法只把结构化字段
 *       记入会话级「压缩日志」，供续跑与排障查看。</li>
 * </ul>
 *
 * <p>两个协作者均可为 null（对应能力未装配时自动降级），任何异常都不向上抛——
 * 记忆是增强能力，不应阻断主链路。</p>
 */
public class SpringMemoryPort implements MemoryPort {

    private static final Logger log = LoggerFactory.getLogger(SpringMemoryPort.class);

    /** 会话级压缩日志上限（防止长任务无限堆积）。 */
    private static final int JOURNAL_LIMIT = 32;

    private final MarkdownMemoryWriter writer;
    private final HierarchyMemoryRetriever retriever;
    private final Map<String, List<Map<String, Object>>> compactJournal = new ConcurrentHashMap<>();

    public SpringMemoryPort(MarkdownMemoryWriter writer, HierarchyMemoryRetriever retriever) {
        this.writer = writer;
        this.retriever = retriever;
    }

    @Override
    public String recall(String sessionId, String query) {
        return ""; // 无工作空间定位无法定位分层记忆，需走 SessionRef 重载
    }

    @Override
    public String recall(SessionRef ref, String query) {
        if (retriever == null || ref == null) {
            return "";
        }
        try {
            String text = retriever.recallText(ref.workspaceId(), ref.sessionId());
            return text == null ? "" : text;
        } catch (RuntimeException e) {
            log.warn("[memory] 召回失败（降级为无召回）: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public void recordLongTerm(String sessionId, String content, Map<String, Object> structured) {
        // 无工作空间定位时不沉淀，避免落到错误的项目分组
    }

    @Override
    public void recordLongTerm(SessionRef ref, String content, Map<String, Object> structured) {
        if (writer == null || ref == null) {
            return;
        }
        try {
            if (!writer.enabled()) {
                log.debug("[memory] 分层记忆未启用，跳过沉淀");
                return;
            }
            writer.updateForSession(ref, transcript(content, structured));
            log.info("[memory] 已沉淀长期记忆：session={} workspace={}", ref.sessionId(), ref.workspaceId());
        } catch (RuntimeException e) {
            log.warn("[memory] 沉淀失败（不影响主链路）: {}", e.getMessage());
        }
    }

    @Override
    public void compact(String sessionId, Map<String, Object> structuredFields) {
        // 无 workspace/session 定位时不记录
    }

    @Override
    public void compact(SessionRef ref, Map<String, Object> structuredFields) {
        if (ref == null) {
            return;
        }
        List<Map<String, Object>> journal = compactJournal.computeIfAbsent(
                ref.sessionId() == null ? "" : ref.sessionId(),
                k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (journal) {
            journal.add(Map.copyOf(structuredFields));
            while (journal.size() > JOURNAL_LIMIT) {
                journal.remove(0);
            }
        }
        log.debug("[memory] 记录压缩日志（不沉淀长期记忆）：goal={} attempts={}",
                structuredFields.get(Fields.GOAL), structuredFields.get(Fields.ATTEMPTS));
    }

    /** 读取会话级压缩日志（排障/续跑用）。 */
    public List<Map<String, Object>> compactJournal(String sessionId) {
        List<Map<String, Object>> journal = compactJournal.get(sessionId == null ? "" : sessionId);
        return journal == null ? List.of() : List.copyOf(journal);
    }

    /** 把结果与结构化字段拼成待沉淀的转写文本。 */
    private String transcript(String content, Map<String, Object> structured) {
        StringBuilder sb = new StringBuilder();
        if (content != null && !content.isBlank()) {
            sb.append(content);
        }
        if (structured != null && !structured.isEmpty()) {
            sb.append("\n\n【结构化字段】");
            structured.forEach((k, v) -> sb.append("\n- ").append(k).append(": ").append(v));
        }
        return sb.toString();
    }
}

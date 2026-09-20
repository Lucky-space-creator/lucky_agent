package com.lucky.agent.memory.support.md;

import com.lucky.agent.memory.config.MemoryMdProperties;

import java.util.List;
import java.util.Set;

/**
 * 分层召回读取器（Hint 型）：两级调用入口（对标 Claude Code）。
 *
 * <ul>
 *   <li>{@link #recallIndex(String)}：只读<b>索引</b>（用户级 + 本地级 MEMORY.md，小而全），
 *       每轮全量注入 System Prompt，形成「记忆使用手册」；</li>
 *   <li>{@link #recallEntries(String, Set)}：按锚点读取<b>条目全文</b>（预取/工具按需读取），
 *       避免全量注入造成上下文浪费；</li>
 *   <li>{@link #recallText(String, String)}：旧版全量召回（整体→项目→会话逐层全文），
 *       预取未启用/失败时兜底。</li>
 * </ul>
 */
public class HierarchyMemoryRetriever {

    private final MarkdownMemoryWriter writer;
    private final MemoryMdProperties props;

    public HierarchyMemoryRetriever(MarkdownMemoryWriter writer, MemoryMdProperties props) {
        this.writer = writer;
        this.props = props;
    }

    /**
     * 分层召回拼接文本（旧版全量召回：整体 → 项目 → 会话）。
     *
     * @param workspaceId 当前工作区（项目层）
     * @param sessionId   当前会话（会话层；null 则只召回整体+项目两层）
     * @return 精炼记忆文本（空串表示无可用记忆）
     */
    public String recallText(String workspaceId, String sessionId) {
        if (!props.enabled()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendLayer(sb, "整体记忆", writer.readGlobal());
        appendLayer(sb, "当前项目记忆", writer.readProject(workspaceId));
        if (sessionId != null && !sessionId.isBlank()) {
            appendLayer(sb, "当前会话记忆", writer.readSession(workspaceId, sessionId));
        }
        return sb.toString();
    }

    /**
     * 两级索引召回（用户级 + 本地级），轻量全量注入 System Prompt。
     *
     * @param workspaceId 当前工作区
     * @return 索引文本（无记忆条目时返回空串）
     */
    public String recallIndex(String workspaceId) {
        if (!props.enabled()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendLayer(sb, "用户级记忆", writer.readGlobal() == null ? "" : indexOf(writer.globalIndexFile()));
        appendLayer(sb, "当前项目记忆", indexOf(writer.projectIndexFile(workspaceId)));
        return sb.toString();
    }

    /**
     * 按锚点读取条目全文（两级都查），供预取选择器命中后注入。
     *
     * @param workspaceId 当前工作区
     * @param ids         锚点 id 集合
     * @return 条目文本（空串表示无命中）
     */
    public String recallEntries(String workspaceId, Set<String> ids) {
        if (!props.enabled() || ids == null || ids.isEmpty()) {
            return "";
        }
        return writer.readEntries(workspaceId, ids);
    }

    /**
     * 全量条目文本（两级 memory.md，无会话层），供仅索引不足以支撑的场景兜底。
     *
     * @param workspaceId 当前工作区
     * @return 用户级 + 本地级条目全文
     */
    public String recallFullText(String workspaceId) {
        if (!props.enabled()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendLayer(sb, "用户级记忆", writer.readGlobal());
        appendLayer(sb, "当前项目记忆", writer.readProject(workspaceId));
        return sb.toString();
    }

    private String indexOf(java.nio.file.Path indexFile) {
        if (indexFile == null || !java.nio.file.Files.exists(indexFile)) {
            return "";
        }
        try {
            return java.nio.file.Files.readString(indexFile, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private void appendLayer(StringBuilder sb, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append("### ").append(title).append('\n').append(content.trim());
    }

    /** 工具/辅助用：读取两级索引的原始行（供选择器入参）。 */
    public List<String> indexLines(String workspaceId) {
        return List.of(recallIndex(workspaceId));
    }
}
package com.lucky.agent.memory.support.md;

import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.memory.service.MemorySummaryModel;
import com.lucky.agent.memory.config.MemoryMdProperties;
import com.lucky.agent.memory.util.WorkspaceMemoryPaths;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 分层 Markdown 记忆写入器（Hint 型，Claude Code Auto Memory 风格）。
 *
 * <p>目录结构（位于 {@code <frameworkRoot>/memory/<root>/}，默认 {@code md}）：</p>
 * <pre>
 * memory/md/
 * ├── MEMORY.md                      # L1 用户级索引（用户对话记忆，memory 根下）
 * ├── memory.md                      # L1 用户级记忆条目（- [user/feedback/project/reference]）
 * └── &lt;工作空间全路径转义&gt;/
 *     ├── MEMORY.md                  # L2 本地级索引
 *     ├── memory.md                  # L2 本地级记忆条目
 *     └── sessions/&lt;sessionId&gt;.md   # L3 会话总结（LLM 生成，不入索引）
 * </pre>
 *
 * <p>{@link #updateForSession(SessionRef, String)} 在会话收尾时执行三级上卷：
 * 会话总结 → 项目合并 → 整体合并，每级都是「旧记忆 + 新增增量」交给记忆管理 Agent LLM
 * 增量合并（四类分类约束：user / feedback / project / reference，矛盾以时间近者优先）。
 * 合并产物由 {@link MemoryIndex} 分配稳定锚点并生成两级 MEMORY.md 索引。
 * 任何一级失败都不阻断主流程：降级为原文追加并打「未总结」标记。</p>
 */
@Slf4j
public class MarkdownMemoryWriter {

    private static final String CLASSIFY_RULE = """
            输出格式：每条记忆以 `- [type] 内容` 开头，type 仅限四类：
              - [user]：用户身份/偏好/习惯（始终私有）
              - [feedback]：用户对行为的纠正/要求（如"不要用某命令"）
              - [project]：项目状态/决策/选型/约定（当前项目相关）
              - [reference]：参考文档/外部资源/链接
            不要输出无关内容、客套话与重复条目；没有值得沉淀的内容时输出空行。""";

    private static final String SESSION_INSTRUCTION = """
            你是记忆管理 Agent，负责把会话聊天记录沉淀为精炼记忆。
            请把下面聊天记录总结为 Markdown 条目，只保留：用户偏好、项目决策、技术选型、关键结论、约定与约束。
            丢弃过程噪声、客套话与重复内容；不要编造记录里没有的内容。
            %s""".formatted(CLASSIFY_RULE);

    private static final String MERGE_PROJECT_INSTRUCTION = """
            你是记忆管理 Agent。下面是【旧项目记忆】与【新增内容】。
            请合并去重后输出更新后的项目记忆：保留重要历史事实，把新增内容合并进去，
            完全重复的条目去掉，同类条目合并，矛盾时以时间更新者为准；不要编造内容。
            %s""".formatted(CLASSIFY_RULE);

    private static final String MERGE_GLOBAL_INSTRUCTION = """
            你是记忆管理 Agent。下面是【旧整体记忆】与【新增项目记忆】。
            请合并去重后输出更新后的整体记忆（面向跨项目长期事实、用户偏好与约定）：
            保留重要历史，合并新增，去重同类，矛盾以时间近者为准；不要编造内容。
            %s""".formatted(CLASSIFY_RULE);

    private final Path root;
    private final MemoryMdProperties props;
    private final MemorySummaryModel summaryModel;
    private final WorkspaceMemoryPaths paths;
    private final MemoryIndex index;

    public MarkdownMemoryWriter(WorkspaceDirs dirs, MemoryMdProperties props, MemorySummaryModel summaryModel,
                                WorkspaceMemoryPaths paths, MemoryIndex index) {
        this.root = dirs.memoryDir().resolve(props.root());
        this.props = props;
        this.summaryModel = summaryModel;
        this.paths = paths;
        this.index = index;
    }

    /** 分层 md 记忆是否启用（配置开关）。 */
    public boolean enabled() {
        return props.enabled();
    }

    /** 分层记忆根目录。 */
    public Path root() {
        return root;
    }

    /**
     * 会话收尾更新分层记忆：会话总结 → 项目合并 → 整体合并。
     * 失败逐级降级（原文追加 + 未总结标记），绝不抛异常阻断主流程。
     */
    public synchronized void updateForSession(SessionRef ref, String transcript) {
        if (!props.enabled() || transcript == null || transcript.isBlank()) {
            return;
        }
        try {
            String sessionSummary = summaryModel.summarize(SESSION_INSTRUCTION, transcript);
            writeSession(ref, blankToPlaceholder(sessionSummary));
            String delta = "\n## 会话 " + ref.sessionId() + "\n" + blankToPlaceholder(sessionSummary);
            String projectMd = merge(MERGE_PROJECT_INSTRUCTION,
                    "# 项目记忆\n" + delta, readProject(ref.workspaceId()), delta);
            writeProject(ref.workspaceId(), projectMd);
            String globalMd = merge(MERGE_GLOBAL_INSTRUCTION,
                    "# 整体记忆\n" + projectMd, readGlobal(), projectMd);
            writeGlobal(globalMd);
            log.info("分层记忆已更新：workspace={} session={}", ref.workspaceId(), ref.sessionId());
        } catch (Exception e) {
            log.warn("分层记忆总结失败（降级原文追加）：workspace={} session={} err={}",
                    ref.workspaceId(), ref.sessionId(), e.getMessage());
            appendRaw(ref, transcript);
        }
    }

    /** 旧内容为空则直接以增量起头；否则交给 LLM 增量合并（失败回退拼接）。 */
    private String merge(String instruction, String fresh, String old, String delta) {
        if (old == null || old.isBlank()) {
            return fresh.trim();
        }
        try {
            String merged = summaryModel.summarize(instruction,
                    "【旧记忆】\n" + old.trim() + "\n\n【新增内容】\n" + delta.trim());
            return blankToPlaceholder(merged);
        } catch (Exception e) {
            log.warn("记忆合并失败（回退拼接）：{}", e.getMessage());
            return (old.trim() + "\n" + delta.trim());
        }
    }

    /**
     * Dream 合成入口：把一段时间内新增的会话总结合并进<b>指定工作空间目录</b>的项目记忆，
     * 并上卷合并到用户级整体记忆（对标 Claude Auto Dream：定向→收集→合并→修剪）。
     * <p>与 {@link #updateForSession} 共享同一把锁（synchronized），互斥写入 memory.md；
     * 幂等：无新增内容时直接返回。失败不抛异常（记日志，保留旧文件）。</p>
     *
     * @param workspaceDir 工作空间记忆目录（{@code root/<全路径转义>}）
     * @param newMaterial  该时段新增的会话总结（可含多个会话）
     * @return 合并后的项目记忆文本
     */
    public synchronized String consolidateDir(Path workspaceDir, String newMaterial) {
        if (workspaceDir == null || newMaterial == null || newMaterial.isBlank()) {
            return readFile(workspaceDir == null ? null : workspaceDir.resolve("memory.md"));
        }
        try {
            Path projectFile = workspaceDir.resolve("memory.md");
            String oldProject = readFile(projectFile);
            String delta = "\n## Dream 合成\n" + blankToPlaceholder(newMaterial);
            String projectMd = merge(MERGE_PROJECT_INSTRUCTION, "# 项目记忆\n" + delta, oldProject, delta);
            writeIndexed(workspaceDir, projectFile, projectMd);
            String globalMd = merge(MERGE_GLOBAL_INSTRUCTION, "# 整体记忆\n" + projectMd, readGlobal(), projectMd);
            writeIndexed(root, globalFile(), globalMd);
            log.info("Dream 合成完成：workspaceDir={}", workspaceDir);
            return projectMd;
        } catch (Exception e) {
            log.warn("Dream 合成失败（保留旧记忆）：workspaceDir={} err={}", workspaceDir, e.getMessage());
            return readFile(workspaceDir.resolve("memory.md"));
        }
    }

    private static String blankToPlaceholder(String text) {
        return (text == null || text.isBlank()) ? "（暂无值得沉淀的内容）" : text.trim();
    }

    /** LLM 不可用/失败时的兜底：原文追加到会话 md，保留可追溯性。 */
    private void appendRaw(SessionRef ref, String transcript) {
        try {
            Path dir = sessionDir(ref.workspaceId());
            Files.createDirectories(dir);
            String mark = "\n<!-- 未能 LLM 总结，保留原始记录 -->\n" + transcript.trim();
            Files.writeString(dir.resolve(sanitize(ref.sessionId()) + ".md"), mark,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e2) {
            log.warn("降级写入会话记录失败：{}", ref.sessionId(), e2);
        }
    }

    // ---------- 读取（召回层复用） ----------

    public String readGlobal() {
        return readFile(globalFile());
    }

    public String readProject(String workspaceId) {
        return readFile(projectFile(workspaceId));
    }

    public String readSession(String workspaceId, String sessionId) {
        return readFile(sessionFile(workspaceId, sessionId));
    }

    /** 用户级索引文件。 */
    public Path globalIndexFile() {
        return root.resolve(MemoryIndex.INDEX_FILE);
    }

    /** 本地级（工作空间）索引文件。 */
    public Path projectIndexFile(String workspaceId) {
        return projectDir(workspaceId).resolve(MemoryIndex.INDEX_FILE);
    }

    // ---------- 写 ----------

    private void writeSession(SessionRef ref, String content) {
        writeFile(sessionFile(ref.workspaceId(), ref.sessionId()),
                "# 会话总结 - " + ref.sessionId() + "\n\n" + content + "\n");
    }

    /** 项目记忆（未命中分类条目的旧内容原样保留；命中部分分配锚点并更新索引）。 */
    private void writeProject(String workspaceId, String content) {
        writeIndexed(projectDir(workspaceId), projectFile(workspaceId), content);
    }

    /** 整体记忆（用户级）。 */
    private void writeGlobal(String content) {
        writeIndexed(root, globalFile(), content);
    }

    /** 写「带锚点条目」并重建索引，保证 memory.md 与 MEMORY.md 由同一产物派生、强一致。 */
    private void writeIndexed(Path dir, Path file, String content) {
        MemoryIndex.IndexedContent indexed = index.index(content);
        writeFile(file, indexed.contentWithAnchors() + "\n");
        index.writeIndex(dir, indexed.indexLines());
    }

    // ---------- 记忆条目按需读取（预取/工具） ----------

    /**
     * 读取两级记忆中指定锚点的条目全文（用户级 + 本地级都查）。
     *
     * @param workspaceId 当前工作空间
     * @param ids         锚点 id 集合
     * @return 命中条目文本拼接（按 id 顺序去重）
     */
    public String readEntries(String workspaceId, java.util.Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendEntries(sb, index.readEntries(globalFile(), ids));
        appendEntries(sb, index.readEntries(projectFile(workspaceId), ids));
        return sb.toString();
    }

    private void appendEntries(StringBuilder sb, java.util.Map<String, String> entries) {
        entries.forEach((id, text) -> {
            if (text == null || text.isBlank()) {
                return;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text);
        });
    }

    // ---------- 路径 ----------

    private Path globalFile() {
        return root.resolve("memory.md");
    }

    private Path projectDir(String workspaceId) {
        return paths.dirOf(root, workspaceId);
    }

    private Path projectFile(String workspaceId) {
        return paths.dirOf(root, workspaceId).resolve("memory.md");
    }

    private Path sessionDir(String workspaceId) {
        return projectDir(workspaceId).resolve("sessions");
    }

    private Path sessionFile(String workspaceId, String sessionId) {
        return sessionDir(workspaceId).resolve(sanitize(sessionId) + ".md");
    }

    private String sanitize(String s) {
        return s == null || s.isBlank() ? "default" : s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private String readFile(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            log.warn("读取记忆文件失败：{}", file, e);
            return "";
        }
    }

    private void writeFile(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写入记忆文件失败：{}", file, e);
        }
    }
}
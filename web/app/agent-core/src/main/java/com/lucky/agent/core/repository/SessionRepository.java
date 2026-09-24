package com.lucky.agent.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.SessionSnapshot;
import com.lucky.agent.common.util.JsonlUtil;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 会话持久化仓库（R7：进程退�?重启自动恢复）�? *
 * <p>落盘 {@code <frameworkRoot>/.agent/sessions/}（框架隐藏目录，只在本机）：
 * 元数�?{@code <sessionId>.meta.json} + 消息 {@code <sessionId>.jsonl}�?/p>
 */

@Slf4j
@Service
public class SessionRepository {

    
    private final Path sessionsDir;
    private final ObjectMapper objectMapper;

    public SessionRepository(WorkspaceDirs dirs, ObjectMapper objectMapper) {
        this.sessionsDir = dirs.agentDir().resolve("sessions");
        this.objectMapper = objectMapper;
    }

    /** 会话元数据�?*/
    public record SessionMeta(String sessionId, String userId, String workspaceId,
                              String title, long createdAt, long updatedAt) {
    }

    /** 保存会话元数据（新建/更新标题/更新时间）�?*/
    public synchronized void upsertMeta(SessionRef ref, String title) {
        try {
            Files.createDirectories(sessionsDir);
            Path file = metaFile(ref.sessionId());
            long now = System.currentTimeMillis();
            SessionMeta existing = loadMeta(ref.sessionId());
            String effectiveTitle = title != null && !title.isBlank() ? title
                    : (existing != null && existing.title() != null ? existing.title() : "对话");
            SessionMeta meta = new SessionMeta(ref.sessionId(), ref.userId(), ref.workspaceId(),
                    effectiveTitle, existing == null ? now : existing.createdAt(), now);
            Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(meta), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("保存会话元数据失败：{}", ref.sessionId(), e);
        }
    }

    /** 追加一条消息到会话（checkpointIds 为该消息执行期间产生的检查点，可空；不落思考链）。 */
    public synchronized void appendMessage(String sessionId, String role, String content, String ts,
                                           List<String> checkpointIds) {
        appendMessage(sessionId, role, content, ts, checkpointIds, null);
    }

    /**
     * 追加一条消息到会话，并可选落盘思考链（assistant 专用）。
     *
     * <p><b>为什么必须存 thinking</b>：DeepSeek 等推理模型的思考模式规定——请求<b>携带 tools</b> 时，
     * 历史所有轮的 {@code reasoning_content} 必须原样回传，缺失即被拒（HTTP 400
     * {@code The `reasoning_content` in the thinking mode must be passed back to the API.}）。
     * 若磁盘只留正文，跨进程回放必然重建出没有思考链的 assistant 消息，带工具重放即触发该错误。</p>
     *
     * @param checkpointIds 该消息执行期间产生的检查点 id（可空）
     * @param thinking      思考链（reasoning_content）；空或空白则不写入该字段，
     *                      普通模型返回 null，因此对非推理模型零影响、零额外落盘
     */
    public synchronized void appendMessage(String sessionId, String role, String content, String ts,
                                           List<String> checkpointIds, String thinking) {
        try {
            Files.createDirectories(sessionsDir);
            Map<String, Object> record = new java.util.LinkedHashMap<>();
            record.put("role", role);
            record.put("content", content == null ? "" : content);
            record.put("ts", ts);
            if (thinking != null && !thinking.isBlank()) {
                record.put("thinking", thinking);
            }
            if (checkpointIds != null && !checkpointIds.isEmpty()) {
                record.put("checkpointIds", checkpointIds);
            }
            JsonlUtil.append(messageFile(sessionId), record);
        } catch (Exception e) {
            log.warn("追加会话消息失败：{}", sessionId, e);
        }
    }

    /** 兼容旧调用（无检查点、无思考链）。 */
    public void appendMessage(String sessionId, String role, String content, String ts) {
        appendMessage(sessionId, role, content, ts, null, null);
    }

    /** 加载会话历史。 */
    public List<SessionSnapshot.MessageRecord> loadMessages(String sessionId) {
        Path file = messageFile(sessionId);
        if (!Files.exists(file)) {
            return List.of();
        }
        return JsonlUtil.readAll(file, line -> {
            Map<String, Object> m = JsonlUtil.parseLine(line, Map.class);
            Object ck = m.get("checkpointIds");
            List<String> checkpointIds = ck instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList()
                    : null;
            return new SessionSnapshot.MessageRecord(
                    String.valueOf(m.getOrDefault("role", "unknown")),
                    String.valueOf(m.getOrDefault("content", "")),
                    String.valueOf(m.getOrDefault("ts", "")),
                    checkpointIds);
        });
    }

    /**
     * 回放用消息（含思考链）。
     *
     * <p>仅用于内部重建内存上下文，<b>刻意不进通道 DTO</b>（{@link SessionSnapshot.MessageRecord}）：
     * 思考链属于内部推理产物，不应经快照接口外泄给前端。</p>
     *
     * @param role     角色：user / assistant
     * @param content  正文
     * @param thinking 思考链（reasoning_content），无则 null
     */
    public record ReplayMessage(String role, String content, String thinking) {
    }

    /**
     * 加载用于内存上下文重建的消息（含 assistant 思考链）。
     *
     * <p>与 {@link #loadMessages(String)} 同源同序，仅多带 {@code thinking} 字段，
     * 供 {@code ConversationStateManager} 回放出带思考链的 assistant 消息；
     * 通道侧的历史展示仍走 {@link #loadMessages(String)}，两者互不影响。</p>
     */
    public List<ReplayMessage> loadForReplay(String sessionId) {
        Path file = messageFile(sessionId);
        if (!Files.exists(file)) {
            return List.of();
        }
        return JsonlUtil.readAll(file, line -> {
            Map<String, Object> m = JsonlUtil.parseLine(line, Map.class);
            Object thinking = m.get("thinking");
            return new ReplayMessage(
                    String.valueOf(m.getOrDefault("role", "unknown")),
                    String.valueOf(m.getOrDefault("content", "")),
                    thinking == null ? null : String.valueOf(thinking));
        });
    }

    /**
     * 移除某条消息已成功回溯的检查点引用（回退后不再重复展示撤销入口）。
     *
     * @param sessionId     会话 ID
     * @param ts            消息时间戳（消息定位键）
     * @param checkpointIds 已回溯的检查点 ID
     */
    public synchronized void removeCheckpoints(String sessionId, String ts, List<String> checkpointIds) {
        Path file = messageFile(sessionId);
        if (!Files.exists(file) || checkpointIds == null || checkpointIds.isEmpty()) {
            return;
        }
        java.util.Set<String> removed = new java.util.HashSet<>(checkpointIds);
        List<String> lines = JsonlUtil.readLines(file);
        List<String> rewritten = new ArrayList<>();
        boolean changed = false;
        for (String line : lines) {
            if (line.isBlank()) {
                rewritten.add(line);
                continue;
            }
            Map<String, Object> m = JsonlUtil.parseLine(line, Map.class);
            if (ts != null && ts.equals(String.valueOf(m.getOrDefault("ts", "")))) {
                Object ck = m.get("checkpointIds");
                if (ck instanceof List<?> l) {
                    List<String> kept = l.stream().map(String::valueOf)
                            .filter(id -> !removed.contains(id)).toList();
                    if (kept.size() != l.size()) {
                        if (kept.isEmpty()) {
                            m.remove("checkpointIds");
                        } else {
                            m.put("checkpointIds", kept);
                        }
                        try {
                            line = objectMapper.writeValueAsString(m);
                        } catch (IOException e) {
                            log.warn("序列化会话消息失败：{}", sessionId, e);
                        }
                        changed = true;
                    }
                }
            }
            rewritten.add(line);
        }
        if (changed) {
            JsonlUtil.rewrite(file, rewritten);
        }
    }

    /**
     * 回滚到消息节点：保留 ts 命中的消息及其之前全部记录，删除其后所有记录（持久化层）。
     * 用于「回滚到此处」——截断该消息之后的对话上下文。
     *
     * @param sessionId 会话 ID
     * @param ts        目标消息时间戳（消息定位键）
     * @return 删除的消息条数（0 表示未命中或无后续可删）
     */
    public synchronized int truncateAfter(String sessionId, String ts) {
        Path file = messageFile(sessionId);
        if (!Files.exists(file) || ts == null || ts.isBlank()) {
            return 0;
        }
        List<String> lines = JsonlUtil.readLines(file);
        int cut = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> m = JsonlUtil.parseLine(line, Map.class);
            if (ts.equals(String.valueOf(m.getOrDefault("ts", "")))) {
                cut = i;
                break;
            }
        }
        if (cut < 0 || cut + 1 >= lines.size()) {
            return 0;
        }
        List<String> kept = new ArrayList<>(lines.subList(0, cut + 1));
        JsonlUtil.rewrite(file, kept);
        return lines.size() - kept.size();
    }

    /** 加载会话元数据�?*/
    public SessionMeta loadMeta(String sessionId) {
        Path file = metaFile(sessionId);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), SessionMeta.class);
        } catch (IOException e) {
            log.warn("读取会话元数据失败：{}", sessionId, e);
            return null;
        }
    }

    /** 某用户全部会话（按更新时间倒序）�?*/
    public List<SessionMeta> listByUser(String userId) {
        if (!Files.isDirectory(sessionsDir)) {
            return List.of();
        }
        List<SessionMeta> result = new ArrayList<>();
        try (var stream = Files.list(sessionsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".meta.json")).forEach(p -> {
                SessionMeta meta = loadMeta(p.getFileName().toString().replace(".meta.json", ""));
                if (meta != null && (userId == null || userId.isBlank() || userId.equals(meta.userId()))) {
                    result.add(meta);
                }
            });
        } catch (IOException e) {
            log.warn("列出会话失败：{}", sessionsDir, e);
        }
        result.sort(Comparator.comparingLong(SessionMeta::updatedAt).reversed());
        return result;
    }

    /** 删除会话（元数据 + 消息文件）�?*/
    public boolean delete(String sessionId) {
        boolean removed = false;
        try {
            removed |= Files.deleteIfExists(metaFile(sessionId));
            removed |= Files.deleteIfExists(messageFile(sessionId));
        } catch (IOException e) {
            log.warn("删除会话文件失败：{}", sessionId, e);
        }
        return removed;
    }

    private Path metaFile(String sessionId) {
        return sessionsDir.resolve(sanitize(sessionId) + ".meta.json");
    }

    private Path messageFile(String sessionId) {
        return sessionsDir.resolve(sanitize(sessionId) + ".jsonl");
    }

    private String sanitize(String sessionId) {
        return sessionId == null ? "session" : sessionId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}

package com.lucky.agent.core.service;

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

    /** 追加一条消息到会话（checkpointIds 为该消息执行期间产生的检查点，可空）。 */
    public synchronized void appendMessage(String sessionId, String role, String content, String ts,
                                           List<String> checkpointIds) {
        try {
            Files.createDirectories(sessionsDir);
            Map<String, Object> record = new java.util.LinkedHashMap<>();
            record.put("role", role);
            record.put("content", content == null ? "" : content);
            record.put("ts", ts);
            if (checkpointIds != null && !checkpointIds.isEmpty()) {
                record.put("checkpointIds", checkpointIds);
            }
            JsonlUtil.append(messageFile(sessionId), record);
        } catch (Exception e) {
            log.warn("追加会话消息失败：{}", sessionId, e);
        }
    }

    /** 兼容旧调用（无检查点）。 */
    public void appendMessage(String sessionId, String role, String content, String ts) {
        appendMessage(sessionId, role, content, ts, null);
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

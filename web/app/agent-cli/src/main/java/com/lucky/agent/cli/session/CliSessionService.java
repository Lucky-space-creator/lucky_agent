package com.lucky.agent.cli.session;

import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.SessionSnapshot;
import com.lucky.agent.core.repository.SessionRepository;
import com.lucky.agent.core.service.ConversationManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CLI 会话服务：在既有 {@link SessionRepository}（JSONL append-only）之上做「查找 / 列举 / 删除」，
 * <b>不引入任何新的存储</b>。
 *
 * <p>职责边界：本类只做会话定位与读取；运行本身仍由内核负责
 * （{@code ConversationStateManager.session(ref)} 会在首次访问时从磁盘回灌上下文）。
 * 因此 {@code --resume} 不需要把历史重新灌给内核，只需要把历史<b>展示</b>给用户。</p>
 *
 * <p><b>回放展示契约</b>：历史回显一律走 {@link SessionRepository#loadMessages(String)}，
 * 其返回的 {@code MessageRecord} 刻意不含 {@code thinking}——推理产物不外泄是已锁定的通道隔离契约
 * （由 {@code SessionRepositoryReplayTest} 守住）。若需要推理链，只展示本轮新产生的 {@code thought} 事件。</p>
 */
@Component
public class CliSessionService {

    /** 列表时间显示格式（本地时区）。 */
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** 列表里展示的标题最大字符数（超出截断，避免长标题把 ID 挤出屏幕）。 */
    private static final int TITLE_MAX_CHARS = 32;

    /** 列表里展示的会话 ID 前缀长度（够区分即可，完整 ID 用 -r 传）。 */
    private static final int ID_PREFIX_LEN = 8;

    /** 无标题占位文案。 */
    private static final String NO_TITLE = "(无标题)";

    private final SessionRepository repository;
    private final ConversationManager conversationManager;

    public CliSessionService(SessionRepository repository, ConversationManager conversationManager) {
        this.repository = repository;
        this.conversationManager = conversationManager;
    }

    /** 新建会话 ID。 */
    public String newSessionId() {
        return UUID.randomUUID().toString();
    }

    /** 某用户的会话列表（按更新时间倒序）。 */
    public List<SessionRepository.SessionMeta> list(String userId) {
        return repository.listByUser(userId);
    }

    /** 最近一次会话（{@code --continue} 语义）。 */
    public Optional<SessionRepository.SessionMeta> latest(String userId) {
        List<SessionRepository.SessionMeta> all = list(userId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * 解析 {@code --resume} 的选择器。
     *
     * <p>匹配顺序：纯数字按列表序号（从 1 开始）→ 会话 ID 精确匹配 → 会话 ID 前缀匹配。
     * 前缀匹配命中多条时返回 {@link Optional#empty()} 并由调用方报「选择器有歧义」，
     * 避免静默挑一个让用户以为恢复到了别的会话。</p>
     *
     * @param selector 用户输入（序号 / ID / ID 前缀）
     * @param userId   用户
     * @return 命中的会话元数据
     */
    public Optional<SessionRepository.SessionMeta> resolve(String selector, String userId) {
        if (selector == null || selector.isBlank()) {
            return Optional.empty();
        }
        String s = selector.trim();
        List<SessionRepository.SessionMeta> all = list(userId);
        if (s.chars().allMatch(Character::isDigit)) {
            int idx = Integer.parseInt(s);
            return (idx >= 1 && idx <= all.size()) ? Optional.of(all.get(idx - 1)) : Optional.empty();
        }
        Optional<SessionRepository.SessionMeta> exact = all.stream()
                .filter(m -> s.equals(m.sessionId())).findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        List<SessionRepository.SessionMeta> prefix = all.stream()
                .filter(m -> m.sessionId() != null && m.sessionId().startsWith(s)).toList();
        return prefix.size() == 1 ? Optional.of(prefix.get(0)) : Optional.empty();
    }

    /** 判定选择器是否命中多条（用于给出「有歧义」的准确提示）。 */
    public boolean isAmbiguous(String selector, String userId) {
        if (selector == null || selector.isBlank() || selector.trim().chars().allMatch(Character::isDigit)) {
            return false;
        }
        String s = selector.trim();
        List<SessionRepository.SessionMeta> all = list(userId);
        if (all.stream().anyMatch(m -> s.equals(m.sessionId()))) {
            return false;
        }
        return all.stream().filter(m -> m.sessionId() != null && m.sessionId().startsWith(s)).count() > 1;
    }

    /** 会话历史（按落盘顺序；不含思考链）。 */
    public List<SessionSnapshot.MessageRecord> history(String sessionId) {
        return repository.loadMessages(sessionId);
    }

    /** 删除会话（元数据 + 消息 + 内存态 + 事件流）。 */
    public boolean delete(SessionRef ref) {
        return conversationManager.destroy(ref);
    }

    /** 把会话元数据格式化为列表行（序号 / 时间 / 标题 / ID 前缀）。 */
    public String format(SessionRepository.SessionMeta meta, int index) {
        String title = oneLineTitle(meta.title());
        String time = meta.updatedAt() > 0
                ? TIME_FMT.format(Instant.ofEpochMilli(meta.updatedAt())) : "-";
        String id = meta.sessionId() == null ? "-"
                : meta.sessionId().substring(0, Math.min(ID_PREFIX_LEN, meta.sessionId().length()));
        return String.format("%2d. %s  %-34s  %s", index, time, title, id);
    }

    /**
     * 标题压成单行再截断。
     *
     * <p><b>为什么必须压</b>：标题来自用户首句或模型生成，可能自带换行（实测存在
     * 「请按顺序完成以下操作…：\n1.…」这类多行标题）。原样输出会把一行会话撑成多行，
     * 「一行一会话、序号与 ID 对齐」的排版直接崩掉，序号还会看起来错位。</p>
     */
    private static String oneLineTitle(String raw) {
        if (raw == null || raw.isBlank()) {
            return NO_TITLE;
        }
        String flat = raw.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return NO_TITLE;
        }
        return flat.length() > TITLE_MAX_CHARS ? flat.substring(0, TITLE_MAX_CHARS) + "…" : flat;
    }
}

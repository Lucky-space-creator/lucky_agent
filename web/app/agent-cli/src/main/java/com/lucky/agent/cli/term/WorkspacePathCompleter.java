package com.lucky.agent.cli.term;

import com.lucky.agent.cli.session.SessionHolder;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.executor.api.FileService;
import org.jline.reader.Candidate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code @} 文件 / 目录路径补全。
 *
 * <p><b>为什么补全要经执行臂</b>：列目录本身是文件操作。若在补全回调里直接 {@code Files.list}，
 * 就绕过了执行臂硬边界（决策 D2）—— CLI 看到的目录树会与 Agent 实际能操作的范围脱节。
 * 故这里一律走 {@link FileService#list}，与 {@code @} 展开、{@code !} 执行同一套边界。</p>
 *
 * <p><b>为什么必须异步</b>：{@code FileService.list} 返回 {@link Mono}；而 JLine 的
 * {@code Completer.complete} 是<b>同步回调</b>。在其中 {@code block()} 会让「首次触发 + 大目录 +
 * 执行臂往返」直接卡住输入。因此：回调内只读本地缓存，未命中时<b>触发一次异步预取</b>并立即返回；
 * 缓存填充后，用户的下一次补全（继续打字 / 再按 Tab）即命中。</p>
 *
 * <p><b>预热</b>：构造时预取工作空间根目录，使「输入 {@code @} 就出候选」这一最常见场景多数情况下
 * 首次即可命中缓存，而不是先空一次。</p>
 *
 * <p><b>安全边界</b>：候选来自执行臂列目录的结果，天然限定在当前工作空间之内；只取名字与目录标志，
 * <b>不读文件内容</b>。</p>
 */
public final class WorkspacePathCompleter {

    /** 单次候选上限：目录项极多时截断，避免菜单刷屏与候选列表膨胀。 */
    static final int MAX_CANDIDATES = 200;

    private final FileService files;
    private final SessionHolder holder;
    private final Scheduler scheduler;

    /** 目录缓存：key = {@code workspaceId + '\0' + 相对目录}，value = 该目录下的条目。 */
    private final Map<String, List<ExecResult.FileEntry>> cache = new ConcurrentHashMap<>();
    /** 正在预取的 key，避免同一目录被重复请求打爆执行臂。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public WorkspacePathCompleter(FileService files, SessionHolder holder) {
        this(files, holder, Schedulers.boundedElastic());
    }

    /** 可注入调度器的构造（测试用 {@code Schedulers.immediate()} 使其确定性同步）。 */
    WorkspacePathCompleter(FileService files, SessionHolder holder, Scheduler scheduler) {
        this.files = files;
        this.holder = holder;
        this.scheduler = scheduler;
        prefetchRoot();
    }

    /**
     * 补全一个以 {@code @} 开头的词。
     *
     * @param word       当前词，形如 {@code @}、{@code @src}、{@code @src/ma}
     * @param candidates JLine 候选收集器（本方法只追加）
     */
    public void complete(String word, List<Candidate> candidates) {
        if (files == null || holder == null || word == null || word.isEmpty()
                || word.charAt(0) != com.lucky.agent.cli.input.FileReference.PREFIX) {
            return;
        }
        String body = word.substring(1);
        int slash = body.lastIndexOf('/');
        String dirPart = slash < 0 ? "" : body.substring(0, slash);
        String namePrefix = (slash < 0 ? body : body.substring(slash + 1)).toLowerCase(Locale.ROOT);
        String workspaceId = holder.workspaceId();

        List<ExecResult.FileEntry> entries = cache.get(cacheKey(workspaceId, dirPart));
        if (entries == null) {
            prefetch(workspaceId, dirPart);
            return; // 未命中：本轮不阻塞，触发异步预取后即刻返回
        }
        candidates.addAll(candidatesFor(entries, namePrefix));
    }

    /**
     * 由目录条目构造候选（纯函数，便于单测）。
     *
     * <p>目录：值带尾部 {@code /} 且 {@code complete=false}（不追加空格），方便继续往里补；
     * 文件：{@code complete=true}（选中即为完整 token，允许其后跟说明文字）。</p>
     */
    static List<Candidate> candidatesFor(List<ExecResult.FileEntry> entries, String namePrefix) {
        List<Candidate> result = new java.util.ArrayList<>();
        if (entries == null) {
            return result;
        }
        for (ExecResult.FileEntry e : entries) {
            if (result.size() >= MAX_CANDIDATES) {
                break;
            }
            String name = e.name() == null ? "" : e.name();
            if (!name.toLowerCase(Locale.ROOT).startsWith(namePrefix)) {
                continue;
            }
            String path = e.path() == null ? name : e.path();
            if (e.dir()) {
                result.add(new Candidate("@" + path + "/", "@" + path + "/", "目录", "目录", null, null, false));
            } else {
                result.add(new Candidate("@" + path, "@" + path, "文件", humanSize(e.size()), null, null, true));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 内部

    /** 预热根目录：让最常见的「输入 @ 即出候选」尽量首次命中。 */
    private void prefetchRoot() {
        if (files != null && holder != null) {
            prefetch(holder.workspaceId(), "");
        }
    }

    private void prefetch(String workspaceId, String dirPart) {
        if (files == null || workspaceId == null) {
            return;
        }
        String key = cacheKey(workspaceId, dirPart);
        if (!inFlight.add(key)) {
            return; // 已在加载
        }
        try {
            Mono<ExecResult> listing = files.list(workspaceId, dirPart);
            (listing == null ? Mono.<ExecResult>empty() : listing)
                    .subscribeOn(scheduler)
                    .subscribe(
                            r -> {
                                inFlight.remove(key);
                                if (r != null && r.ok() && r.entries() != null) {
                                    cache.put(key, r.entries());
                                }
                            },
                            err -> inFlight.remove(key));
        } catch (Exception e) {
            inFlight.remove(key);
        }
    }

    private static String cacheKey(String workspaceId, String dirPart) {
        return workspaceId + '\0' + dirPart;
    }

    /** 人类可读体积（补全菜单右侧展示，仅信息量，不求精确）。 */
    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}

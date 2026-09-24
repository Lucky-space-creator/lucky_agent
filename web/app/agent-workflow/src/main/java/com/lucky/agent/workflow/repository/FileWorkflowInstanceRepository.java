package com.lucky.agent.workflow.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.workflow.domain.WorkflowInstance;
import com.lucky.agent.workflow.exception.WorkflowException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 文件实现：将每个运行实例序列化为 {@code <dir>/<instanceId>.json}。
 *
 * <p><b>为何需要它：</b>内存实现下运行记录随进程消失，前端「最近运行」重启即空，
 * 且无法回溯历史执行。本实现与 {@link FileWorkflowRepository} 配套，全部落在用户本机
 * 工作空间 {@code <root>/workflow/instances}，不引入服务端存储。</p>
 *
 * <p><b>保留策略：</b>实例是持续增长的运行历史，故设容量上限（默认 200）。
 * 超出后按文件最后修改时间淘汰最旧者 —— 用 mtime 而非解析 JSON 取 {@code startedAt}，
 * 因为实例文件在终态写入后即不再变更，mtime 与「完成时间」等价，而淘汰路径无需读盘内容。</p>
 *
 * <p><b>为何写入是「临时文件 + 原子移动」：</b>引擎在 ASYNC 运行期间会按节点持续落盘进度，
 * 而 HTTP 线程会同时读同一文件。裸 {@code writeValue(File)} 会先截断目标文件再写入，
 * 读取方可能正好读到「已截断但未写完」的半截 JSON —— 表现为 {@link #findById} 返回
 * {@code Optional.empty()}，在接口层就是运行中途突然 404。故写入一律落同目录临时文件后
 * {@code ATOMIC_MOVE} 覆盖：读取方只会看到「旧完整版」或「新完整版」。
 * 临时文件后缀为 {@code .tmp}（不以 {@code .json} 结尾），故不会被列表/淘汰逻辑误收。</p>
 *
 * <p><b>为何淘汰是摊销的：</b>进度落盘使同一实例被反复写入，若每次都做一次目录列举 + 排序，
 * 就把 O(节点数) 次写入放大成 O(节点数 × 目录规模) 次 IO。故仅在「创建了新实例文件」或
 * 每 {@value #PRUNE_INTERVAL_SAVES} 次写入时才执行淘汰 —— 后者用于兜底「上限被调小、
 * 存量已超标而恰好没有新实例产生」的场景。</p>
 *
 * <p><b>与定义仓储的差异（有意为之）：</b>定义文件损坏应显式报错（用户会立刻感知结果缺失）；
 * 而历史实例属于旁路记录，单个文件损坏不应导致整个列表接口 500 —— 故本实现跳过坏文件并告警。</p>
 */
@Slf4j
public class FileWorkflowInstanceRepository implements WorkflowInstanceRepository {

    /** 默认保留的最大实例数。 */
    public static final int DEFAULT_MAX_RETAINED = 200;

    /** 兜底淘汰间隔：无新实例产生时，每写入 N 次仍校验一次容量。 */
    static final int PRUNE_INTERVAL_SAVES = 64;

    private final Path dir;
    private final ObjectMapper objectMapper;
    private final int maxRetained;

    /**
     * 写入串行锁：进度落盘可能来自引擎线程与终态收尾等不同路径，
     * 串行化可保证同一实例的「临时文件 → 原子移动」不交错。
     * 读取路径刻意不加锁 —— 原子移动已保证读取方永远看到完整内容，无需为读引入竞争。
     */
    private final ReentrantLock writeLock = new ReentrantLock();

    private final AtomicLong saveCount = new AtomicLong();

    public FileWorkflowInstanceRepository(Path dir, ObjectMapper objectMapper) {
        this(dir, objectMapper, DEFAULT_MAX_RETAINED);
    }

    public FileWorkflowInstanceRepository(Path dir, ObjectMapper objectMapper, int maxRetained) {
        this.dir = dir;
        this.objectMapper = objectMapper;
        this.maxRetained = maxRetained > 0 ? maxRetained : DEFAULT_MAX_RETAINED;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建工作流实例存储目录: " + dir, e);
        }
    }

    @Override
    public void save(WorkflowInstance instance) {
        Path file = fileOf(instance.getInstanceId());
        writeLock.lock();
        try {
            boolean newInstanceFile = !Files.exists(file);
            writeAtomically(file, instance);
            if (newInstanceFile || saveCount.incrementAndGet() % PRUNE_INTERVAL_SAVES == 0) {
                pruneIfExceeded();
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 先写同目录临时文件，再原子移动覆盖目标 —— 使并发读取方永远读到完整 JSON。
     *
     * <p>临时文件必须与目标同目录：{@code ATOMIC_MOVE} 要求同文件系统。个别文件系统不支持原子移动时
     * 降级为普通替换移动（此时仍有「截断窗口」，但至少内容不会与目标文件交错写入）。</p>
     */
    private void writeAtomically(Path target, WorkflowInstance instance) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile(dir, target.getFileName().toString() + ".", ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(out, instance);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } catch (IOException e) {
            throw new WorkflowException("工作流实例写入失败: " + instance.getInstanceId(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    log.warn("清理工作流实例临时文件失败: {} - {}", tmp.getFileName(), e.getMessage());
                }
            }
        }
    }

    @Override
    public Optional<WorkflowInstance> findById(String instanceId) {
        Path file = fileOf(instanceId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), WorkflowInstance.class));
        } catch (IOException e) {
            log.warn("工作流实例读取失败，已忽略: {} - {}", instanceId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<WorkflowInstance> findAll() {
        List<WorkflowInstance> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            result.add(objectMapper.readValue(p.toFile(), WorkflowInstance.class));
                        } catch (IOException e) {
                            log.warn("工作流实例读取失败，已跳过: {} - {}", p.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            throw new WorkflowException("工作流实例目录扫描失败: " + dir, e);
        }
        result.sort(Comparator.comparingLong(WorkflowInstance::getStartedAt).reversed());
        return result;
    }

    /**
     * 超出上限时按 mtime 淘汰最旧实例文件（幂等；单次淘汰到上限内）。
     *
     * <p>调用频率由 {@link #save} 摊销控制，不在此处判断「是否需要淘汰」的时机 ——
     * 本方法只回答「当前是否超标」，时机决策留在写入路径，避免调用方漏判。</p>
     */
    private void pruneIfExceeded() {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> json = files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(this::lastModified).reversed())
                    .toList();
            if (json.size() <= maxRetained) {
                return;
            }
            for (Path old : json.subList(maxRetained, json.size())) {
                try {
                    Files.deleteIfExists(old);
                } catch (IOException e) {
                    log.warn("淘汰历史工作流实例失败: {} - {}", old.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("工作流实例目录清理失败: {} - {}", dir, e.getMessage());
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private Path fileOf(String instanceId) {
        return dir.resolve(instanceId + ".json");
    }
}

package com.lucky.agent.workflow.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.workflow.domain.WorkflowInstance;
import com.lucky.agent.workflow.exception.WorkflowException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
 * <p><b>与定义仓储的差异（有意为之）：</b>定义文件损坏应显式报错（用户会立刻感知结果缺失）；
 * 而历史实例属于旁路记录，单个文件损坏不应导致整个列表接口 500 —— 故本实现跳过坏文件并告警。</p>
 */
@Slf4j
public class FileWorkflowInstanceRepository implements WorkflowInstanceRepository {

    /** 默认保留的最大实例数。 */
    public static final int DEFAULT_MAX_RETAINED = 200;

    private final Path dir;
    private final ObjectMapper objectMapper;
    private final int maxRetained;

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
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), instance);
        } catch (IOException e) {
            throw new WorkflowException("工作流实例写入失败: " + instance.getInstanceId(), e);
        }
        pruneIfExceeded();
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

    /** 超出上限时按 mtime 淘汰最旧实例文件（幂等；单次淘汰到上限内）。 */
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

package com.lucky.agent.workflow.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.exception.WorkflowException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 文件实现：将每个工作流定义序列化为 {@code <dir>/<id>.json}。
 * <p>默认落用户本机目录，符合「用户数据在本机、不引入服务端存储」的工程边界。</p>
 */
public class FileWorkflowRepository implements WorkflowRepository {

    private final Path dir;
    private final ObjectMapper objectMapper;

    public FileWorkflowRepository(Path dir, ObjectMapper objectMapper) {
        this.dir = dir;
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建工作流存储目录: " + dir, e);
        }
    }

    @Override
    public WorkflowDef save(WorkflowDef definition) {
        Path file = fileOf(definition.id());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            // 原子写：先写临时文件再 move。此前 writeValue(File) 直接 TRUNCATE 目标，
            // 中途崩溃/掉电会留下半截 JSON，findAll() 随即整体读取失败（台账 #3）。
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), definition);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return definition;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败不影响主错误上报
            }
            throw new WorkflowException("工作流定义写入失败: " + definition.id(), e);
        }
    }

    @Override
    public Optional<WorkflowDef> findById(String id) {
        Path file = fileOf(id);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), WorkflowDef.class));
        } catch (IOException e) {
            throw new WorkflowException("工作流定义读取失败: " + id, e);
        }
    }

    @Override
    public List<WorkflowDef> findAll() {
        List<WorkflowDef> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            result.add(objectMapper.readValue(p.toFile(), WorkflowDef.class));
                        } catch (IOException e) {
                            throw new WorkflowException("工作流定义读取失败: " + p, e);
                        }
                    });
        } catch (IOException e) {
            throw new WorkflowException("工作流目录扫描失败: " + dir, e);
        }
        return result;
    }

    @Override
    public boolean deleteById(String id) {
        try {
            return Files.deleteIfExists(fileOf(id));
        } catch (IOException e) {
            throw new WorkflowException("工作流定义删除失败: " + id, e);
        }
    }

    /**
     * 解析定义文件路径，并卡住「id 即路径」的逃逸面（台账 #3）。
     *
     * <p>此前直接 {@code dir.resolve(id + ".json")}，{@code id="../../Users/x/foo/config"}
     * 可写/删工作空间外任意 {@code *.json}。这里只做「不能跳出 dir」的判定：
     * 禁止路径分隔符、盘符、NUL/控制字符、以点开头与 {@code ..} 片段；
     * 中文、空格、字母数字等正常 id 一律放行。</p>
     */
    private Path fileOf(String id) {
        if (!isSafeId(id)) {
            throw new WorkflowException("工作流 ID 不合法（不得包含路径分隔符或 ..）：" + id);
        }
        return dir.resolve(id + ".json");
    }

    private static boolean isSafeId(String id) {
        if (id == null || id.isBlank() || id.length() > 128) {
            return false;
        }
        if (id.startsWith(".") || id.contains("..")) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '\0' || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }
}

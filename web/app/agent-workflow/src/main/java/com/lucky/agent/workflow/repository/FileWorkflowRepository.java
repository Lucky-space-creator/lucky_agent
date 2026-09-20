package com.lucky.agent.workflow.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.exception.WorkflowException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), definition);
            return definition;
        } catch (IOException e) {
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

    private Path fileOf(String id) {
        return dir.resolve(id + ".json");
    }
}

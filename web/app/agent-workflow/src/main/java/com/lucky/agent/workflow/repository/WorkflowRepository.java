package com.lucky.agent.workflow.repository;

import com.lucky.agent.workflow.domain.WorkflowDef;

import java.util.List;
import java.util.Optional;

/**
 * 工作流定义仓储（可替换实现：内存 / 文件 / 后续可接工作空间落盘）。
 * <p>遵守工程边界：定义属于用户产物，默认落用户本机工作空间，不引入服务端存储。</p>
 */
public interface WorkflowRepository {

    WorkflowDef save(WorkflowDef definition);

    Optional<WorkflowDef> findById(String id);

    List<WorkflowDef> findAll();

    boolean deleteById(String id);

    default boolean existsById(String id) {
        return findById(id).isPresent();
    }
}

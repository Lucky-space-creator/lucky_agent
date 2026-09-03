package com.lucky.agent.common.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * 工作空间标识值对象。
 *
 * <p>轻量命名空间：工作区即「一个工作区 / 一个需求会话」，资源按 workspaceId 在本机隔离
 * （产物目录、回退快照、锁文件均带 wid）。</p>
 */
public final class WorkspaceId {

    private final String value;

    private WorkspaceId(String value) {
        this.value = Objects.requireNonNull(value, "workspaceId 不能为空");
    }

    /** 生成新的工作空间 ID。 */
    public static WorkspaceId generate() {
        return new WorkspaceId(UUID.randomUUID().toString());
    }

    /** 由既有字符串构造。 */
    public static WorkspaceId of(String value) {
        return new WorkspaceId(value);
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WorkspaceId that)) {
            return false;
        }
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}

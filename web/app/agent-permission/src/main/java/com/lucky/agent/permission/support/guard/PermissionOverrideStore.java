package com.lucky.agent.permission.support.guard;

import com.lucky.agent.common.dto.FileOp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限一次性放行存储：用户确认高危操作后，短期内该操作不再触发 ASK。
 *
 * <p>key = {@code workspaceId|opType|path}，TTL 默认 120s；到期自动失效，避免永久放行。</p>
 */
public class PermissionOverrideStore {

    private static final long DEFAULT_TTL_MS = 120_000L;

    private final Map<String, Long> overrides = new ConcurrentHashMap<>();

    /** 记录一次性放行。 */
    public void allow(FileOp op) {
        overrides.put(key(op), System.currentTimeMillis() + DEFAULT_TTL_MS);
    }

    /** 校验是否存在有效放行。 */
    public boolean check(FileOp op) {
        String k = key(op);
        Long expireAt = overrides.get(k);
        if (expireAt == null) {
            return false;
        }
        if (System.currentTimeMillis() > expireAt) {
            overrides.remove(k);
            return false;
        }
        return true;
    }

    /** 清理过期放行。 */
    public void sweep() {
        long now = System.currentTimeMillis();
        overrides.entrySet().removeIf(e -> now > e.getValue());
    }

    private String key(FileOp op) {
        return op.workspaceId() + "|" + (op.opType() == null ? "?" : op.opType().name()) + "|" + (op.path() == null ? "" : op.path());
    }
}

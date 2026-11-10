package com.lucky.agent.common.concurrent;

import com.lucky.agent.common.exception.AgentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件锁互斥回归（台账 #6）。
 *
 * <p>修复前 owner 取 {@code System.getProperty("pid")}，而全仓没有任何地方设置 {@code -Dpid}
 * → 首行恒为 {@code unknown|ts|lease} → {@code isProcessAlive("unknown")} 恒 false
 * → 每个锁一创建就被判 stale → 第二个并发请求立即删锁抢占，互斥完全失效。</p>
 */
class FileLockGuardTest {

    @Test
    void lockOwnerMustBeRealPid(@TempDir Path dir) throws Exception {
        FileLockGuard guard = new FileLockGuard(dir);
        Path lock = guard.lock("wid", "a.txt", 1000);
        try {
            List<String> lines = Files.readAllLines(lock);
            assertTrue(!lines.isEmpty(), "锁文件应有首行");
            String owner = lines.get(0).split("\\|")[0];
            assertEquals(String.valueOf(ProcessHandle.current().pid()), owner,
                    "锁 owner 必须是真实 PID，否则会被判 stale");
        } finally {
            guard.unlock(lock);
        }
    }

    @Test
    void secondAcquirerMustWaitInsteadOfStealing(@TempDir Path dir) throws Exception {
        FileLockGuard guard = new FileLockGuard(dir);
        Path lock = guard.lock("wid", "b.txt", 1000);
        try {
            // 租约 30s 远未过期、持有者进程仍存活 → 第二次获取必须等满 waitMs 后超时，
            // 而不是像修复前那样立刻删锁抢占。
            long start = System.currentTimeMillis();
            assertThrows(AgentException.class, () -> guard.lock("wid", "b.txt", 300));
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed >= 250, "应真正等待过，实际耗时 " + elapsed + "ms");
        } finally {
            guard.unlock(lock);
        }
    }

    @Test
    void releasedLockCanBeAcquiredAgain(@TempDir Path dir) throws Exception {
        FileLockGuard guard = new FileLockGuard(dir);
        Path first = guard.lock("wid", "c.txt", 500);
        guard.unlock(first);
        Path second = guard.lock("wid", "c.txt", 500);
        assertTrue(Files.exists(second));
        guard.unlock(second);
    }
}

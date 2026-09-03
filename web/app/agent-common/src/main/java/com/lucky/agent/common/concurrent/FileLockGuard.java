package com.lucky.agent.common.concurrent;

import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.exception.AgentException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import lombok.extern.slf4j.Slf4j;

/**
 * 文件锁守卫（§3.0 锁契约，R10 防死锁）。
 *
 * <p>锁文件：{@code <frameworkRoot>/.tmp/<workspaceId>.<relPath>.lock}，首行 {@code PID|timestamp|leaseMs}。</p>
 * <ul>
 *   <li><b>获取</b>：建锁前原子写 {@code .lock.tmp} 再 {@code rename}；已存在则读首行，
 *       PID 不存在或超时则删后抢占；否则等待重试（带总上限防死等）。</li>
 *   <li><b>释放</b>：{@code try-finally} 删除；注册 {@code Runtime.addShutdownHook} 清理本进程锁。</li>
 *   <li><b>启动清理</b>：扫描 {@code .tmp/}，删 PID 已死/超时 stale 锁与残留 {@code .part}。</li>
 * </ul>
 */

@Slf4j
public class FileLockGuard {

    /** 默认租约超时（毫秒）。 */
    public static final long DEFAULT_LEASE_MS = 30_000L;

    /** 等待总上限（毫秒），防无限阻塞。 */
    public static final long DEFAULT_WAIT_TOTAL_MS = 5_000L;

    private final Path lockDir;
    private final long leaseMs;

    public FileLockGuard(Path lockDir) {
        this(lockDir, DEFAULT_LEASE_MS);
    }

    public FileLockGuard(Path lockDir, long leaseMs) {
        this.lockDir = lockDir;
        this.leaseMs = leaseMs;
    }

    /** 由 frameworkRoot 构造（锁目录固定为 {@code <frameworkRoot>/.tmp/}）。 */
    public static FileLockGuard of(WorkspaceDirs dirs) {
        return new FileLockGuard(dirs.tmpDir());
    }

    /**
     * 获取工作区内某相对路径的锁。
     *
     * @param workspaceId 工作空间 ID
     * @param relPath     相对路径（可含目录分隔符）
     * @param waitMs      等待总上限（毫秒）
     * @return 锁文件路径（由调用方持有并在 finally 中 {@link #unlock(Path)}）
     * @throws AgentException 超过等待上限仍未获取
     */
    public Path lock(String workspaceId, String relPath, long waitMs) {
        Path lockFile = lockFile(workspaceId, relPath);
        try {
            Files.createDirectories(lockDir);
            long deadline = System.currentTimeMillis() + Math.max(waitMs, 0);
            while (true) {
                if (tryLock(lockFile)) {
                    return lockFile;
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new AgentException("LOCK_TIMEOUT", "等待文件锁超时：" + lockFile);
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AgentException("LOCK_INTERRUPTED", "等待文件锁被打断：" + lockFile, e);
                }
            }
        } catch (IOException e) {
            throw new AgentException("LOCK_IO", "创建锁文件失败：" + lockFile, e);
        }
    }

    /**
     * 释放锁（删除锁文件）。
     *
     * @param lockFile {@link #lock(String, String, long)} 返回的锁文件路径
     */
    public void unlock(Path lockFile) {
        try {
            Files.deleteIfExists(lockFile);
        } catch (IOException e) {
            log.warn("删除锁文件失败：{}", lockFile, e);
        }
    }

    /** 启动清理：删除 PID 已死或超时的 stale 锁与残留 .part 中转文件。 */
    public void cleanupStaleLocks() {
        if (!Files.isDirectory(lockDir)) {
            return;
        }
        try (var stream = Files.list(lockDir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.endsWith(".lock")) {
                    if (isStale(p)) {
                        try {
                            Files.deleteIfExists(p);
                            log.info("清理 stale 锁：{}", p);
                        } catch (IOException e) {
                            log.warn("清理 stale 锁失败：{}", p, e);
                        }
                    }
                } else if (name.endsWith(".part")) {
                    try {
                        Files.deleteIfExists(p);
                        log.info("清理残留 .part 中转：{}", p);
                    } catch (IOException e) {
                        log.warn("清理 .part 失败：{}", p, e);
                    }
                }
            });
        } catch (IOException e) {
            log.warn("扫描锁目录失败：{}", lockDir, e);
        }
    }

    /** 锁文件路径：{@code <wid>.<relPath>.lock}，relPath 中分隔符替换为 {@code _}。 */
    public Path lockFile(String workspaceId, String relPath) {
        String safe = relPath.replace('/', '_').replace('\\', '_');
        return lockDir.resolve(workspaceId + "." + safe + ".lock");
    }

    private boolean tryLock(Path lockFile) throws IOException {
        if (!Files.exists(lockFile)) {
            Path tmp = lockDir.resolve(lockFile.getFileName() + ".tmp");
            String owner = System.getProperty("pid", "unknown") + "|" + System.currentTimeMillis() + "|" + leaseMs;
            Files.writeString(tmp, owner + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.move(tmp, lockFile, StandardCopyOption.ATOMIC_MOVE);
            return true;
        }
        if (isStale(lockFile)) {
            Files.deleteIfExists(lockFile);
            return tryLock(lockFile);
        }
        return false;
    }

    private boolean isStale(Path lockFile) {
        try {
            String firstLine = Files.readAllLines(lockFile, StandardCharsets.UTF_8).stream()
                    .findFirst().orElse("");
            String[] parts = firstLine.split("\\|");
            if (parts.length < 3) {
                return true;
            }
            String pid = parts[0];
            long ts = Long.parseLong(parts[1]);
            long lease = Long.parseLong(parts[2]);
            boolean pidDead = !isProcessAlive(pid);
            boolean expired = System.currentTimeMillis() - ts > lease;
            return pidDead || expired;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean isProcessAlive(String pid) {
        if (pid == null || pid.isBlank() || "unknown".equals(pid)) {
            return false;
        }
        try {
            return ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** 注册 JVM 退出钩子，兜底清理本进程持有的锁。 */
    public void registerShutdownCleanup() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                cleanupStaleLocks();
            } catch (Exception ignored) {
                // 退出清理失败不影响进程退出
            }
        }));
    }
}

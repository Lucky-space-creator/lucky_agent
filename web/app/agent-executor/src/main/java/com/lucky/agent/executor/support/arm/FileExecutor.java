package com.lucky.agent.executor.support.arm;

import com.lucky.agent.common.concurrent.FileLockGuard;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.common.exception.AgentException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * 文件执行器：所有文件操作执行，均限制在工作空间内。
 * <p>写操作前加文件锁（{@code .tmp/} 锁契约），操作结束释放（try-finally）；
 * 删除走软删除（移入 {@code .trash/} 回收站）。</p>
 */

@Slf4j
public class FileExecutor {

    
    private final FileLockGuard fileLockGuard;
    private final Path trashDir;

    public FileExecutor(FileLockGuard fileLockGuard, WorkspaceDirs dirs) {
        this.fileLockGuard = fileLockGuard;
        this.trashDir = dirs.trashDir();
    }

    public ExecResult execute(FileOp op, Path absPath) {
        try {
            return switch (op.opType()) {
                case READ -> read(op, absPath);
                case WRITE -> write(op, absPath);
                case DELETE -> delete(op, absPath);
                case LIST -> list(op, absPath);
                case MKDIR -> mkdir(op, absPath);
                case RENAME -> rename(op, absPath);
                case STAT -> stat(op, absPath);
                case EXEC -> ExecResult.failure(FileOp.OpType.EXEC, op.requestId(), "命令执行需经沙箱，未启用");
            };
        } catch (IOException e) {
            log.error("文件操作失败：op={} path={}", op.opType(), op.path(), e);
            String msg = isDiskFull(e)
                    ? "工作空间所在磁盘已满，无法执行文件操作"
                    : "文件操作失败：" + e.getMessage();
            return ExecResult.failure(op.opType(), op.requestId(), msg);
        } catch (AgentException e) {
            return ExecResult.failure(op.opType(), op.requestId(), e.getMessage());
        }
    }

    private ExecResult read(FileOp op, Path absPath) throws IOException {
        if (!Files.exists(absPath) || Files.isDirectory(absPath)) {
            return ExecResult.failure(FileOp.OpType.READ, op.requestId(), "文件不存在或为目录：" + op.path());
        }
        String content = Files.readString(absPath, StandardCharsets.UTF_8);
        long size = Files.size(absPath);
        return ExecResult.success(FileOp.OpType.READ, op.requestId())
                .path(op.path()).size(size).content(content)
                .summary("读取文件 " + op.path() + "：" + size + " 字节");
    }

    private ExecResult write(FileOp op, Path absPath) throws IOException {
        Files.createDirectories(absPath.getParent());
        if (op.args() != null && "base64".equals(String.valueOf(op.args().get("encoding")))) {
            byte[] bytes = op.content() == null ? new byte[0] : Base64.getDecoder().decode(op.content());
            Files.write(absPath, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } else {
            Files.writeString(absPath, op.content() == null ? "" : op.content(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        long size = Files.size(absPath);
        return ExecResult.success(FileOp.OpType.WRITE, op.requestId())
                .path(op.path()).size(size).summary("已写入：" + op.path() + "：" + size + " 字节");
    }

    private ExecResult delete(FileOp op, Path absPath) throws IOException {
        if (Files.isSameFile(absPath, absPath.getRoot())) {
            return ExecResult.failure(FileOp.OpType.DELETE, op.requestId(), "禁止删除根目录");
        }
        if (!Files.exists(absPath)) {
            return ExecResult.failure(FileOp.OpType.DELETE, op.requestId(), "目标不存在：" + op.path());
        }
        softDeleteToTrash(op, absPath);
        return ExecResult.success(FileOp.OpType.DELETE, op.requestId())
                .path(op.path()).summary("已删除：" + op.path() + "（可 30 天内于回收站恢复）");
    }

    /**
     * 移入回收站。
     *
     * <p>台账 #10：此前直接用 {@code op.path().replace(':','_')} 拼目标名，{@code a/../b}
     * 会经 {@code resolve} 逃出本次时间戳目录、覆盖其他回收站条目；同名文件在同一毫秒内
     * 还会互相覆盖。现在把分隔符全部扁平化、消掉 {@code ..} 语义，并追加原路径短 hash
     * 保证唯一。</p>
     */
    private void softDeleteToTrash(FileOp op, Path absPath) throws IOException {
        String timestamp = Long.toString(System.currentTimeMillis());
        Path targetTrash = trashDir.resolve(safeName(op.workspaceId()))
                .resolve(timestamp + "_" + trashEntryName(op, absPath));
        Files.createDirectories(targetTrash.getParent());
        Files.move(absPath, targetTrash, StandardCopyOption.REPLACE_EXISTING);
        log.info("软删除：{} -> {}", absPath, targetTrash);
    }

    /** 回收站条目名：扁平化分隔符、消除 {@code ..}、追加 hash 去重。 */
    private static String trashEntryName(FileOp op, Path absPath) {
        String raw = op.path() == null || op.path().isBlank() ? "root" : op.path();
        String cleaned = raw.replace('\\', '_').replace('/', '_').replace(':', '_')
                .replace("..", "__");
        if (cleaned.isBlank()) {
            cleaned = "root";
        }
        // 长度上限，避免超长路径撞上文件系统限制
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80);
        }
        return cleaned + "_" + Integer.toHexString(absPath.toString().hashCode());
    }

    /** 工作空间 id 也可能带分隔符（配置手填），落目录前统一净化。 */
    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        String cleaned = name.replace('\\', '_').replace('/', '_').replace(':', '_');
        return cleaned.equals(".") || cleaned.contains("..") ? cleaned.replace("..", "__") : cleaned;
    }

    private ExecResult list(FileOp op, Path absPath) throws IOException {
        if (!Files.isDirectory(absPath)) {
            return ExecResult.failure(FileOp.OpType.LIST, op.requestId(), "目录不存在：" + op.path());
        }
        List<ExecResult.FileEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(absPath)) {
            stream.sorted().forEach(p -> {
                Path name = p.getFileName();
                String rel = (op.path() == null || op.path().isEmpty())
                        ? name.toString() : op.path() + "/" + name;
                try {
                    entries.add(new ExecResult.FileEntry(name.toString(), rel, Files.isDirectory(p), Files.size(p)));
                } catch (IOException e) {
                    entries.add(new ExecResult.FileEntry(name.toString(), rel, Files.isDirectory(p), 0));
                }
            });
        }
        return ExecResult.success(FileOp.OpType.LIST, op.requestId())
                .path(op.path()).entries(entries).summary("目录 " + op.path() + " 下共 " + entries.size() + " 项");
    }

    private ExecResult mkdir(FileOp op, Path absPath) throws IOException {
        Files.createDirectories(absPath);
        return ExecResult.success(FileOp.OpType.MKDIR, op.requestId())
                .path(op.path()).summary("已创建目录：" + op.path());
    }

    private ExecResult rename(FileOp op, Path absPath) throws IOException {
        if (op.args() == null || !(op.args().get("to") instanceof String to) || to.isBlank()) {
            return ExecResult.failure(FileOp.OpType.RENAME, op.requestId(), "缺少目标路径参数 to");
        }
        // 兜底：目标必须是「相对源目录」的路径。绝对路径在 Windows 上会被 Path.resolve
        // 直接采纳，从而完全绕过工作空间边界（台账 #1）。主校验在 LocalTransport 的
        // guardRenameTarget（基于工作空间根 + realpath），这里防的是有人直接调用本类。
        Path rawTarget;
        try {
            rawTarget = Path.of(to);
        } catch (RuntimeException e) {
            return ExecResult.failure(FileOp.OpType.RENAME, op.requestId(), "目标路径不合法：" + to);
        }
        if (rawTarget.isAbsolute() || rawTarget.getRoot() != null) {
            return ExecResult.failure(FileOp.OpType.RENAME, op.requestId(),
                    "目标路径必须相对源目录，禁止使用绝对路径：" + to);
        }
        if (!Files.exists(absPath)) {
            return ExecResult.failure(FileOp.OpType.RENAME, op.requestId(), "源不存在：" + op.path());
        }
        Path target = absPath.getParent().resolve(to);
        Files.createDirectories(target.getParent());
        Files.move(absPath, target, StandardCopyOption.REPLACE_EXISTING);
        return ExecResult.success(FileOp.OpType.RENAME, op.requestId())
                .path(op.path()).summary("已重命名 " + op.path() + " -> " + to);
    }

    private ExecResult stat(FileOp op, Path absPath) throws IOException {
        if (!Files.exists(absPath)) {
            return ExecResult.failure(FileOp.OpType.STAT, op.requestId(), "目标不存在：" + op.path());
        }
        boolean dir = Files.isDirectory(absPath);
        long size = dir ? 0 : Files.size(absPath);
        return ExecResult.success(FileOp.OpType.STAT, op.requestId())
                .path(op.path()).size(size).summary((dir ? "目录" : "文件") + " " + op.path());
    }

    private boolean isDiskFull(IOException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("no space") || msg.contains("空间不足") || msg.contains("磁盘已满");
    }
}

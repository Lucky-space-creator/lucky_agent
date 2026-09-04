package com.lucky.agent.common.util;

import com.lucky.agent.common.exception.AgentException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 路径规范化工具（供 executor 的 BoundaryGuard 复用）。
 *
 * <p>对 {@code ../} 与符号链接逃逸返回 {@link AgentException}，阻断路径越出工作空间；
 * 这是执行臂的硬边界，任何操作落盘前必须经过校验。</p>
 */
public final class PathUtil {

    private PathUtil() {
    }

    /**
     * 以工作空间根为锚解析用户路径（字符串级，不访问文件系统）。
     *
     * @param base     工作空间根目录
     * @param userPath 用户提供的路径（绝对或相对）
     * @return 规范化后的绝对路径
     * @throws AgentException 路径越界（含 {@code ..} 逃逸）
     */
    public static Path resolveWithin(Path base, String userPath) {
        if (base == null || userPath == null) {
            throw new AgentException("EXEC_INVALID_PATH", "路径不能为空");
        }
        Path baseAbs = base.toAbsolutePath().normalize();
        // 工作空间内路径均为相对路径；剥离可能误带的前导分隔符（/ 或 \），
        // 避免 Path.resolve 在 Windows 上将 "/x" 当作绝对路径、从而脱离工作空间根。
        String normalized = userPath.replaceAll("^[/\\\\]+", "");
        Path candidate = baseAbs.resolve(normalized).normalize();
        if (!candidate.startsWith(baseAbs)) {
            throw new AgentException("EXEC_OUT_OF_BOUNDS", "路径越界，禁止访问工作空间外：" + userPath);
        }
        return candidate;
    }

    /**
     * 校验并返回真实路径（文件系统级，阻断符号链接逃逸）。
     *
     * <p>对存在的目标做 {@code toRealPath()}，确认真实物理路径仍处于工作空间根内；
     * 目标不存在（如待写入文件）时对最深已存在祖先做校验。</p>
     *
     * @param base     工作空间根目录
     * @param userPath 用户提供的路径
     * @return 真实路径
     * @throws AgentException 路径越界或 IO 失败
     */
    public static Path realpathWithin(Path base, String userPath) {
        Path baseAbs = base.toAbsolutePath().normalize();
        Path candidate = resolveWithin(baseAbs, userPath);
        try {
            Path baseReal = realpathOrNull(baseAbs);
            if (baseReal == null) {
                return candidate;
            }
            Path targetReal = realpathOfDeepestExisting(candidate);
            if (targetReal != null && !targetReal.startsWith(baseReal)) {
                throw new AgentException("EXEC_OUT_OF_BOUNDS", "符号链接越界，禁止访问工作空间外：" + userPath);
            }
            return candidate;
        } catch (IOException e) {
            throw new AgentException("EXEC_IO_ERROR", "路径校验失败：" + userPath, e);
        }
    }

    /** 路径是否位于 base 之下（字符串级判断）。 */
    public static boolean isWithin(Path base, Path target) {
        return target.toAbsolutePath().normalize().startsWith(base.toAbsolutePath().normalize());
    }

    private static Path realpathOrNull(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }

    /** 对最深已存在祖先做 toRealPath，用于目标尚不存在时的符号链接校验。 */
    private static Path realpathOfDeepestExisting(Path candidate) throws IOException {
        Path p = candidate;
        if (Files.exists(p)) {
            return p.toRealPath();
        }
        Path parent = p.getParent();
        while (parent != null) {
            if (Files.exists(parent)) {
                return parent.toRealPath();
            }
            parent = parent.getParent();
        }
        return null;
    }
}

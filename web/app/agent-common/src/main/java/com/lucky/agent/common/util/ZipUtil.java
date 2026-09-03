package com.lucky.agent.common.util;

import com.lucky.agent.common.exception.AgentException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 压缩包安全解压工具（供 Skill/MCP 本地上传解包复用）。
 *
 * <p>逐条目解压时校验：拒绝绝对路径、拒绝包含 {@code ..} 的越界路径，防止恶意压缩包
 * 覆盖工作空间外文件；目标目录不存在时自动创建。解压失败统一转为面向用户的友好提示。</p>
 */
public final class ZipUtil {

    private ZipUtil() {
    }

    /**
     * 将 zip 内容安全解压到 {@code targetDir}。
     *
     * @param zipStream zip 输入流（由调用方负责关闭）
     * @param targetDir 解压目标目录（不存在时自动创建）
     * @throws AgentException 条目路径越界或解压 IO 失败（消息面向用户友好）
     */
    public static void extractZip(InputStream zipStream, Path targetDir) {
        Path root = targetDir.toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = resolveEntry(root, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new AgentException("IMPORT_ARCHIVE_ERROR", "压缩包解压失败：文件可能已损坏", e);
        }
    }

    /** 解析条目路径并校验不越界（拒绝绝对路径与 {@code ..} 逃逸）。 */
    private static Path resolveEntry(Path root, String name) {
        Path candidate = root.resolve(name).normalize();
        if (!candidate.startsWith(root)) {
            throw new AgentException("IMPORT_ARCHIVE_UNSAFE", "压缩包包含越界路径，已拒绝上传：" + name);
        }
        return candidate;
    }
}

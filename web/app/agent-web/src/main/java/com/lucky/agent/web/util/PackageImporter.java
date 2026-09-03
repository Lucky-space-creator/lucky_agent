package com.lucky.agent.web.util;

import com.lucky.agent.common.exception.AgentException;
import com.lucky.agent.common.util.ZipUtil;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地上传包解包工具（Skill / MCP 上传共用）。
 *
 * <p>接受单个 {@code meta.json}（Skill）或 {@code SKILL.md} 文件，或 {@code .zip} 压缩包：
 * 解包到临时目录后递归定位「内容根目录」。内容根目录以存在 {@code meta.json} 或 {@code SKILL.md}
 * 为判据——兼容社区标准技能包（只有 SKILL.md、无 meta.json）。</p>
 *
 * <p>批量定位：同一压缩包内含多个技能包时，逐一识别并返回全部内容根目录，供调用方批量导入。</p>
 */
public final class PackageImporter {

    private static final String META = "meta.json";
    private static final String SKILL_MD = "SKILL.md";

    private PackageImporter() {
    }

    /**
     * 将上传文件解包到 {@code tempDir}，返回全部内容根目录（含 meta.json 或 SKILL.md 的目录）。
     *
     * @param file    上传文件（{@code .zip}、单个 {@code meta.json} 或单个 {@code SKILL.md}）
     * @param tempDir 解包临时目录（须已存在，调用方负责清理）
     * @return 内容根目录列表（可为多个）
     * @throws AgentException 文件为空/解包失败/包内没有任何技能包
     */
    public static List<Path> unpackAll(MultipartFile file, Path tempDir) {
        String filename = file.getOriginalFilename();
        boolean zip = filename != null && filename.toLowerCase().endsWith(".zip");
        try {
            if (zip) {
                ZipUtil.extractZip(file.getInputStream(), tempDir);
            } else {
                Files.write(tempDir.resolve(filename == null ? META : filename), file.getBytes());
            }
        } catch (IOException e) {
            throw new AgentException("IMPORT_READ_ERROR", "读取上传文件失败，请重试", e);
        }
        return locateAllContentRoots(tempDir);
    }

    /** 兼容旧调用：返回单个内容根目录，多包时抛歧义异常。 */
    public static Path unpack(MultipartFile file, Path tempDir) {
        List<Path> roots = unpackAll(file, tempDir);
        if (roots.isEmpty()) {
            throw new AgentException("IMPORT_META_MISSING", "上传包内未找到 meta.json 或 SKILL.md");
        }
        if (roots.size() > 1) {
            throw new AgentException("IMPORT_AMBIGUOUS", "压缩包包含多个配置包，请分别上传");
        }
        return roots.get(0);
    }

    /** 递归定位所有内容根目录：目录下含 meta.json 或 SKILL.md 即视为一个技能包根。 */
    private static List<Path> locateAllContentRoots(Path tempDir) {
        List<Path> roots = new ArrayList<>();
        walk(tempDir, roots);
        return roots;
    }

    private static void walk(Path dir, List<Path> roots) {
        if (isContentRoot(dir)) {
            roots.add(dir);
            return; // 命中即不再深入其子目录，避免把引用资源目录误判为独立包
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                walk(child, roots);
            }
        } catch (IOException e) {
            // 目录读取失败视为无子内容，交由上层决定
        }
    }

    /** 内容根判据：目录内直接存在 meta.json 或 SKILL.md。 */
    private static boolean isContentRoot(Path dir) {
        return Files.isRegularFile(dir.resolve(META)) || Files.isRegularFile(dir.resolve(SKILL_MD));
    }

    /** 递归删除目录（临时目录清理），IO 失败忽略交由系统回收。 */
    public static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败可忽略，交由系统回收
                }
            });
        } catch (IOException ignored) {
            // 清理失败可忽略，交由系统回收
        }
    }

    /** 递归统计内容根目录下全部文件数（供导入限量/提示）。 */
    public static long countFiles(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }
}

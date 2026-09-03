package com.lucky.agent.permission.guard;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 符号链接双路径解析（D14 P2：symlink 双路径）。
 *
 * <p>权限锚定以「用户给定路径」与「文件系统真实路径」双路径同时约束：任一越出工作区根即判越界。
 * 真实路径通过 {@code toRealPath} 展开符号链接后取得，规避软链逃逸。</p>
 */
public class SymlinkResolver {

    /**
     * 解析真实路径（展开符号链接）。无法解析时回退为给定路径的归一化形式。
     *
     * @param path 待解析路径
     * @return 真实路径（已归一化）
     */
    public Path resolveReal(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // 文件不存在或无法访问：回退归一化，交由调用方按字面判定
            return path.normalize();
        }
    }

    /**
     * 判定给定路径是否位于锚定根目录之下（含根自身）。
     *
     * @param anchor 锚定根目录（工作区根 / frameworkRoot）
     * @param target 目标路径
     * @return 在边界内返回 true
     */
    public boolean within(Path anchor, Path target) {
        Path normAnchor = anchor.normalize();
        Path normTarget = target.normalize();
        if (normTarget.equals(normAnchor)) {
            return true;
        }
        String a = withTrailingSlash(normAnchor.toString());
        String t = normTarget.toString();
        return t.equals(normAnchor.toString()) || t.startsWith(a);
    }

    private String withTrailingSlash(String s) {
        return s.endsWith(java.io.File.separator) ? s : s + java.io.File.separator;
    }
}

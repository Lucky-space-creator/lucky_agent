package com.lucky.agent.executor.fileops;

import java.util.Locale;
import java.util.Set;

/**
 * 文件类型分发：按扩展名识别文件类型，供解析触发 Skill 使用（Skill 在 Phase 3）。
 */
public class TypeDispatcher {

    private static final Set<String> TEXT_EXT = Set.of("txt", "md", "markdown", "log", "csv", "json",
            "xml", "yaml", "yml", "toml", "ini", "properties");
    private static final Set<String> CODE_EXT = Set.of("java", "kt", "groovy", "py", "js", "ts", "tsx",
            "jsx", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "rb", "sh", "bat", "ps1", "sql",
            "html", "css", "scss", "vue", "gradle", "xml");
    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg");

    /**
     * 识别文件类型。
     *
     * @param path 文件路径
     * @return text / code / image / binary
     */
    public String detectType(String path) {
        if (path == null || !path.contains(".")) {
            return "text";
        }
        String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (TEXT_EXT.contains(ext)) {
            return "text";
        }
        if (CODE_EXT.contains(ext)) {
            return "code";
        }
        if (IMAGE_EXT.contains(ext)) {
            return "image";
        }
        return "binary";
    }
}

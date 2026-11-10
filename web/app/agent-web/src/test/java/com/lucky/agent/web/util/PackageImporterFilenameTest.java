package com.lucky.agent.web.util;

import com.lucky.agent.common.exception.AgentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上传包解包的文件名清洗契约（台账 #43）。
 *
 * <p>{@code originalFilename} 来自客户端：可携带路径分隔符（{@code ../../evil.txt}、
 * {@code C:\x\meta.json}）——此前直接 {@code tempDir.resolve(filename)} 落盘，
 * 路径跟着走，能写穿临时目录。清洗后只保留最后一段，且必须仍能完成正常的
 * meta.json / SKILL.md 上传。</p>
 */
class PackageImporterFilenameTest {

    @TempDir
    Path tempDir;

    private static MockMultipartFile upload(String filename, byte[] bytes) {
        return new MockMultipartFile("file", filename, "application/octet-stream", bytes);
    }

    @Test
    @DisplayName("正常文件名落盘：meta.json / SKILL.md 都能识别为内容根")
    void normalUploadStillWorks() throws Exception {
        MockMultipartFile file = upload("meta.json",
                "{\"id\":\"demo\"}".getBytes(StandardCharsets.UTF_8));

        List<Path> roots = PackageImporter.unpackAll(file, tempDir);

        assertThat(roots).hasSize(1);
        assertThat(tempDir.resolve("meta.json")).exists();
    }

    @Test
    @DisplayName("#43 带路径分隔符的文件名被清洗为纯文件名，不得写穿临时目录")
    void pathTraversalInFilenameIsNeutralized() throws Exception {
        MockMultipartFile evil = upload("../../evil.txt", "boom".getBytes(StandardCharsets.UTF_8));

        PackageImporter.unpackAll(evil, tempDir);

        assertThat(tempDir.resolve("evil.txt")).exists();
        assertThat(tempDir.resolve("evil.txt"))
                .as("落盘文件必须在临时目录内，内容为上传体")
                .hasContent("boom");
        // 目标逃逸位置必须不存在：临时目录上两级不该多出 evil.txt
        Path outside = tempDir.getParent().getParent().resolve("evil.txt");
        assertThat(outside).doesNotExist();
    }

    @Test
    @DisplayName("Windows 风格路径与目录引用同样清洗；空名回落 meta.json")
    void sanitizesBackslashAndDotDot() {
        assertThat(PackageImporter.sanitizeFilename("C:\\x\\y\\meta.json")).isEqualTo("meta.json");
        assertThat(PackageImporter.sanitizeFilename("a/b/SKILL.md")).isEqualTo("SKILL.md");
        assertThat(PackageImporter.sanitizeFilename("..")).isEqualTo("meta.json");
        assertThat(PackageImporter.sanitizeFilename(".")).isEqualTo("meta.json");
        assertThat(PackageImporter.sanitizeFilename("  ")).isEqualTo("meta.json");
        assertThat(PackageImporter.sanitizeFilename(null)).isEqualTo("meta.json");
        assertThat(PackageImporter.sanitizeFilename("ok\u0000name.txt")).isEqualTo("okname.txt");
    }

    @Test
    @DisplayName("解包失败仍报既有错误码（行为不回归）")
    void brokenZipStillReportsImportError() {
        MockMultipartFile broken = upload("broken.zip", "not-a-zip".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> PackageImporter.unpack(broken, tempDir))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("压缩包解压失败");
    }
}

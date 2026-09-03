package com.lucky.agent.common.util;

import com.lucky.agent.common.exception.AgentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 压缩包安全解压单元测试：正常解包、路径逃逸/绝对路径拒绝。
 */
class ZipUtilTest {

    @TempDir
    Path temp;

    @Test
    void testExtractsNormalZip() throws Exception {
        byte[] zip = buildZip(new String[][]{
                {"pdf/meta.json", "{\"id\":\"pdf\"}"},
                {"pdf/SKILL.md", "# PDF 技能"},
        });

        ZipUtil.extractZip(new ByteArrayInputStream(zip), temp.resolve("out"));

        assertEquals("{\"id\":\"pdf\"}", Files.readString(temp.resolve("out/pdf/meta.json")));
        assertEquals("# PDF 技能", Files.readString(temp.resolve("out/pdf/SKILL.md")));
    }

    @Test
    void testRejectsPathTraversalEntry() throws Exception {
        byte[] zip = buildZip(new String[][]{{"../evil.txt", "boom"}});

        assertThrows(AgentException.class,
                () -> ZipUtil.extractZip(new ByteArrayInputStream(zip), temp.resolve("out")));
    }

    @Test
    void testRejectsAbsolutePathEntry() throws Exception {
        byte[] zip = buildZip(new String[][]{{"/etc/passwd", "boom"}});

        assertThrows(AgentException.class,
                () -> ZipUtil.extractZip(new ByteArrayInputStream(zip), temp.resolve("out")));
    }

    /** 构造包含给定条目（name/content）的 zip 字节流。 */
    private byte[] buildZip(String[][] entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (String[] entry : entries) {
                zos.putNextEntry(new ZipEntry(entry[0]));
                zos.write(entry[1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}

package com.lucky.agent.memory.support.md;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 两级记忆索引（MEMORY.md，对标 Claude Code 的索引机制）。
 *
 * <p>索引与条目分离：{@code memory.md} 存完整条目（每条以 {@code - [type] 内容} 开头），
 * {@code MEMORY.md} 存索引行（{@code - [type] 一句话描述 #mem-xxx}）指向条目中的锚点。
 * 索引全量轻量注入 System Prompt（可有上限），条目全文按需读取。</p>
 *
 * <p><b>幂等</b>：锚点 id 由「类型 + 条目首行」的 SHA-256 前 8 位派生，同一内容反复合并
 * 生成相同 id，索引不会随合并重复漂移。超行数/字节上限时保留最早条目并在末尾打标记，
 * 完整修剪由 Dream 合成执行。</p>
 */
@Slf4j
public class MemoryIndex {

    /** 索引文件名。 */
    public static final String INDEX_FILE = "MEMORY.md";

    /** 索引头部说明（可读性）。 */
    private static final String HEADER = "# 记忆索引（MEMORY.md）\n"
            + "# 每条形如：- [type] 一句话描述 #mem-xxx（type: user/feedback/project/reference）\n";

    /** 摘要长度上限（字符）。 */
    private static final int SUMMARY_MAX = 60;

    /** 条目起始行：{@code - [type] ...}。 */
    private static final Pattern ENTRY_START = Pattern.compile("(?m)^- \\[([^\\]]+)\\] (.*)$");

    /** 锚点（插入在条目首行前）。 */
    private static final Pattern ANCHOR = Pattern.compile("<a name=\"([^\"]+)\"></a>");

    private final int maxLines;
    private final int maxBytes;

    public MemoryIndex(int maxLines, int maxBytes) {
        this.maxLines = Math.max(10, maxLines);
        this.maxBytes = Math.max(1024, maxBytes);
    }

    /** 索引产物：带锚点的条目全文 + 索引行。 */
    public record IndexedContent(String contentWithAnchors, List<String> indexLines) {
    }

    /**
     * 为一段「合并后的条目文本」分配锚点并生成索引行（幂等：同内容同锚点）。
     * 旧条目（无锚点、非 {@code - [type]} 开头）原样保留，不参与索引。
     *
     * @param rawContent 合并或总结出的条目文本
     * @return 带锚点内容 + 索引行（无分类条目时索引行为空且内容原样返回）
     */
    public IndexedContent index(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return new IndexedContent("", List.of());
        }
        String[] lines = rawContent.split("\\R", -1);
        List<String> out = new ArrayList<>();
        Map<String, String> indexLines = new LinkedHashMap<>();
        int i = 0;
        while (i < lines.length) {
            String start = lines[i];
            Matcher m = ENTRY_START.matcher(start);
            if (!m.find()) {
                out.add(start); // 非条目行（标题/空行/说明）原样保留
                i++;
                continue;
            }
            // 收集本条条目文本（到下一条目起始行或文件尾）
            StringBuilder entry = new StringBuilder(start);
            i++;
            while (i < lines.length && !ENTRY_START.matcher(lines[i]).find()) {
                entry.append('\n').append(lines[i]);
                i++;
            }
            String type = m.group(1).trim();
            String summary = trimSummary(m.group(2));
            String id = entryId(type, summary);
            out.add("<a name=\"" + id + "\"></a>\n" + entry);
            indexLines.put(id, "- [" + type + "] " + summary + " #" + id);
        }
        return new IndexedContent(String.join("\n", out), new ArrayList<>(indexLines.values()));
    }

    /**
     * 从 memory.md 提取指定锚点条目的全文（预取/工具读取用）。
     *
     * @param memoryFile memory.md 路径（可能不存在）
     * @param ids        锚点 id 集合
     * @return 命中的条目文本（按 id 顺序），未命中忽略
     */
    public Map<String, String> readEntries(Path memoryFile, Set<String> ids) {
        Map<String, String> result = new LinkedHashMap<>();
        if (memoryFile == null || ids == null || ids.isEmpty() || !Files.exists(memoryFile)) {
            return result;
        }
        try {
            String content = Files.readString(memoryFile, StandardCharsets.UTF_8);
            Matcher anchor = ANCHOR.matcher(content);
            int searchFrom = 0;
            while (anchor.find(searchFrom)) {
                String id = anchor.group(1);
                int start = anchor.end();
                // 条目结束：下一个锚点或文件尾
                int end = content.length();
                Matcher next = ANCHOR.matcher(content);
                if (next.find(start)) {
                    end = next.start();
                }
                if (ids.contains(id)) {
                    result.putIfAbsent(id, content.substring(start, end).trim());
                }
                searchFrom = Math.max(end, start);
                if (searchFrom >= content.length()) {
                    break;
                }
            }
            return result;
        } catch (IOException e) {
            log.warn("读取记忆条目失败：{}", memoryFile, e);
            return result;
        }
    }

    /**
     * 读取索引文件内容（不存在返回空串）。
     *
     * @param dir 索引所在目录（根或 workspace 目录）
     * @return MEMORY.md 内容
     */
    public String readIndex(Path dir) {
        return readFile(dir == null ? null : dir.resolve(INDEX_FILE));
    }

    /**
     * 写入索引文件（覆盖，含头部；超上限保留最早 maxLines 行并打满标记）。
     *
     * @param dir        索引所在目录（根或 workspace 目录）
     * @param indexLines 索引行
     */
    public void writeIndex(Path dir, List<String> indexLines) {
        if (dir == null) {
            return;
        }
        List<String> lines = indexLines == null ? List.of() : new ArrayList<>(indexLines);
        List<String> bounded = new ArrayList<>();
        bounded.add("<!-- " + INDEX_FILE + "：记忆索引，最多 " + maxLines + " 行 / " + maxBytes + " 字节 -->");
        bounded.addAll(lines);
        if (lines.size() > maxLines) {
            bounded = new ArrayList<>(bounded.subList(0, maxLines + 1));
            bounded.add("<!-- 索引已满（" + lines.size() + " 行），Dream 将修剪整理 -->");
        }
        String text = HEADER + String.join("\n", bounded) + "\n";
        // 字节上限兜底
        if (text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            text = HEADER + String.join("\n", bounded.subList(0, Math.max(1, bounded.size() - 1))) + "\n";
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(INDEX_FILE), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写入记忆索引失败：{}", dir, e);
        }
    }

    /** 稳定锚点 id：type + summary 的 SHA-256 前 8 位。 */
    private String entryId(String type, String summary) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((type + "|" + summary).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("mem-");
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "mem-" + Integer.toHexString((type + "|" + summary).hashCode()).substring(0, 8);
        }
    }

    private String trimSummary(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= SUMMARY_MAX ? flat : flat.substring(0, SUMMARY_MAX) + "…";
    }

    private String readFile(Path file) {
        if (file == null || !Files.exists(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取索引文件失败：{}", file, e);
            return "";
        }
    }
}
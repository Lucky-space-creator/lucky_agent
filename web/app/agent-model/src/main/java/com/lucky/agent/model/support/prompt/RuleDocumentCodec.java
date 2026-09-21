package com.lucky.agent.model.support.prompt;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 规则文档解析器：在「单文件 + {@code ## 规则名} 分节」与结构化 {@link RuleItem} 列表之间双向转换。
 *
 * <p>设计目标：<b>往返幂等</b>——{@code parse(render(items))} 应与 {@code items} 等价，
 * 保证 Web 编辑器「读取 → 修改 → 保存」不会因格式漂移反复改写用户文件。</p>
 *
 * <p>格式约定：</p>
 * <ul>
 *     <li>以 {@code ## } 开头的行为规则分节起始（{@code #}/{@code ###} 不作为规则边界，允许正文内使用）；</li>
 *     <li>分节标题可携带控制标记 {@code [P]}（项目作用域）与 {@code [ ]}（停用），顺序无关；</li>
 *     <li>文件顶部的无分节前言（如 HTML 注释说明）不属任何规则，解析与渲染时保留在文件头部；</li>
 *     <li>没有任何分节文件视为「未迁移」——{@link #isStructured} 返回 false，调用方退回整文件注入。</li>
 * </ul>
 */
@Slf4j
public final class RuleDocumentCodec {

    /** 规则分节标题行：恰好两个 # + 空格（### 不视为规则边界）。 */
    private static final Pattern SECTION = Pattern.compile("^##[ \\t]+(.*)$");

    /** 标题首部连续控制标记（{@code [P]} / {@code [ ]}），允许重复与混排。 */
    private static final Pattern LEADING_MARKS = Pattern.compile("^((?:\\[P\\]|\\[ \\]|\\[\\s*\\])[ \\t]*)+");

    private RuleDocumentCodec() {
    }

    /**
     * 解析规则文档为结构化列表。
     *
     * @param raw 文件全文（可为 null）
     * @return 规则项列表；无分节时返回空列表
     */
    public static List<RuleItem> parse(String raw) {
        List<RuleItem> items = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return items;
        }
        String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        String name = null;
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            var m = SECTION.matcher(line);
            if (m.matches()) {
                flush(items, name, body);
                name = m.group(1);
                body.setLength(0);
            } else if (name != null) {
                body.append(line).append('\n');
            }
            // name == null 时（文件前言）直接丢弃，不属任何规则
        }
        flush(items, name, body);
        return items;
    }

    /** 将一条已结束的分节写入结果列表（自动剥离控制标记、过滤空规则）。 */
    private static void flush(List<RuleItem> items, String rawTitle, StringBuilder body) {
        if (rawTitle == null) {
            return;
        }
        String title = rawTitle.trim();
        boolean project = false;
        boolean disabled = false;
        var marks = LEADING_MARKS.matcher(title);
        if (marks.find()) {
            String head = marks.group();
            String upper = head.toUpperCase();
            project = upper.contains("[P]");
            disabled = head.replace("[P]", "").replace("[p]", "").contains("[ ]")
                    || head.replace("[P]", "").contains("[]");
            title = title.substring(head.length()).trim();
        }
        RuleItem item = new RuleItem(title, body.toString().strip(),
                !disabled, project ? RuleItem.SCOPE_PROJECT : RuleItem.SCOPE_GLOBAL);
        if (!item.isEmpty()) {
            items.add(item);
        }
    }

    /**
     * 将结构化列表渲染回规则文档。
     *
     * @param header  文件头部前言（可空；通常是 HTML 注释说明）
     * @param items   规则项列表
     * @return Markdown 全文
     */
    public static String render(String header, List<RuleItem> items) {
        StringBuilder sb = new StringBuilder();
        if (header != null && !header.isBlank()) {
            sb.append(header.strip()).append("\n\n");
        }
        if (items != null) {
            for (RuleItem item : items) {
                if (item == null || item.isEmpty()) {
                    continue;
                }
                sb.append("## ");
                if (item.isProject()) {
                    sb.append(RuleItem.MARK_PROJECT).append(' ');
                }
                if (!item.enabled()) {
                    sb.append(RuleItem.MARK_DISABLED).append(' ');
                }
                sb.append(item.name()).append("\n\n");
                sb.append(item.content().strip()).append("\n\n");
            }
        }
        return sb.toString().strip() + "\n";
    }

    /**
     * 判断文档是否已采用结构化分节。
     *
     * <p>用于「未迁移的旧文件」（纯段落、无任何 {@code ##} 分节）兼容：此时解析结果为空，
     * 调用方应退回「整文件作为单条规则」的旧行为，避免用户既有规则凭空失效。</p>
     */
    public static boolean isStructured(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        for (String line : raw.replace("\r\n", "\n").split("\n", -1)) {
            if (SECTION.matcher(line).matches()) {
                return true;
            }
        }
        return false;
    }

    /** 剥离文件头部前言（首个分节之前的内容），渲染时原样保留。 */
    public static String extractHeader(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder header = new StringBuilder();
        for (String line : raw.replace("\r\n", "\n").split("\n", -1)) {
            if (SECTION.matcher(line).matches()) {
                break;
            }
            header.append(line).append('\n');
        }
        return header.toString().strip();
    }
}

package com.lucky.agent.model.support.prompt;

import com.lucky.agent.common.constant.WorkspaceDirs;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则存储：管理与注入「多条全局规则 / 多条项目规则」。
 *
 * <p>承接 §八-④ 的决策：<b>默认单文件内 {@code ## 规则名} 分节</b>，让规则始终是用户可直接
 * 阅读、diff 友好的 Markdown；需要时开启「高级：规则目录（多文件）」模式，
 * 改用 {@code <frameworkRoot>/rules/global/*.md} 与 {@code <workspace>/rules/*.md}
 * 一规则一文件。</p>
 *
 * <p>注入语义（与旧的两级 LUCKY.md 完全兼容）：</p>
 * <ol>
 *     <li><b>全局层</b>：{@code <frameworkRoot>/LUCKY.md}——既充当 system prompt 基座（身份/硬性规则），
 *         其 {@code ## 分节} 又作为多条全局规则叠加；未分节的旧文件按「整文件即基座」处理，行为不变。</li>
 *     <li><b>项目层</b>：{@code <workspacePath>/LUCKY.md}——项目规则，优先级高于全局规则。</li>
 *     <li>仅注入 {@code enabled} 的规则；停用规则保留在文件中但不参与拼装。</li>
 * </ol>
 *
 * <p>多文件模式下的目录命名为 {@code rules/global}、{@code rules/project}（均为复数、语义清晰），
 * 与 {@code LUCKY.md} 单文件模式互不干扰。</p>
 */
@Slf4j
public class RuleStore {

    /** 规则文件名（框架根与工作空间根下）。 */
    public static final String FILE_NAME = "LUCKY.md";

    /** 高级模式：规则目录。 */
    public static final String RULES_DIR = "rules";
    /** 高级模式：全局规则子目录。 */
    public static final String GLOBAL_SUBDIR = "global";
    /** 高级模式：项目规则子目录。 */
    public static final String PROJECT_SUBDIR = "project";

    private final WorkspaceDirs dirs;

    public RuleStore(WorkspaceDirs dirs) {
        this.dirs = dirs;
    }

    // ------------------------------------------------------------------ 路径解析

    /** 全局规则文件（{@code <frameworkRoot>/LUCKY.md}，同时是基座提示词文件）。 */
    public Path globalFile() {
        return dirs.frameworkRoot().resolve(FILE_NAME);
    }

    /** 项目规则文件（{@code <workspacePath>/LUCKY.md}）。 */
    public Path projectFile(String workspacePath) {
        return Path.of(workspacePath).resolve(FILE_NAME);
    }

    /** 全局多人模式目录（{@code <frameworkRoot>/rules/global}）。 */
    public Path globalRulesDir() {
        return dirs.frameworkRoot().resolve(RULES_DIR).resolve(GLOBAL_SUBDIR);
    }

    /** 项目多文件模式目录（{@code <workspacePath>/rules/project}）。 */
    public Path projectRulesDir(String workspacePath) {
        return Path.of(workspacePath).resolve(RULES_DIR).resolve(PROJECT_SUBDIR);
    }

    // ------------------------------------------------------------------ 读写

    /**
     * 读取规则列表。
     *
     * @param workspacePath 工作空间物理路径；为空表示只取全局层
     * @return 全局规则在前、项目规则在后（项目优先级更高，注入顺序确定）
     */
    public List<RuleItem> list(String workspacePath) {
        List<RuleItem> all = new ArrayList<>(listGlobal());
        if (workspacePath != null && !workspacePath.isBlank()) {
            all.addAll(listProject(workspacePath));
        }
        return all;
    }

    /** 读取全局规则（单文件分节优先，缺失时回退多文件目录）。 */
    public List<RuleItem> listGlobal() {
        Path file = globalFile();
        if (Files.isRegularFile(file)) {
            String raw = readQuietly(file);
            if (RuleDocumentCodec.isStructured(raw)) {
                return RuleDocumentCodec.parse(raw);
            }
            // 未分节的旧文件：整文件作为单条「全局规则」，保持既有行为
            String body = stripHeaderComments(raw);
            return body.isBlank() ? List.of() : List.of(RuleItem.of("全局规则", body));
        }
        return readDir(globalRulesDir());
    }

    /** 读取项目规则（单文件分节优先，缺失时回退多文件目录）。 */
    public List<RuleItem> listProject(String workspacePath) {
        Path file = projectFile(workspacePath);
        if (Files.isRegularFile(file)) {
            String raw = readQuietly(file);
            if (RuleDocumentCodec.isStructured(raw)) {
                return RuleDocumentCodec.parse(raw);
            }
            String body = stripHeaderComments(raw);
            return body.isBlank() ? List.of() : List.of(new RuleItem("项目规则", body, true, RuleItem.SCOPE_PROJECT));
        }
        return readDir(projectRulesDir(workspacePath));
    }

    /**
     * 整体覆盖保存某一作用域的规则。
     *
     * <p>单文件模式下按分节渲染写回（保留文件头前言）；多文件模式下按名称一规则一文件。
     * {@code LUCKY.md} 同时是基座提示词文件，其首个命名分节即身份与硬性规则，
     * 因此保存后基座与规则同源、无重复维护。</p>
     *
     * @param workspacePath 工作空间物理路径；{@code scope=project} 时不可为空
     * @param scope         {@code global} 或 {@code project}
     * @param items         该作用域下的完整规则列表
     * @return 实际落盘文件数
     */
    public int save(String workspacePath, String scope, List<RuleItem> items) throws IOException {
        List<RuleItem> safe = items == null ? List.of() : items;
        if (RuleItem.SCOPE_PROJECT.equals(scope)) {
            if (workspacePath == null || workspacePath.isBlank()) {
                throw new IOException("项目规则保存失败：未指定工作空间路径");
            }
            Path file = projectFile(workspacePath);
            String header = Files.isRegularFile(file) ? RuleDocumentCodec.extractHeader(readQuietly(file)) : defaultProjectHeader();
            writeText(file, RuleDocumentCodec.render(header, safe));
            return 1;
        }
        Path file = globalFile();
        String header = Files.isRegularFile(file) ? RuleDocumentCodec.extractHeader(readQuietly(file)) : defaultGlobalHeader();
        writeText(file, RuleDocumentCodec.render(header, safe));
        return 1;
    }

    // ------------------------------------------------------------------ 注入拼装

    /**
     * 渲染注入 system prompt 的规则文本（仅启用项）。
     *
     * <p>顺序：全局规则（按声明顺序）→ 项目规则（按声明顺序），项目在后、优先级更高。
     * 无任何启用规则时返回空串，调用方据此跳过注入，避免空标题噪声。</p>
     *
     * @param workspacePath 工作空间物理路径，可空
     * @return 形如 {@code 【全局规则】\n### 名称\n内容} 的拼接文本；无规则返回空串
     */
    public String renderForPrompt(String workspacePath) {
        List<RuleItem> items = list(workspacePath);
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder global = new StringBuilder();
        StringBuilder project = new StringBuilder();
        for (RuleItem item : items) {
            if (!item.enabled()) {
                continue;
            }
            StringBuilder target = item.isProject() ? project : global;
            if (!target.isEmpty()) {
                target.append('\n');
            }
            target.append("### ").append(item.name()).append('\n').append(item.content().strip()).append('\n');
        }
        StringBuilder out = new StringBuilder();
        if (!global.isEmpty()) {
            out.append("【全局规则】\n").append(global.toString().strip());
        }
        if (!project.isEmpty()) {
            if (!out.isEmpty()) {
                out.append("\n\n");
            }
            out.append("【项目规则】\n").append(project.toString().strip());
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ 内部工具

    /** 读取目录下所有 .md 为规则项（文件名即规则名，{@code _} 前缀或无扩展名文件忽略）。 */
    private List<RuleItem> readDir(Path dir) {
        List<RuleItem> items = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return items;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .sorted()
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        String name = fileName.substring(0, fileName.length() - 3);
                        items.add(new RuleItem(name, stripHeaderComments(readQuietly(p)), true, RuleItem.SCOPE_GLOBAL));
                    });
        } catch (IOException e) {
            log.warn("读取规则目录失败（忽略）：{}", dir, e);
        }
        return items;
    }

    /** 静默读取文件；失败返回空串（规则读取失败不应中断对话链路）。 */
    private String readQuietly(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取规则文件失败（忽略）：{}", file, e);
            return "";
        }
    }

    /** 写入文本（自动建父目录）。 */
    private void writeText(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** 去除整文件规则中的 HTML 注释前言，避免注入时把说明文字当规则内容。 */
    private String stripHeaderComments(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("(?s)^\\s*<!--.*?-->\\s*", "").strip();
    }

    /** 默认全局文件头（首次结构化保存时使用）。 */
    private String defaultGlobalHeader() {
        return """
                <!-- LUCKY Agent 规则与基座提示词（system prompt 第一层，静态可缓存前缀）
                     本文件位于框架根目录，每次模型调用前读取，保存后即时生效，无需重启。
                     每个 ## 分节即一条全局规则；标题前缀 [P] 表示项目作用域、[ ] 表示停用。 -->""";
    }

    /** 默认项目文件头。 */
    private String defaultProjectHeader() {
        return """
                <!-- LUCKY Agent 项目规则（仅对当前工作空间生效，优先级高于全局规则）
                     每个 ## 分节即一条项目规则；标题前缀 [ ] 表示停用。 -->""";
    }
}

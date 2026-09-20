package com.lucky.agent.skill.support.sandbox;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.executor.api.FileService;
import com.lucky.agent.skill.repository.dto.SkillDef;
import com.lucky.agent.skill.repository.SkillLoader;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Skill 执行沙箱：执行仍经本机执行臂硬边界（realpath 越界防护），不另造防护。
 *
 * <p>两种执行模式：</p>
 * <ul>
 *     <li><b>纯指令型</b>（entry 为空）：返回 SKILL.md 正文引导模型按步骤操作，
 *         实际文件/命令操作仍走 file/shell 工具（经执行臂），无额外越界面。</li>
 *     <li><b>脚本型</b>（entry 为命令模板）：经 {@link FileService#exec} 在工作空间内执行；
 *         {@code sandbox=true} 时需工作空间为全部权限，危险操作仍由执行臂转 ASK。</li>
 * </ul>
 */
public class SkillSandbox {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final FileService fileService;
    private final SkillLoader loader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillSandbox(FileService fileService, SkillLoader loader) {
        this.fileService = fileService;
        this.loader = loader;
    }

    /**
     * 执行一次 Skill 调用。
     *
     * @param skill 命中的 Skill 定义
     * @param ctx   会话执行上下文（提供 workspaceId 与权限级别）
     * @param args  模型传入参数（脚本型注入 {@code {args}} 占位或尾随 JSON）
     * @return 工具结果，错误以 ok=false 返回
     */
    public ToolResult run(SkillDef skill, ConversationCtx ctx, Map<String, Object> args) {
        if (skill.sandbox() && (ctx.permissionLevel() == null || ctx.permissionLevel() != PermissionLevel.FULL)) {
            return ToolResult.error("Skill「" + skill.name() + "」为沙箱模式，需工作空间为全部权限");
        }
        if (skill.entry() == null || skill.entry().isBlank()) {
            return runInstruction(skill);
        }
        return runScript(skill, ctx, args);
    }

    private ToolResult runInstruction(SkillDef skill) {
        String instructions = loader.readInstructions(skill.id());
        if (instructions == null || instructions.isBlank()) {
            return ToolResult.ok("Skill「" + skill.name() + "」无可执行指令，请按描述直接处理。");
        }
        return ToolResult.ok(instructions);
    }

    private ToolResult runScript(SkillDef skill, ConversationCtx ctx, Map<String, Object> args) {
        String command = renderEntry(skill.entry(), args == null ? Map.of() : args);
        ExecResult result = fileService.exec(ctx.workspaceId(), command).block(TIMEOUT);
        return toResult(result);
    }

    /** 渲染命令模板：{@code {args}} 占位替换为参数 JSON；无占位则尾随参数 JSON。 */
    private String renderEntry(String entry, Map<String, Object> args) {
        String argsJson;
        try {
            argsJson = objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            argsJson = new HashMap<>(args).toString();
        }
        if (entry.contains("{args}")) {
            return entry.replace("{args}", argsJson);
        }
        return entry.trim() + " " + argsJson;
    }

    private ToolResult toResult(ExecResult r) {
        if (r == null) {
            return ToolResult.error("Skill 执行超时");
        }
        if (!r.ok()) {
            if (r.error() != null && r.error().contains("需用户确认")) {
                return ToolResult.suspended(r.error());
            }
            return ToolResult.error(r.error());
        }
        String text = r.content() != null ? r.content()
                : (r.summary() != null ? r.summary() : "ok");
        return ToolResult.ok(text);
    }
}

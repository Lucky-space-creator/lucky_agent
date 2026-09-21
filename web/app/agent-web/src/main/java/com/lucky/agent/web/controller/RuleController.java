package com.lucky.agent.web.controller;

import com.lucky.agent.model.support.prompt.RuleItem;
import com.lucky.agent.model.support.prompt.RuleStore;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 规则管理接口：多条全局规则 / 多条项目规则的读取、保存、启停与删除。
 *
 * <p>承接五项功能优化 §五-5-1：用户可以像管理普通列表一样在 Web 上维护规则，
 * 不再需要「进文件夹改文件」。默认形态为 {@code LUCKY.md} 内 {@code ## 规则名} 分节
 * （Markdown 可直接阅读、diff 友好），需要时切换「高级：多文件目录」模式。</p>
 *
 * <p>磁盘读写为阻塞 I/O，一律 offload 到 {@code boundedElastic}，避免占用 Netty 事件循环线程。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleStore ruleStore;
    private final WorkspaceConfig workspaceConfig;

    public RuleController(RuleStore ruleStore, WorkspaceConfig workspaceConfig) {
        this.ruleStore = ruleStore;
        this.workspaceConfig = workspaceConfig;
    }

    /**
     * 列出规则。
     *
     * @param workspaceId 可选；传入则同时返回该项目下的项目规则
     * @return {@code {storage, global:[...], project:[...], globalPath, projectPath, globalMode, projectMode}}
     */
    @GetMapping
    public Mono<Map<String, Object>> list(@RequestParam(required = false) String workspaceId) {
        return Mono.fromCallable(() -> doList(workspaceId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 保存某一作用域的规则（整体覆盖）。
     *
     * @param body {@code {workspaceId?, scope, rules:[{name,content,enabled,scope}]}}
     * @return {@code {saved:true, count, path}}
     */
    @PostMapping
    public Mono<Map<String, Object>> save(@RequestBody SaveRulesRequest body) {
        return Mono.fromCallable(() -> doSave(body))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 切换某条规则的启用状态（前端开关即时落盘，避免整表提交）。
     *
     * @param body {@code {workspaceId?, scope, index, enabled}}
     */
    @PostMapping("/toggle")
    public Mono<Map<String, Object>> toggle(@RequestBody ToggleRequest body) {
        return Mono.fromCallable(() -> {
            List<RuleItem> items = new ArrayList<>(loadScope(body.workspaceId(), body.scope()));
            if (body.index() < 0 || body.index() >= items.size()) {
                return Map.<String, Object>of("ok", false, "message", "规则不存在");
            }
            RuleItem old = items.get(body.index());
            items.set(body.index(), new RuleItem(old.name(), old.content(), body.enabled(), old.scope()));
            int count = saveScope(body.workspaceId(), body.scope(), items);
            return Map.<String, Object>of("ok", true, "count", count);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 删除某条规则。
     *
     * @param body {@code {workspaceId?, scope, index}}
     */
    @PostMapping("/delete")
    public Mono<Map<String, Object>> delete(@RequestBody ToggleRequest body) {
        return Mono.fromCallable(() -> {
            List<RuleItem> items = new ArrayList<>(loadScope(body.workspaceId(), body.scope()));
            if (body.index() < 0 || body.index() >= items.size()) {
                return Map.<String, Object>of("ok", false, "message", "规则不存在");
            }
            items.remove(body.index());
            int count = saveScope(body.workspaceId(), body.scope(), items);
            return Map.<String, Object>of("ok", true, "count", count);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ------------------------------------------------------------------ 内部实现

    private Map<String, Object> doList(String workspaceId) {
        String wsPath = resolveWorkspacePath(workspaceId).orElse(null);
        List<RuleItem> global = ruleStore.listGlobal();
        List<RuleItem> project = (wsPath == null || wsPath.isBlank())
                ? List.of() : ruleStore.listProject(wsPath);

        Path globalFile = ruleStore.globalFile();
        Path projectFile = wsPath == null ? null : ruleStore.projectFile(wsPath);
        Path globalDir = ruleStore.globalRulesDir();
        Path projectDir = wsPath == null ? null : ruleStore.projectRulesDir(wsPath);

        boolean globalMulti = !Files.isRegularFile(globalFile) && Files.isDirectory(globalDir);
        boolean projectMulti = projectFile != null && !Files.isRegularFile(projectFile)
                && Files.isDirectory(projectDir);

        return Map.of(
                "storage", globalMulti || projectMulti ? "multi-file" : "single-file",
                "global", global,
                "project", project,
                "globalPath", globalFile.toString(),
                "projectPath", projectFile == null ? "" : projectFile.toString(),
                "rulesMode", true);
    }

    private Map<String, Object> doSave(SaveRulesRequest body) throws IOException {
        int count = saveScope(body.workspaceId(), body.scope(), body.rules());
        Path path = "project".equalsIgnoreCase(body.scope())
                ? ruleStore.projectFile(resolveWorkspacePath(body.workspaceId()).orElse(""))
                : ruleStore.globalFile();
        return Map.of("saved", true, "count", count, "path", path.toString());
    }

    /** 读取指定作用域的规则（供 toggle/delete 在此基础上改写）。 */
    private List<RuleItem> loadScope(String workspaceId, String scope) {
        if ("project".equalsIgnoreCase(scope)) {
            String wsPath = resolveWorkspacePath(workspaceId).orElse("");
            return wsPath.isBlank() ? List.of() : ruleStore.listProject(wsPath);
        }
        return ruleStore.listGlobal();
    }

    private int saveScope(String workspaceId, String scope, List<RuleItem> items) throws IOException {
        String wsPath = resolveWorkspacePath(workspaceId).orElse(null);
        return ruleStore.save(wsPath, scope, items);
    }

    /** 工作空间物理路径解析：不存在返回空（项目规则将报错或返回空列表）。 */
    private Optional<String> resolveWorkspacePath(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return Optional.empty();
        }
        return workspaceConfig.physicalPathOf(workspaceId);
    }

    /** 保存请求体：作用域 + 完整规则列表。 */
    public record SaveRulesRequest(String workspaceId, String scope, List<RuleItem> rules) {
    }

    /** 按索引操作请求体（切换启用 / 删除）。 */
    public record ToggleRequest(String workspaceId, String scope, int index, boolean enabled) {
    }
}

package com.lucky.agent.web.controller;

import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.exception.AgentException;
import com.lucky.agent.skill.api.SkillMatcher;
import com.lucky.agent.skill.api.SkillRegistry;
import com.lucky.agent.skill.api.dto.SkillDef;
import com.lucky.agent.skill.api.dto.SkillMatch;
import com.lucky.agent.web.util.PackageImporter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Skill 管理接口：列表/启停/保存/删除/重载/语义匹配预览/本地上传。
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRegistry registry;
    private final SkillMatcher matcher;
    private final WorkspaceDirs dirs;

    public SkillController(SkillRegistry registry, SkillMatcher matcher, WorkspaceDirs dirs) {
        this.registry = registry;
        this.matcher = matcher;
        this.dirs = dirs;
    }

    /** 列出全部 Skill（含禁用）。 */
    @GetMapping
    public List<SkillDef> list() {
        return registry.list();
    }

    /** 注册表版本号（前端轮询感知热插拔）。 */
    @GetMapping("/revision")
    public Map<String, Long> revision() {
        return Map.of("revision", registry.revision());
    }

    /** 语义匹配预览：给定目标文本召回 Top-K Skill。 */
    @GetMapping("/match")
    public List<SkillMatch> match(@RequestParam String query,
                                  @RequestParam(defaultValue = "5") int topK) {
        return matcher.match(registry.list(), query, topK);
    }

    /** 保存（新增或覆盖）用户自定义 Skill；强制来源为 USER（平台只读目录不可写）。 */
    @PostMapping
    public SkillDef save(@RequestBody SkillDef body) {
        SkillDef userDef = new SkillDef(body.id(), body.name(), body.description(), body.triggers(),
                body.type(), body.sandbox(), body.enabled(), body.deps(), body.entry(),
                SkillDef.SOURCE_USER, body.skillMd());
        return registry.save(userDef);
    }

    /**
     * 本地上传 Skill 包（{@code .zip} 压缩包、单个 {@code meta.json} 或单个 {@code SKILL.md}）。
     * 支持单个压缩包内批量导入多个技能；SKILL.md frontmatter 会自动构造元数据（兼容社区标准包）。
     * 上传文件为阻塞 IO，在 boundedElastic 线程执行，避免占用 Reactor 事件循环线程。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> upload(@RequestPart("file") MultipartFile file) {
        return Mono.fromCallable(() -> doUpload(file)).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> doUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AgentException("IMPORT_EMPTY", "请选择要上传的 Skill 包（.zip / meta.json / SKILL.md）");
        }
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory(dirs.tmpDir(), "skill-upload-");
            List<Path> roots = PackageImporter.unpackAll(file, tempDir);
            if (roots.isEmpty()) {
                throw new AgentException("IMPORT_META_MISSING", "上传包内未找到 meta.json 或 SKILL.md");
            }
            List<String> imported = new java.util.ArrayList<>();
            for (Path root : roots) {
                SkillDef def = registry.importFrom(root);
                imported.add(def.id());
            }
            return Map.of("imported", imported, "count", imported.size());
        } catch (IOException e) {
            throw new AgentException("IMPORT_IO_ERROR", "上传文件处理失败", e);
        } finally {
            PackageImporter.deleteQuietly(tempDir);
        }
    }

    /**
     * 从本机目录导入 Skill（E15）：目录名即 id，目录内须含 {@code meta.json} 或 {@code SKILL.md}。
     * 目录选择由本机后端唤起原生弹框（浏览器无法直接访问本机文件系统）。
     */
    @PostMapping("/import-dir")
    public Mono<SkillDef> importDir(@RequestBody(required = false) Map<String, String> body) {
        return Mono.fromCallable(() -> doImportDir(body)).subscribeOn(Schedulers.boundedElastic());
    }

    private SkillDef doImportDir(Map<String, String> body) {
        String path = body == null ? null : body.get("path");
        if (path == null || path.isBlank()) {
            throw new AgentException("IMPORT_DIR_EMPTY", "缺少要导入的目录路径");
        }
        return registry.importFromDir(Path.of(path));
    }

    /** 启用/禁用某 Skill。 */
    @PostMapping("/{id}/enabled")
    public Map<String, Boolean> setEnabled(@PathVariable String id, @RequestBody Map<String, Boolean> body) {
        registry.setEnabled(id, Boolean.TRUE.equals(body.get("enabled")));
        return Map.of("enabled", registry.find(id).map(SkillDef::enabled).orElse(false));
    }

    /** 删除用户自定义 Skill（平台预置返回 false）。 */
    @DeleteMapping("/{id}")
    public Map<String, Boolean> remove(@PathVariable String id) {
        return Map.of("removed", registry.remove(id));
    }

    /** 手动触发重载（热插拔）。 */
    @PostMapping("/reload")
    public Map<String, Integer> reload() {
        registry.reload();
        return Map.of("count", registry.list().size());
    }

    /** 单个 Skill 详情（便于前端编辑）。 */
    @GetMapping("/{id}")
    public Optional<SkillDef> detail(@PathVariable String id) {
        return registry.find(id);
    }
}

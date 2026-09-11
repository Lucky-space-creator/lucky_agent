package com.lucky.agent.web.controller;

import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.model.api.dto.AgentSettings;
import com.lucky.agent.model.config.ModelConfigStore;
import com.lucky.agent.web.util.NativeDirectoryChooser;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 系统本地接口：经操作系统打开本机配置文件（{@code settings.json}）、唤起本机目录选择框。
 */
@Slf4j
@RestController
@RequestMapping("/api/system")
public class SystemConfigController {

    /** 目录选择框最长等待时间（用户长时间不操作则按取消处理，避免请求悬挂）。 */
    private static final Duration PICK_TIMEOUT = Duration.ofMinutes(10);

    private final ModelConfigStore settingsStore;
    private final WorkspaceDirs dirs;
    private final WorkspaceConfig workspaceConfig;

    public SystemConfigController(ModelConfigStore settingsStore, WorkspaceDirs dirs,
                                  WorkspaceConfig workspaceConfig) {
        this.settingsStore = settingsStore;
        this.dirs = dirs;
        this.workspaceConfig = workspaceConfig;
    }

    /** 打开框架配置文件（{@code <frameworkRoot>/settings.json}），返回真实路径。 */
    @PostMapping("/open-settings-file")
    public Map<String, Object> openSettingsFile() {
        Path target = settingsStore.storeFile();
        if (!Files.isRegularFile(target)) {
            settingsStore.save(AgentSettings.empty());
        }
        boolean opened = openInOs(target);
        return Map.of("opened", opened, "path", target.toString());
    }

    /**
     * 打开规则文件（两级 LUCKY.md）：无 {@code workspaceId} 打开全局规则
     * （{@code <frameworkRoot>/LUCKY.md}），有则打开该工作空间的项目规则
     * （{@code <workspacePath>/LUCKY.md}）。文件不存在时提示用户保存后自动生成。
     */
    @PostMapping("/open-rules-file")
    public Map<String, Object> openRulesFile(@RequestBody(required = false) Map<String, String> body) {
        String workspaceId = body == null ? null : body.get("workspaceId");
        Path target;
        if (workspaceId == null || workspaceId.isBlank()) {
            target = dirs.frameworkRoot().resolve("LUCKY.md");
        } else {
            Optional<String> path = workspaceConfig.physicalPathOf(workspaceId);
            if (path.isEmpty()) {
                return Map.of("opened", false, "path", "", "message", "工作空间不存在");
            }
            target = Path.of(path.get()).resolve("LUCKY.md");
        }
        if (!Files.isRegularFile(target)) {
            return Map.of("opened", false, "path", target.toString(), "message", "规则文件尚不存在（保存后自动生成）");
        }
        boolean opened = openInOs(target);
        return Map.of("opened", opened, "path", target.toString());
    }

    /**
     * 唤起本机原生目录选择框，返回选中路径。
     *
     * <p>浏览器无法直接访问本机文件系统，路径一律由后端代选（用户手工输入仅作兜底）。
     * 弹框为阻塞式模态操作，必须切到 boundedElastic 线程池，禁止占用事件循环线程。</p>
     *
     * @param body 可选 {@code startPath}（初始目录）与 {@code title}（对话框标题）
     * @return {@code {path, cancelled}}：cancelled 为 true 表示用户取消或超时
     */
    @PostMapping("/pick-directory")
    public Mono<Map<String, Object>> pickDirectory(@RequestBody(required = false) Map<String, String> body) {
        String startPath = body == null ? null : body.get("startPath");
        String title = body == null ? null : body.get("title");
        return Mono.fromCallable(() -> NativeDirectoryChooser.choose(startPath, title))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(PICK_TIMEOUT, Mono.just(Optional.<String>empty()))
                .map(choice -> {
                    if (choice.isPresent()) {
                        return Map.<String, Object>of("path", choice.get(), "cancelled", false);
                    }
                    return Map.<String, Object>of("path", "", "cancelled", true, "message", "已取消选择");
                });
    }

    /**
     * 浏览本机目录，返回子目录列表（供前端目录浏览器逐层导航，替代老式原生对话框）。
     *
     * <p>本机单机场景，仅列出子目录（不含文件），返回 {@code path} 与其下的 {@code dirs}。
     * 目录读取为阻塞 IO，跑在 boundedElastic。</p>
     *
     * @param path 要浏览的目录绝对路径；为空则返回用户主目录
     * @return {@code {path, parent, dirs:[{name, path}]}}；目录不存在时 dirs 为空
     */
    @GetMapping("/browse")
    public Mono<Map<String, Object>> browse(@RequestParam(required = false) String path) {
        return Mono.fromCallable(() -> doBrowse(path))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> doBrowse(String path) {
        Path current = resolveBrowsePath(path);
        List<Map<String, String>> dirs = new ArrayList<>();
        if (Files.isDirectory(current)) {
            try (var stream = Files.list(current)) {
                stream.filter(Files::isDirectory)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                        .forEach(p -> dirs.add(Map.of(
                                "name", p.getFileName().toString(),
                                "path", p.toAbsolutePath().normalize().toString())));
            } catch (IOException e) {
                log.warn("浏览目录失败：{}", current, e);
            }
        }
        Path parent = current.getParent();
        return Map.of(
                "path", current.toAbsolutePath().normalize().toString(),
                "parent", parent == null ? null : parent.toAbsolutePath().normalize().toString(),
                "dirs", dirs);
    }

    /** 解析浏览起点：空则用户主目录；非绝对路径拒绝（防越界），不存在则回退主目录。 */
    private Path resolveBrowsePath(String path) {
        if (path == null || path.isBlank()) {
            return Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        }
        Path p = Path.of(path.trim()).toAbsolutePath().normalize();
        return Files.isDirectory(p) ? p : Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
    }

    /** 调用系统默认方式打开文件（Windows 用 start 调默认程序，macOS/Linux 用系统默认）。 */
    private boolean openInOs(Path target) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("cmd.exe", "/c", "start", "", target.toString()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", target.toString()).start();
            } else {
                new ProcessBuilder("xdg-open", target.toString()).start();
            }
            return true;
        } catch (IOException e) {
            log.warn("打开配置文件失败：{}", target, e);
            return false;
        }
    }
}

package com.lucky.agent.common.constant;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Agent 必备目录规范（§3.0）集中定义。
 *
 * <p>全部必备目录统一位于单一根 {@code <user.home>/.lucky_agent/} 下，根路径为 Agent 必备目录、
 * 硬编码不可更改（不支持配置覆盖）。子目录一律不带点前缀（config/ memory/ platform/ skills/ mcp/ 等），
 * 任何模块写盘必须经此类取路径，从编译期杜绝私自外写。</p>
 *
 * <p>{@link #frameworkRoot()} 与 {@link #workspaceRoot()} 均指向该单根；两个私有 String 字段
 * 仅供单元测试反射注入临时路径。</p>
 */
@Component
@Slf4j
public class WorkspaceDirs {

    /** 配置/权限级别/模型 Key+URL（加密）/账号。 */
    public static final String CONFIG = "config";
    /** 用户记忆 JSONL + 向量索引。 */
    public static final String MEMORY = "memory";
    /** 平台预置记忆（随包，只读）。 */
    public static final String PLATFORM = "platform";
    /** 快照（按 workspaceId 分桶，Phase 2 用）。 */
    public static final String ROLLBACK = "rollback";
    /** 缓存（预留，Phase 2）。 */
    public static final String CACHE = "cache";
    /** 审计 / 模型 trace（仅元数据）。 */
    public static final String LOGS = "logs";
    /** 技能（预留，Phase 3）。 */
    public static final String SKILLS = "skills";
    /** MCP（预留，Phase 3）。 */
    public static final String MCP = "mcp";
    /** 锁文件 + 临时中转。 */
    public static final String TMP = "tmp";
    /** 内置默认工作空间（位于框架根内，前端不展示）。 */
    public static final String WORKSPACE = "workspace";
    /** 回收站（按 workspaceId 分桶）。 */
    public static final String TRASH = "trash";
    /** 执行臂程序与运行态。 */
    public static final String AGENT = "agent";

    /**
     * 每个工作空间物理路径下的「用户可见、可编辑」配置子树（非隐藏，可直接在文件管理器查看与修改）。
     * 单根化后默认工作空间即落在 {@code <root>/} 下，其子树与全局必备目录重合；
     * 额外工作空间则在各自路径下按此结构初始化。各模块读取时可优先用工作空间内的覆盖项。
     */
    /** 用户自定义技能（可见）。 */
    public static final String WS_SKILLS = "skills";
    /** 用户自建 MCP 配置（可见）。 */
    public static final String WS_MCP = "mcp";
    /** 用户记忆（可见，JSONL）。 */
    public static final String WS_MEMORY = "memory";
    /** 工作空间配置（可见，含 account/workspaces 等）。 */
    public static final String WS_CONFIG = "config";

    /** 测试注入口（反射赋值）；生产保持 null，走硬编码默认值。 */
    private String frameworkRoot;
    /** 测试注入口（反射赋值）；生产保持 null，走硬编码默认值。 */
    private String workspaceRoot;

    private Path frameworkRootPath;
    private Path workspaceRootPath;

    /**
     * 启动即解析根路径并扫描/创建必备目录：此方法在 Spring 依赖装配阶段执行，
     * 早于 skill/mcp/model 等注册中心扫描，保证注册中心首次读取即可命中已建目录。
     * 根路径为 Agent 必备目录，生产固定默认值，不支持配置更改。
     */
    @PostConstruct
    public void init() {
        this.frameworkRootPath = resolveRoot(frameworkRoot, defaultRoot());
        this.workspaceRootPath = resolveRoot(workspaceRoot, defaultRoot());
        ensureDirectoryStructure();
    }

    /** 单根默认值：{@code <user.home>/.lucky_agent}（Agent 必备目录，不可更改）。 */
    private String defaultRoot() {
        return System.getProperty("user.home", ".") + File.separator + ".lucky_agent";
    }

    /** 测试注入的路径值非空则优先，否则回退硬编码默认值。 */
    private Path resolveRoot(String configured, String defaultPath) {
        return (configured == null || configured.isBlank())
                ? Paths.get(defaultPath)
                : Paths.get(configured);
    }

    /** 单根（Agent 必备目录）：{@code <user.home>/.lucky_agent}。 */
    public Path frameworkRoot() {
        return frameworkRootPath;
    }

    /** 单根（Agent 必备目录）：{@code <user.home>/.lucky_agent}。 */
    public Path workspaceRoot() {
        return workspaceRootPath;
    }

    /**
     * 内置默认工作空间路径：{@code <user.home>/.lucky_agent/workspace}。
     *
     * <p>Agent 自身内容（config/ memory/ skills/ logs/ …）一律留在框架根，
     * 用户产物只落在默认工作空间或用户自选目录下，二者不再混写（D4/D24）。
     * 该工作空间为内置兜底项，前端不展示、不可删除。</p>
     */
    public Path defaultWorkspaceRoot() {
        return frameworkRootPath.resolve(WORKSPACE);
    }

    public Path configDir() {
        return frameworkRootPath.resolve(CONFIG);
    }

    public Path memoryDir() {
        return frameworkRootPath.resolve(MEMORY);
    }

    public Path platformDir() {
        return frameworkRootPath.resolve(PLATFORM);
    }

    public Path rollbackDir() {
        return frameworkRootPath.resolve(ROLLBACK);
    }

    public Path cacheDir() {
        return frameworkRootPath.resolve(CACHE);
    }

    public Path logsDir() {
        return frameworkRootPath.resolve(LOGS);
    }

    public Path skillsDir() {
        return frameworkRootPath.resolve(SKILLS);
    }

    public Path mcpDir() {
        return frameworkRootPath.resolve(MCP);
    }

    public Path tmpDir() {
        return frameworkRootPath.resolve(TMP);
    }

    public Path trashDir() {
        return frameworkRootPath.resolve(TRASH);
    }

    public Path agentDir() {
        return frameworkRootPath.resolve(AGENT);
    }

    /**
     * 扫描并创建全部必备目录（幂等）：缺失则自动创建，已存在则统计直接子条目数并输出汇总。
     * 磁盘满等 IO 异常抛出 {@link IllegalStateException} 交由上层提示（必备目录不可缺失）。
     */
    public void ensureDirectoryStructure() {
        Set<Path> dirs = new LinkedHashSet<>(List.of(
                frameworkRootPath, workspaceRootPath, defaultWorkspaceRoot(),
                configDir(), memoryDir(), platformDir(), rollbackDir(), cacheDir(),
                logsDir(), skillsDir(), mcpDir(), tmpDir(), trashDir(), agentDir()));
        List<String> created = new ArrayList<>();
        List<String> existing = new ArrayList<>();
        for (Path dir : dirs) {
            boolean existed = Files.isDirectory(dir);
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new IllegalStateException("工作空间所在磁盘已满，无法执行文件操作：" + dir, e);
            }
            if (existed) {
                existing.add(dir.getFileName() + "(" + countChildren(dir) + ")");
            } else {
                created.add(dir.getFileName().toString());
            }
        }
        log.info("框架必备目录扫描完成：root={}", workspaceRootPath);
        log.info("已存在 {} 个：{}", existing.size(), String.join(", ", existing));
        log.info("已创建 {} 个：{}", created.size(), String.join(", ", created));
    }

    /** 统计目录直接子条目数（供启动汇总）；读取失败按 0 处理。 */
    private long countChildren(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.count();
        } catch (IOException e) {
            log.warn("扫描目录内容失败（按空处理）：{}", dir, e);
            return 0;
        }
    }

    /**
     * 在某工作空间物理路径下创建「用户可见、可编辑」的配置子树。
     *
     * <p>生成的目录（均位于 {@code workspacePath/} 下，非隐藏，用户可在文件管理器直接查看修改）：</p>
     * <ul>
     *     <li>{@code skills/} — 用户自定义技能</li>
     *     <li>{@code mcp/} — 用户自建 MCP 配置</li>
     *     <li>{@code memory/} — 用户记忆（JSONL）</li>
     *     <li>{@code config/} — 账号/工作空间配置</li>
     * </ul>
     *
     * <p>幂等：已存在则跳过，不覆盖用户已有内容。</p>
     */
    public void ensureWorkspaceStructure(Path workspacePath) {
        Path wsRoot = workspacePath.toAbsolutePath().normalize();
        List<Path> dirs = List.of(
                wsRoot.resolve(WS_SKILLS),
                wsRoot.resolve(WS_MCP),
                wsRoot.resolve(WS_MEMORY),
                wsRoot.resolve(WS_CONFIG));
        for (Path dir : dirs) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                log.warn("创建工作空间可见子目录失败（可忽略）：{}", dir, e);
            }
        }
    }
}

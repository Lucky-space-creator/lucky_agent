package com.lucky.agent.web.workflow.adapter;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.core.util.gateway.ToolGateway;
import com.lucky.agent.workspace.api.WorkspaceManager;
import com.lucky.agent.workspace.api.dto.Workspace;
import com.lucky.agent.workflow.adapter.ToolAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具节点适配器（宿主实现）：把工作流的 TOOL 节点桥接到 {@link ToolGateway}。
 *
 * <p>工具集由 {@code ToolGateway.buildTools(workspaceId)} 聚合（file 工具 + skill/mcp 等 ToolSource），
 * 因此必须提供一个可用的 {@code workspaceId}，否则一律返回「未知工具」。
 * 由于 {@link ToolAdapter#call(String, Map)} 契约未携带工作空间（工作流模块刻意不依赖会话概念），
 * 这里由宿主侧解析<b>默认工作空间</b>：优先内置工作空间，其次列表首位，并缓存解析结果。</p>
 *
 * <p><b>已知限制（需后续收口）：</b>合成上下文的 {@code sessionId} 为固定值，
 * 故依赖会话态的工具有可能表现异常；且工具执行走的是默认工作空间权限，
 * 无法按「触发工作流的调用方」区分权限。根治需要给 {@code ToolAdapter} 契约补上下文参数
 * （涉及工作流模块接口变更），本期先不动契约，作为已知风险记录。</p>
 */
@Slf4j
public class ToolGatewayToolAdapter implements ToolAdapter {

    /** 工作流工具调用的合成会话标识（无真实会话主体）。 */
    static final String SYNTHETIC_SESSION_ID = "workflow-exec";
    /** 本机单人使用，用户标识固定。 */
    static final String LOCAL_USER_ID = "local";

    private final ToolGateway toolGateway;
    private final ObjectProvider<WorkspaceManager> workspaceManagerProvider;

    /** 已解析的默认工作空间 id（解析一次后复用；为 null 表示尚未解析或解析失败）。 */
    private volatile String defaultWorkspaceId;

    public ToolGatewayToolAdapter(ToolGateway toolGateway,
                                  ObjectProvider<WorkspaceManager> workspaceManagerProvider) {
        this.toolGateway = toolGateway;
        this.workspaceManagerProvider = workspaceManagerProvider;
    }

    @Override
    public Map<String, Object> call(String toolName, Map<String, Object> args) {
        if (toolName == null || toolName.isBlank()) {
            return Map.of("ok", false, "error", "工具名为空");
        }
        String workspaceId = resolveWorkspaceId();
        if (workspaceId == null) {
            return Map.of("ok", false, "error", "未找到可用工作空间，无法解析工具集");
        }

        ConversationCtx ctx = ConversationCtx.builder()
                .sessionRef(SessionRef.of(SYNTHETIC_SESSION_ID, LOCAL_USER_ID, workspaceId))
                .phase(Phase.ACT)
                .permissionLevel(PermissionLevel.FULL)
                .goal("workflow-tool:" + toolName)
                .build();

        ToolResult result;
        try {
            result = toolGateway.dispatch(toolName, args == null ? Map.of() : args, ctx);
        } catch (Exception e) {
            // ToolGateway 内部本应「错误即数据」，但权限裁决等链路仍可能抛出；此处兜底为可读失败
            String message = (e.getMessage() == null) ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("工作流工具调用异常：tool={} - {}", toolName, message);
            return Map.of("ok", false, "error", "工具调用异常: " + message);
        }
        return toVariableMap(result);
    }

    /**
     * 把 {@link ToolResult} 摊平为变量作用域友好的结构。
     * <p>{@code ok}/{@code error}/{@code suspended} 为保留键（先写入），
     * 其后才摊平 {@code data} 中的 Map 条目，故业务数据无法覆盖保留键。</p>
     */
    private Map<String, Object> toVariableMap(ToolResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        if (result.suspended()) {
            out.put("suspended", true);
        }
        if (result.error() != null) {
            out.put("error", result.error());
        }
        Object data = result.data();
        if (data instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (k != null) {
                    out.put(String.valueOf(k), v);
                }
            });
        } else if (data != null) {
            out.put("data", data);
        }
        return out;
    }

    /**
     * 解析默认工作空间 id：内置工作空间优先，其次创建时间最早者。
     * <p>注意 {@link Workspace} 使用 Lombok {@code @Accessors(fluent = true)}，
     * 访问器为 {@code workspaceId()} / {@code builtin()} 形式，无 {@code getXxx()}。</p>
     */
    private String resolveWorkspaceId() {
        String cached = defaultWorkspaceId;
        if (cached != null) {
            return cached;
        }
        WorkspaceManager manager = workspaceManagerProvider.getIfAvailable();
        if (manager == null) {
            return null;
        }
        try {
            List<Workspace> all = manager.listWorkspaces();
            if (all == null || all.isEmpty()) {
                return null;
            }
            String resolved = all.stream()
                    .filter(Workspace::builtin)
                    .findFirst()
                    .or(() -> all.stream()
                            .min(Comparator.comparing(Workspace::createdAt,
                                    Comparator.nullsLast(Comparator.naturalOrder()))))
                    .map(Workspace::workspaceId)
                    .orElse(null);
            if (resolved != null) {
                this.defaultWorkspaceId = resolved;
            }
            return resolved;
        } catch (Exception e) {
            log.warn("解析默认工作空间失败：{}", e.getMessage());
            return null;
        }
    }
}

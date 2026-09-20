package com.lucky.agent.core.runtime.middleware;

import com.lucky.agent.common.constant.PermissionDecision;
import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.core.runtime.contract.ExecutionResult;
import com.lucky.agent.core.runtime.contract.ToolCall;
import com.lucky.agent.core.runtime.tool.RuntimeTool;
import com.lucky.agent.core.runtime.tool.ToolRegistry;
import com.lucky.agent.permission.service.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 权限中间件：把「工具能否执行」的裁决从工具实现与主循环中抽离，统一在此完成。
 *
 * <p>两道判定，deny 优先：</p>
 * <ol>
 *   <li><b>权限级别闸</b>：工具声明所需最低级别（{@link RuntimeTool#requiredPermission()}），
 *       低于当前工作区级别即拦截并转 <b>ASK</b>（请用户授权），而非静默失败；</li>
 *   <li><b>规则链闸</b>：若工具携带 {@code command} 参数且注入 {@link PermissionService}，
 *       交给 {@code deny > ask > allow} 规则链裁决，DENY 立即阻断、ASK 挂起待确认。</li>
 * </ol>
 *
 * <p>本中间件处于最外层（{@link MiddlewareOrder#PERMISSION}），因此被拒绝的调用不会被重试中间件放大。</p>
 */
public class PermissionMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(PermissionMiddleware.class);

    private final ToolRegistry tools;
    private final PermissionService permissionService;

    public PermissionMiddleware(ToolRegistry tools) {
        this(tools, null);
    }

    public PermissionMiddleware(ToolRegistry tools, PermissionService permissionService) {
        this.tools = tools;
        this.permissionService = permissionService;
    }

    @Override
    public int order() {
        return MiddlewareOrder.PERMISSION;
    }

    @Override
    public void beforeTool(MiddlewareContext ctx) {
        ToolCall call = ctx.toolCall();
        if (call == null) {
            return;
        }

        PermissionLevel current = currentLevel(ctx);
        RuntimeTool tool = tools.find(call.name()).orElse(null);
        if (tool != null) {
            PermissionLevel required = tool.requiredPermission();
            if (required.ordinal() > current.ordinal()) {
                String reason = "工具「" + call.name() + "」需要「" + required.getLabel()
                        + "」权限，当前工作区为「" + current.getLabel() + "」。";
                log.info("[permission] 拒绝并转 ASK: {}", reason);
                ctx.result(ExecutionResult.ask(reason));
                ctx.block("permission-level");
                return;
            }
        }

        // 规则链闸：命令类工具交给 deny > ask > allow 规则链
        String command = call.arg("command");
        if (permissionService != null && command != null && !command.isBlank()) {
            PermissionDecision decision = permissionService.evaluateCommand(command, workspaceId(ctx));
            if (decision == PermissionDecision.DENY) {
                String reason = "规则链拒绝执行命令: " + command;
                log.info("[permission] {}", reason);
                ctx.result(ExecutionResult.failure(reason));
                ctx.block("permission-deny");
            } else if (decision == PermissionDecision.ASK) {
                String reason = "命令需用户确认后执行: " + command;
                log.info("[permission] {}", reason);
                ctx.result(ExecutionResult.ask(reason));
                ctx.block("permission-ask");
            }
        }
    }

    private PermissionLevel currentLevel(MiddlewareContext ctx) {
        ConversationCtx conversation = ctx.runtime().conversation();
        PermissionLevel level = (conversation == null) ? null : conversation.permissionLevel();
        return level == null ? PermissionLevel.defaultValue() : level;
    }

    private String workspaceId(MiddlewareContext ctx) {
        String workspaceId = ctx.runtime().workspaceId();
        return workspaceId == null ? "" : workspaceId;
    }
}

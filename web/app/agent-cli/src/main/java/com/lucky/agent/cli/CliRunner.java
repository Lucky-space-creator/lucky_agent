package com.lucky.agent.cli;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.UserInput;
import com.lucky.agent.core.runtime.ConversationStateManager;
import com.lucky.agent.core.service.ConversationManager;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.dto.Workspace;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * CLI 主循环：终端 REPL，复用同一内核与事件流。
 * <p>每条用户输入经 {@link ConversationManager}（同一内核）提交，运行过程中
 * {@code Flux<AgentEvent>} 由 {@link CliChannel} 终端渲染；事件流直接来自
 * {@link ConversationStateManager}（与 WebChannel 订阅同一源，契约 §5）。
 * ASK 高危确认回传内核（{@code extra.confirm}），与 Web 通道语义完全一致。</p>
 */

@Slf4j
@Component
public class CliRunner implements CommandLineRunner {

    private static final String LOCAL_USER = "local-user";

    private final ConversationManager conversationManager;
    private final ConversationStateManager stateManager;
    private final WorkspaceConfig workspaceConfig;
    private final CliChannel channel = new CliChannel();

    public CliRunner(ConversationManager conversationManager, ConversationStateManager stateManager,
                     WorkspaceConfig workspaceConfig) {
        this.conversationManager = conversationManager;
        this.stateManager = stateManager;
        this.workspaceConfig = workspaceConfig;
    }

    @Override
    public void run(String... args) {
        String workspaceId = resolveWorkspaceId();
        if (workspaceId == null) {
            System.out.println("⚠️ 未配置任何工作空间，请先通过 Web 端或配置文件添加工作空间后再使用 CLI。");
            return;
        }
        String sessionId = UUID.randomUUID().toString();
        SessionRef ref = new SessionRef(sessionId, LOCAL_USER, workspaceId);

        System.out.println("════════════════════════════════════════");
        System.out.println(" Lucky Agent CLI —— 复用同一内核/记忆/执行臂");
        System.out.println(" 工作空间: " + workspaceId);
        System.out.println(" 输入指令开始；输入 exit/quit 退出。");
        System.out.println("════════════════════════════════════════");

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("\n> ");
            String line = scanner.nextLine();
            if (line == null) {
                break;
            }
            String content = line.trim();
            if (content.isEmpty()) {
                continue;
            }
            if ("exit".equalsIgnoreCase(content) || "quit".equalsIgnoreCase(content)) {
                break;
            }
            runOnce(ref, content);
        }
        System.out.println("再见。");
    }

    private void runOnce(SessionRef ref, String content) {
        com.lucky.agent.common.dto.RunResult result = conversationManager.submit(ref, UserInput.of(content)).block();
        if (result != null && result.error() != null && !result.error().isBlank()) {
            System.out.println("  ⚠️ " + result.error());
        }
        // 订阅内核事件流（与 WebChannel 同一源），终端渲染；ASK 确认在 render 时采集
        Flux<AgentEvent> events = stateManager.publisher().stream(ref.sessionId());
        try {
            channel.consume(events, ref).block();
        } catch (Exception e) {
            log.error("CLI 运行失败", e);
            System.out.println("  ⚠️ 运行异常: " + e.getMessage());
        }
        // 若期间出现 ASK，则将用户确认回传内核（一次性放行）
        if (channel.takePendingConfirm()) {
            Map<String, Object> confirm = Map.of("opType", "WRITE", "path", "", "args", Map.of());
            conversationManager.submit(ref, UserInput.of(content, Map.of("confirm", confirm))).block();
        }
    }

    /** 选择第一个已配置工作空间作为默认（CLI 不提供工作区管理 UI）。*/
    private String resolveWorkspaceId() {
        List<Workspace> workspaces = workspaceConfig.listWorkspaces();
        if (workspaces == null || workspaces.isEmpty()) {
            return null;
        }
        // 偏好「全部权限」或「修改文件」级别；否则取第一个
        return workspaces.stream()
                .filter(w -> {
                    PermissionLevel lvl = w.permissionLevel();
                    return lvl == PermissionLevel.FULL || lvl == PermissionLevel.MODIFY;
                })
                .map(Workspace::workspaceId)
                .findFirst()
                .orElseGet(() -> workspaces.get(0).workspaceId());
    }
}

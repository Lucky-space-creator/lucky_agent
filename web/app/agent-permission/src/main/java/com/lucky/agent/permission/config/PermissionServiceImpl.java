package com.lucky.agent.permission.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.PermissionDecision;
import com.lucky.agent.common.contract.PermissionRule;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.permission.api.PermissionService;
import com.lucky.agent.permission.guard.PermissionOverrideStore;
import com.lucky.agent.permission.rules.PermissionEvaluator;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.dto.Workspace;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 权限服务实现：级别 + deny/ask/allow 规则链 + 危险操作识别 → 裁决。
 * <p>框架侧裁决仅作提示；规则集同步到执行臂本地（{@link #currentRules()}），
 * 执行臂重新评估为最终裁决者。</p>
 */

@Slf4j
@Service
public class PermissionServiceImpl implements PermissionService {

    
    private final PermissionEvaluator evaluator;
    private final PermissionOverrideStore overrideStore;
    private final WorkspaceConfig workspaceConfig;
    private final Path rulesFile;
    private final ObjectMapper objectMapper;

    private volatile List<PermissionRule> rules;

    public PermissionServiceImpl(PermissionEvaluator evaluator, PermissionOverrideStore overrideStore,
                                 WorkspaceConfig workspaceConfig, Path permissionRulesFile,
                                 ObjectMapper objectMapper, List<PermissionRule> defaultPermissionRules) {
        this.evaluator = evaluator;
        this.overrideStore = overrideStore;
        this.workspaceConfig = workspaceConfig;
        this.rulesFile = permissionRulesFile;
        this.objectMapper = objectMapper;
        this.rules = new ArrayList<>(defaultPermissionRules);
    }

    @Override
    public PermissionDecision evaluateFileOp(FileOp op, String workspaceId) {
        Workspace workspace = workspaceConfig.getWorkspace(workspaceId).orElse(null);
        if (workspace == null) {
            return PermissionDecision.DENY;
        }
        return evaluator.evaluate(op, workspace.permissionLevel(), Path.of(workspace.path()), rules);
    }

    @Override
    public void allowOnce(FileOp op) {
        overrideStore.allow(op);
        overrideStore.sweep();
        log.info("用户确认放行高危操作：{} {} {}", op.opType(), op.workspaceId(), op.path());
    }

    @Override
    public PermissionDecision evaluateCommand(String command, String workspaceId) {
        Workspace workspace = workspaceConfig.getWorkspace(workspaceId).orElse(null);
        if (workspace == null) {
            return PermissionDecision.DENY;
        }
        return evaluator.evaluateCommand(command, workspace.permissionLevel(), rules);
    }

    @Override
    public List<PermissionRule> loadRules() {
        if (!Files.exists(rulesFile)) {
            return List.of();
        }
        try {
            String json = Files.readString(rulesFile, StandardCharsets.UTF_8);
            List<PermissionRule> loaded = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, PermissionRule.class));
            return loaded == null ? List.of() : loaded;
        } catch (Exception e) {
            log.error("读取权限规则失败：{}", rulesFile, e);
            return List.of();
        }
    }

    @Override
    public synchronized void saveRules(List<PermissionRule> rules) {
        this.rules = new ArrayList<>(rules == null ? List.of() : rules);
        try {
            Files.createDirectories(rulesFile.getParent());
            byte[] bytes = objectMapper.writeValueAsBytes(this.rules);
            Files.write(rulesFile, bytes);
            log.info("权限规则已保存，共 {} 条", this.rules.size());
        } catch (Exception e) {
            log.error("保存权限规则失败：{}", rulesFile, e);
        }
    }

    @Override
    public List<PermissionRule> currentRules() {
        return List.copyOf(rules);
    }
}

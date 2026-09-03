package com.lucky.agent.permission.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.contract.PermissionRule;
import com.lucky.agent.permission.guard.AuditLogger;
import com.lucky.agent.permission.guard.DangerousOpDetector;
import com.lucky.agent.permission.guard.PermissionOverrideStore;
import com.lucky.agent.permission.rules.PermissionChain;
import com.lucky.agent.permission.rules.PermissionEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * agent-permission 配置：提供规则链 / 危险识别 / 审计 Bean。
 *
 * <p>规则集默认来自 {@code <frameworkRoot>/.config/permission-rules.json}，加载进内存供裁决与执行臂同步。</p>
 */
@Configuration
public class PermissionModuleConfig {

    private static final String RULES_FILE = "permission-rules.json";

    /** deny > ask > allow 规则链。 */
    @Bean
    public PermissionChain permissionChain() {
        return new PermissionChain();
    }

    /** 权限裁决核心（框架侧与执行臂共用）。 */
    @Bean
    public PermissionEvaluator permissionEvaluator(PermissionChain permissionChain,
                                                   DangerousOpDetector dangerousOpDetector,
                                                   PermissionOverrideStore permissionOverrideStore) {
        return new PermissionEvaluator(permissionChain, dangerousOpDetector, permissionOverrideStore);
    }

    /** 危险操作识别。 */
    @Bean
    public DangerousOpDetector dangerousOpDetector() {
        return new DangerousOpDetector();
    }

    /** 权限一次性放行存储（用户确认后短 TTL 内放行）。 */
    @Bean
    public PermissionOverrideStore permissionOverrideStore() {
        return new PermissionOverrideStore();
    }

    /** 审计日志（仅元数据，.logs/）。 */
    @Bean
    public AuditLogger auditLogger(WorkspaceDirs dirs, ObjectMapper objectMapper) {
        return new AuditLogger(dirs.logsDir(), objectMapper);
    }

    /** 规则文件路径（供 PermissionService 读写）。 */
    @Bean
    public Path permissionRulesFile(WorkspaceDirs dirs) {
        return dirs.configDir().resolve(RULES_FILE);
    }

    /** 加载默认规则（文件不存在则返回内置安全基线，保证「开箱即有 deny 兜底」，不再空规则裸奔）。 */
    @Bean
    public List<PermissionRule> defaultPermissionRules(ObjectMapper objectMapper, Path permissionRulesFile) {
        if (!Files.exists(permissionRulesFile)) {
            return builtinRules();
        }
        try {
            String json = Files.readString(permissionRulesFile, StandardCharsets.UTF_8);
            List<PermissionRule> rules = objectMapper.readValue(
                    json, objectMapper.getTypeFactory().constructCollectionType(List.class, PermissionRule.class));
            return rules == null || rules.isEmpty() ? builtinRules() : rules;
        } catch (IOException e) {
            throw new IllegalStateException("加载权限规则失败：" + permissionRulesFile, e);
        }
    }

    /**
     * 内置安全基线（deny-wins 的兜底规则，优先级最高）。
     *
     * <p>规则链按 priority 升序评估、DENY 永远胜出；给最高 priority 使其最先被评估，
     * 一旦命中即拒绝，不依赖用户是否配置规则。文件操作规则为 PATH 型、anchor=home，
     * 锚定在用户主目录（Agent 产物所在），敏感路径一律拒绝；命令执行规则为 COMMAND 型。</p>
     */
    private static List<PermissionRule> builtinRules() {
        PermissionRule sensitivePaths = new PermissionRule()
                .id("builtin.deny-sensitive-paths")
                .priority(1000)
                .type(PermissionRule.RuleType.PATH)
                .matcher(new PermissionRule.Matcher("regex:(^|[/\\\\])(\\.ssh|\\.aws|\\.kube|\\.gnupg|\\.git/|\\.env)[/\\\\]?", "home"))
                .action(PermissionRule.RuleAction.DENY)
                .reason("内置基线：禁止访问本机敏感配置/密钥目录");

        PermissionRule destructiveCmds = new PermissionRule()
                .id("builtin.deny-destructive-cmds")
                .priority(1000)
                .type(PermissionRule.RuleType.COMMAND)
                .matcher(new PermissionRule.Matcher("regex:(rm\\s+-rf|mkfs|dd\\s+if=|:(){|chmod\\s+-R\\s+777\\s+/|>\\s*/dev/sd)", "relative"))
                .action(PermissionRule.RuleAction.DENY)
                .reason("内置基线：禁止危险破坏性命令");

        return List.of(sensitivePaths, destructiveCmds);
    }
}

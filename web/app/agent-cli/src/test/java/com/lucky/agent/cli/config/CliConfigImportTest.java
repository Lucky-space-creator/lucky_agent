package com.lucky.agent.cli.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置单一事实源护栏（缺陷 D3 的回归守卫）。
 *
 * <p><b>守的是什么：</b>Web 与 CLI 共用同一内核（决策 D11/D20），因此
 * {@code model / executor / core / skill / mcp / memory} 这组业务键在两条通道上
 * <b>必须解析到同一份值</b>。此前这些键只写在 {@code agent-web/application.yml} 里，
 * 而 {@code agent-cli} 没有 application.yml，只能落到 {@code @ConfigurationProperties}
 * 的代码兜底默认值，于是同一个内核在两个通道上行为不同。已实测到的两处分叉：
 * <ul>
 *     <li>{@code executor.sandbox-enabled}：Web {@code true} / CLI {@code false}
 *         ⇒ CLI 下任何命令都被拦（{@code !命令} 100% 失败）；</li>
 *     <li>{@code core.orchestrator-max-iterations}：Web {@code 5} / CLI {@code 3}
 *         ⇒ 同一任务在 CLI 上更早被安全阀强制结束（用户可见）。</li>
 * </ul>
 *
 * <p><b>为什么用真实配置数据阶段而不是直接读文件：</b>本测试走
 * {@link ConfigDataApplicationContextInitializer}，即真正执行 Spring Boot 的
 * 「配置数据」流程（含 {@code spring.config.import} 与优先级规则）。
 * 因此它同时验证了三件事：共享文件在 classpath 上、import 语法正确、
 * 且 import 的结果确实进入了 Environment —— 这正是引入本方案时的 PoC 关注点。</p>
 */
class CliConfigImportTest {

    /** 只做配置数据加载，不装配任何 Bean：快，且不会被业务组件干扰。 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    /** 必须由 {@code lucky-defaults.yml} 提供、且两条通道取值一致的业务键。 */
    private static final List<String> SHARED_KEYS_MUST_MATCH_WEB = List.of(
            "executor.sandbox-enabled",
            "executor.boundary-strict",
            "executor.cmd-timeout-sec",
            "core.orchestrator-max-iterations",
            "model.context-threshold",
            "memory.md.enabled");

    @Test
    @DisplayName("CLI 能通过 spring.config.import 取到共享业务配置（不再落到代码兜底默认值）")
    void importsSharedBusinessDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var env = context.getEnvironment();

            // 沙箱开关：修复前 CLI 落代码兜底 false，导致执行臂命令全部被拦
            assertThat(env.getProperty("executor.sandbox-enabled"))
                    .as("executor.sandbox-enabled 必须来自共享配置且与 Web 一致")
                    .isEqualTo("true");

            // 安全阀轮数：修复前 Web=5 / CLI=3
            assertThat(env.getProperty("core.orchestrator-max-iterations"))
                    .as("core.orchestrator-max-iterations 必须与 Web 统一为 5")
                    .isEqualTo("5");

            for (String key : SHARED_KEYS_MUST_MATCH_WEB) {
                assertThat(env.getProperty(key))
                        .as("共享业务键 %s 必须能从 lucky-defaults.yml 解析到", key)
                        .isNotNull();
            }
        });
    }

    @Test
    @DisplayName("CLI 自己的 application.yml 不得重复声明共享业务键（否则共享值被静默顶掉）")
    void cliOwnConfigMustNotRedeclareSharedKeys() throws Exception {
        String yaml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(in)
                    .as("agent-cli 必须提供 application.yml —— spring.config.import 只能写在配置文件里，"
                            + "而它正是导入共享业务配置的唯一入口")
                    .isNotNull();
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Set<String> topLevelKeys = topLevelKeys(yaml);
        assertThat(topLevelKeys)
                .as("agent-cli/application.yml 只能声明通道专属键；"
                        + "出现共享业务键会让 lucky-defaults.yml 里的值被静默顶掉，漂移就此复发。"
                        + "当前顶层键：%s", topLevelKeys)
                .doesNotContainAnyElementsOf(
                        List.of("executor", "core", "model", "memory", "skill", "mcp"));
    }

    /** 取「顶格」的键名（第 0 列、形如 {@code key:}），忽略缩进层级与注释。 */
    private static Set<String> topLevelKeys(String yaml) {
        return yaml.lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#") && !line.startsWith(" "))
                .filter(line -> line.endsWith(":") || line.contains(":"))
                .map(line -> line.substring(0, line.indexOf(':')).trim())
                .filter(key -> !key.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
package com.lucky.agent.model.support.prompt;

import com.lucky.agent.common.constant.WorkspaceDirs;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 基座提示词存储：{@code <frameworkRoot>/LUCKY.md}（Agent 必备目录根下，用户可见可直接编辑）。
 *
 * <p>基座层是 system prompt 的第一层、属静态可缓存前缀（D17），原本硬编码在
 * {@code ReactEngine} 里。按「本机优先、用户可改」原则改为落盘到框架根目录的
 * {@code LUCKY.md}：用户改完保存即生效，无需重启，也无需重新打包。</p>
 *
 * <p>读写策略：</p>
 * <ul>
 *     <li>文件不存在（首次启动）→ 写入默认模板，让用户一眼看到可编辑的入口；</li>
 *     <li>每次取用实时读文件（文件极小，一次 {@code engine.run} 只读一次），
 *         不做进程内缓存，避免改完不生效的困惑；</li>
 *     <li>文件为空或读取失败 → 回退 {@link #DEFAULT_BASE_PROMPT} 并告警，链路不因文件问题中断，
 *         且不覆盖用户文件（用户可能正在编辑）。</li>
 * </ul>
 */
@Slf4j
public class BasePromptStore {

    /** 基座提示词文件名（框架根下，非隐藏子目录，便于用户查找编辑）。 */
    public static final String FILE_NAME = "LUCKY.md";

    /** 内置兜底基座提示词（文件为空 / 读取失败时使用，与默认模板的生效内容一致）。 */
    public static final String DEFAULT_BASE_PROMPT =
            "你是 lucky_agent 的智能体助手，所有文件操作限制在工作空间内，禁止外传用户数据。"
                    + "严格遵守用户指令：只执行用户明确要求的内容，不做用户未要求的额外操作，任务完成后立即总结并停止；"
                    + "目标不明确时先询问。模糊输入必须确认：用户只发单个字/单个数字/极短内容且有歧义时，禁止自行猜测为选项执行，须先确认意图。"
                    + "简单任务直接输出结构化总结；复杂任务分步执行、每步阶段性汇报，最后给总结。";

    /** 首次启动时写入的默认模板（说明部分用 HTML 注释，不干扰模型理解）。 */
    private static final String DEFAULT_TEMPLATE = """
            <!-- LUCKY Agent 基座提示词（system prompt 第一层，静态可缓存前缀）
                 本文件位于 Agent 必备目录根下，每次模型调用前读取，保存后即时生效，无需重启。
                 建议只写恒定身份与硬性规则；阶段指令、权限约束、记忆召回由框架自动附加在后面。 -->

            你是 lucky_agent 的智能体助手，所有文件操作限制在工作空间内，禁止外传用户数据。

            ## 硬性规则
            1. 严格遵守用户指令：只执行用户明确要求的内容，不做用户未要求的额外操作，不擅自扩展任务范围。
            2. 任务目标不明确或存在歧义时，先询问用户，得到确认后再执行。
            3. 模糊输入必须确认：当用户只发送单个字、单个数字或极短内容且可能对应多个解释（如选项编号、历史待办）时，
               禁止自行猜测为一个选项并开始执行，必须先确认用户意图。
            4. 任务完成后立即给出总结并停止，不要继续扩展、不要重复已确认的事项。
            5. 简单任务直接输出结构化总结（结论 + 做了什么 + 关键路径）；复杂任务分步执行、每完成一步先做简短阶段性汇报，最后给【总结】。
            """;

    private final Path file;

    public BasePromptStore(WorkspaceDirs dirs) {
        this.file = dirs.frameworkRoot().resolve(FILE_NAME);
        ensureFileExists();
    }

    /** 包级构造，便于单元测试注入临时文件路径。 */
    BasePromptStore(Path file) {
        this.file = file;
        ensureFileExists();
    }

    /** 基座提示词文件路径（供前端展示「去编辑」入口与日志排查）。 */
    public Path file() {
        return file;
    }

    /**
     * 读取当前生效的基座提示词。
     *
     * @return 文件内容（已去首尾空白）；为空时回退内置默认值
     */
    public String basePrompt() {
        if (!Files.exists(file)) {
            return DEFAULT_BASE_PROMPT;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text == null || text.isBlank()) {
                log.warn("基座提示词文件为空，回退内置默认值：{}", file);
                return DEFAULT_BASE_PROMPT;
            }
            return text.trim();
        } catch (IOException e) {
            log.error("读取基座提示词失败，回退内置默认值：{}", file, e);
            return DEFAULT_BASE_PROMPT;
        }
    }

    /** 首次启动写入默认模板（幂等；写盘失败只告警，不阻断启动）。 */
    private void ensureFileExists() {
        if (Files.exists(file)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, DEFAULT_TEMPLATE, StandardCharsets.UTF_8);
            log.info("已生成基座提示词模板：{}", file);
        } catch (IOException e) {
            log.error("生成基座提示词模板失败（将回退内置默认值）：{}", file, e);
        }
    }
}

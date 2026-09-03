package com.lucky.agent.core.models;

import java.util.List;

/**
 * 可执行计划（结构化 JSON 契约，见 {@code PlanSchema}）。
 *
 * @param goal           目标
 * @param steps          步骤列表
 * @param canAutoExecute 是否可自动执行
 */
public record Plan(String goal, List<PlanStep> steps, boolean canAutoExecute) {

    /**
     * 计划步骤。
     *
     * @param id     步骤序号
     * @param type   步骤类型（file / shell / ask / tool）
     * @param desc   步骤描述
     * @param target 目标路径/对象
     * @param safe   是否安全（false 需用户确认）
     * @param verify 客观校验声明（可为 null，表示该步骤不做客观验证）
     */
    public record PlanStep(int id, String type, String desc, String target, boolean safe, VerifySpec verify) {

        /** 兼容构造：未声明校验方式的步骤（默认不做客观验证）。 */
        public PlanStep(int id, String type, String desc, String target, boolean safe) {
            this(id, type, desc, target, safe, null);
        }
    }

    /**
     * 客观校验声明（流程图 D 节点输入）。
     *
     * <p>PLAN 阶段由模型为每个步骤声明「如何证明这一步做成了」，主回环据此跑客观验证器，
     * 不依赖模型自述完成。未声明的步骤跳过客观验证。</p>
     *
     * @param type     校验类型：file（文件存在/内容断言）| command（校验命令退出码，含构建/测试/状态码/数据库）
     * @param command  校验命令（type=command 时必填，如 {@code mvn -q test}）
     * @param path     待校验文件路径（type=file 时必填，取不到时回退 {@code target}）
     * @param contains 内容断言：文件内容需包含的文本，或命令输出需包含的文本（如期望状态码 200）
     */
    public record VerifySpec(String type, String command, String path, String contains) {

        /** 合法校验类型。 */
        public static final String TYPE_FILE = "file";
        /** 合法校验类型。 */
        public static final String TYPE_COMMAND = "command";

        /** 是否为可执行的客观校验声明（类型合法且参数齐全）。 */
        public boolean actionable() {
            if (type == null || type.isBlank()) {
                return false;
            }
            return switch (type) {
                case TYPE_FILE -> path != null && !path.isBlank();
                case TYPE_COMMAND -> command != null && !command.isBlank();
                default -> false;
            };
        }
    }
}

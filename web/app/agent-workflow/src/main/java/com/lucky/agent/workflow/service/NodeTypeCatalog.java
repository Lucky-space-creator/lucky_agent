package com.lucky.agent.workflow.service;

import com.lucky.agent.workflow.dto.NodeTypeMeta;
import com.lucky.agent.workflow.dto.NodeTypeMeta.ConfigField;

import java.util.List;

/**
 * 节点类型目录：前端画布的唯一事实源。
 *
 * <p><b>维护约束：</b>本目录中的 {@code key} 必须与
 * {@code engine/executors/*NodeExecutor} 里读取的 config 键完全一致，
 * 否则会出现「前端表单填了、引擎读不到」的静默失配。
 * 新增节点类型或改动 config 键时，需同步此处与对应执行器
 * （{@code NodeTypeCatalogTest} 会校验两侧一致性）。</p>
 */
public final class NodeTypeCatalog {

    private NodeTypeCatalog() {
    }

    /** 全部节点类型定义（顺序即前端组件面板的展示顺序）。 */
    public static List<NodeTypeMeta> catalog() {
        return List.of(
                new NodeTypeMeta("START", "开始", "流程入口，透传触发变量",
                        "structure", true, true, List.of()),

                new NodeTypeMeta("END", "结束", "流程出口，汇总产出",
                        "structure", false, true, List.of()),

                new NodeTypeMeta("LLM", "LLM 生成", "单步模型调用（非自主多轮推理）",
                        "model", false, false, List.of(
                        ConfigField.textarea("prompt", "提示词", true,
                                "例如：请根据以下内容生成摘要\\n${content}",
                                "支持 ${变量名} 占位，运行时替换为作用域中的值"),
                        ConfigField.textarea("system", "系统提示", false,
                                "可选，用于设定角色与约束",
                                "为空时不发送 system 消息"))),

                new NodeTypeMeta("TOOL", "工具调用", "调用本机工具（复用工具网关与权限链）",
                        "action", false, false, List.of(
                        ConfigField.text("toolName", "工具名", true,
                                "例如：file.list",
                                "需与工具网关注册名一致；未知工具将返回可读错误"),
                        ConfigField.json("params", "固定参数（JSON 对象）", false,
                                "{\"path\": \"docs\"}",
                                "可选。作为工具入参基线，与上游节点注入的参数合并；同名时以上游注入为准"))),

                new NodeTypeMeta("CONDITION", "条件判断", "对表达式求值并输出布尔结果",
                        "logic", false, false, List.of(
                        ConfigField.textarea("condition", "条件表达式", true,
                                "例如：score >= 80 && status == 'active'",
                                "支持比较、逻辑与括号；空表达式视为恒真。实际分支路由由出边的条件决定"))),

                new NodeTypeMeta("CODE", "命令执行", "在沙箱中执行命令/脚本",
                        "action", false, false, List.of(
                        ConfigField.textarea("command", "命令", true,
                                "例如：echo ${topic}",
                                "支持 ${变量名} 占位；执行需满足沙箱与权限前置条件"),
                        ConfigField.number("timeoutMs", "超时（毫秒）", "0 表示用引擎默认",
                                "超过该时长判定 timedOut", 0),
                        ConfigField.bool("failOnError", "失败即中止流程", "关闭时非零退出码仅记录，不中止", true))),

                new NodeTypeMeta("SUBFLOW", "子流程", "内嵌执行另一个工作流",
                        "composite", false, false, List.of(
                        ConfigField.text("subWorkflowId", "子工作流 ID", true,
                                "例如：wf-daily-report",
                                "被引用的工作流需已存在，否则节点失败")))
        );
    }
}

package com.lucky.agent.core.contract;

/**
 * 计划 JSON Schema 契约（§6.3）。
 *
 * <p>计划产物结构固定：{@code goal, steps[{id,type,desc,target,safe,verify}], canAutoExecute}；
 * 校验器拒绝 {@code safe=false} 且无用户确认的步骤（→ ASK）。</p>
 *
 * <p>{@code verify} 为客观校验声明（流程图 D 节点）：由模型在 PLAN 阶段说明
 * 「如何证明该步骤做成了」，主回环据此跑客观验证器（文件存在性 / 校验命令退出码），
 * 不依赖模型自述完成。省略该字段的步骤跳过客观验证。</p>
 */
public final class PlanSchema {

    /** 计划 JSON Schema（用于 PLAN 产出的结构化校验）。 */
    public static final String JSON_SCHEMA = """
            {
              "type": "object",
              "required": ["goal", "steps", "canAutoExecute"],
              "properties": {
                "goal": { "type": "string" },
                "steps": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["id", "type", "desc", "safe"],
                    "properties": {
                      "id": { "type": "integer" },
                      "type": { "type": "string", "enum": ["file", "shell", "ask", "tool"] },
                      "desc": { "type": "string" },
                      "target": { "type": "string" },
                      "safe": { "type": "boolean" },
                      "verify": {
                        "type": "object",
                        "description": "客观校验声明：如何证明该步骤做成了",
                        "required": ["type"],
                        "properties": {
                          "type": { "type": "string", "enum": ["file", "command"] },
                          "command": { "type": "string", "description": "校验命令，按退出码判定；构建/测试/状态码/数据库查询均适用" },
                          "path": { "type": "string", "description": "待校验文件路径（type=file，省略时取 target）" },
                          "contains": { "type": "string", "description": "文件内容或命令输出需包含的文本（如期望状态码 200）" }
                        }
                      }
                    }
                  }
                },
                "canAutoExecute": { "type": "boolean" },
                "subagents": {
                  "type": "array",
                  "description": "可选：高复杂度独立子问题的隔离子代理声明（框架按需启动，只回摘要；普通拆解放 steps 即可）",
                  "items": {
                    "type": "object",
                    "required": ["id", "name", "task"],
                    "properties": {
                      "id": { "type": "string" },
                      "name": { "type": "string" },
                      "task": { "type": "string" },
                      "tools": { "type": "array", "items": { "type": "string" } },
                      "disallowedTools": { "type": "array", "items": { "type": "string" } },
                      "permissionMode": { "type": "string" },
                      "summaryOnly": { "type": "boolean" }
                    }
                  }
                }
              }
            }
            """;

    private PlanSchema() {
    }
}

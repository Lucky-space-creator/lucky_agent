package com.lucky.agent.model.support.prompt;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 结构化规则项（多条规则体系的原子单位）。
 *
 * <p>默认存储形态为「单文件内 {@code ## 规则名} 分节」：每个分节映射为一个
 * {@code RuleItem}，节标题即 {@link #name}，节正文即 {@link #content}。
 * 通过节标题中的控制标记（{@code [ ]} 前缀表示停用，{@code [P]} 前缀表示项目作用域）
 * 携带「启用状态」与「作用域」两个元信息，从而在不引入第二个索引文件的前提下
 * 保持 {@code LUCKY.md} 的单文件可读性与 diff 友好性。</p>
 *
 * <p>标题控制标记语法（与正文一并存回文件，保持幂等往返）：</p>
 * <ul>
 *     <li>{@code ## [P] 项目编码规范} → 项目作用域、启用</li>
 *     <li>{@code ## [ ] 临时实验规则} → 全局作用域、停用</li>
 *     <li>{@code ## [P][ ] 待定规则} → 项目作用域、停用（标记可组合、顺序无关）</li>
 *     <li>{@code ## 提交规范} → 全局作用域、启用（无标记即默认）</li>
 * </ul>
 */
public record RuleItem(
        /** 规则名（节标题，已剥离控制标记）。 */
        String name,
        /** 规则正文（节内容，不含标题行）。 */
        String content,
        /** 是否启用；停用规则不注入 system prompt。 */
        boolean enabled,
        /** 作用域：{@code global} 或 {@code project}。 */
        String scope) {

    /** 全局作用域标识。 */
    public static final String SCOPE_GLOBAL = "global";
    /** 项目作用域标识。 */
    public static final String SCOPE_PROJECT = "project";

    /** 标题作用域标记（置于标题最前，区分大小写）。 */
    static final String MARK_PROJECT = "[P]";
    /** 标题停用标记。 */
    static final String MARK_DISABLED = "[ ]";

    public RuleItem {
        name = name == null ? "" : name.trim();
        content = content == null ? "" : content;
        scope = SCOPE_PROJECT.equalsIgnoreCase(scope) ? SCOPE_PROJECT : SCOPE_GLOBAL;
    }

    /** 新建一条启用中的全局规则。 */
    public static RuleItem of(String name, String content) {
        return new RuleItem(name, content, true, SCOPE_GLOBAL);
    }

    /** 是否为项目作用域规则。 */
    @JsonIgnore
    public boolean isProject() {
        return SCOPE_PROJECT.equals(scope);
    }

    /** 是否为空规则（无名称且无正文），解析分组标题等噪声时用于过滤。 */
    @JsonIgnore
    public boolean isEmpty() {
        return name.isBlank() && content.isBlank();
    }
}

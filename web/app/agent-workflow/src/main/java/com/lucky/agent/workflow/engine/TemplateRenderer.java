package com.lucky.agent.workflow.engine;

import com.lucky.agent.workflow.domain.VariableScope;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量模板渲染：将 {@code ${path}} 占位符替换为作用域中的变量值。
 * <p>与连接器使用同一套点路径语义（委托 {@link MappingEvaluator}）。</p>
 */
public class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private final MappingEvaluator mappingEvaluator;

    public TemplateRenderer(MappingEvaluator mappingEvaluator) {
        this.mappingEvaluator = mappingEvaluator;
    }

    public String render(String template, VariableScope scope) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            Object value = mappingEvaluator.resolve(matcher.group(1).trim(), scope);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}

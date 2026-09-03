package com.lucky.agent.core.gateway;

import com.lucky.agent.common.api.Tool;
import com.lucky.agent.common.api.ToolSource;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.executor.api.FileService;
import com.lucky.agent.executor.api.tool.FileOpsToolset;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具调用网关（工具注册与分发）。
 * <p>能力模块（executor/skill/mcp）把 {@link Tool} 实现注册进来，本网关为工作空间构建
 * LangChain4j {@link ToolSpecification} 列表供编排引擎使用；模型回调工具时由本网关分发给
 * 对应实现。工具定义只暴露名称/描述/参数 schema，执行不跨模块耦合实现细节。</p>
 * <p>构建工具集时聚合所有 {@link ToolSource} Bean（file 工具 + skill/mcp 等外部工具源）；
 * 注入给模型的是目标感知子集（skill 按 Top-K 召回），分发时用全量列表按名查找。</p>
 */

@Slf4j
@Component
public class ToolGateway {

    private final FileService fileService;
    private final List<ToolSource> toolSources;

    public ToolGateway(FileService fileService, List<ToolSource> toolSources) {
        this.fileService = fileService;
        this.toolSources = toolSources == null ? List.of() : toolSources;
    }

    /**
     * 为工作空间构建工具集（LangChain4j ToolSpecification 列表），目标为空时全量注入。
     *
     * @param workspaceId 工作空间 ID
     * @return 工具定义列表，供 ChatModel 工具调用使用
     */
    public List<ToolSpecification> buildToolSpecifications(String workspaceId) {
        return buildToolSpecifications(workspaceId, null);
    }

    /**
     * 为工作空间按目标语义构建工具集（skill 按 Top-K 召回，非全量注入省 token）。
     *
     * @param workspaceId 工作空间 ID
     * @param goal        当前任务目标（为空时回退全量注入）
     * @return 工具定义列表
     */
    public List<ToolSpecification> buildToolSpecifications(String workspaceId, String goal) {
        List<Tool> tools = buildTools(workspaceId, goal);
        List<ToolSpecification> specs = new ArrayList<>();
        for (Tool tool : tools) {
            specs.add(ToolSpecification.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .parameters(buildParameters())
                    .build());
        }
        log.debug("构建工具集：workspaceId={} tools={}", workspaceId, specs.stream().map(ToolSpecification::name).toList());
        return specs;
    }

    /**
     * 构建全量工具实例（分发用，含全部工具源，goal 为空时各源全量注入）。
     *
     * @param workspaceId 工作空间 ID
     * @return 工具实例列表
     */
    public List<Tool> buildTools(String workspaceId) {
        return buildTools(workspaceId, null);
    }

    /**
     * 构建工具实例：file 工具 + 全部外部工具源（skill/mcp）按目标语义注入。
     *
     * @param workspaceId 工作空间 ID
     * @param goal        当前任务目标（skill 源据此做 Top-K 召回）
     * @return 工具实例列表
     */
    public List<Tool> buildTools(String workspaceId, String goal) {
        List<Tool> tools = new ArrayList<>();
        tools.addAll(new FileOpsToolset(workspaceId, fileService).tools());
        for (ToolSource source : toolSources) {
            try {
                tools.addAll(source.tools(workspaceId, goal));
            } catch (Exception e) {
                log.warn("工具源构建失败：namespace={}", source.namespace(), e);
            }
        }
        return tools;
    }

    /**
     * 程序化分发工具调用（用于 PLAN 阶段干跑 / 子 Agent 流程）。
     *
     * @param toolName 工具名
     * @param args     入参
     * @param ctx      会话上下文
     * @return 工具结果
     */
    public ToolResult dispatch(String toolName, Map<String, Object> args, ConversationCtx ctx) {
        Tool tool = findTool(buildTools(ctx.workspaceId()), toolName);
        if (tool == null) {
            return ToolResult.error("未知工具：" + toolName);
        }
        return execute(tool, ctx, args);
    }

    /**
     * 批量分发一轮工具调用：只读工具并行执行（受注解 readOnly 控制）、写工具串行，
     * 结果按输入顺序返回（调用方按序回灌消息与发布事件）。
     *
     * @param calls 本轮工具调用（名称 + 入参）
     * @param ctx   会话上下文
     * @return 与输入一一对应的工具结果列表
     */
    public List<ToolResult> dispatchAll(List<ToolCall> calls, ConversationCtx ctx) {
        List<Tool> tools = buildTools(ctx.workspaceId());
        ToolResult[] results = new ToolResult[calls.size()];
        List<Mono<IndexedResult>> parallel = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            Tool tool = findTool(tools, call.name());
            if (tool == null) {
                results[i] = ToolResult.error("未知工具：" + call.name());
            } else if (tool.annotations().readOnly()) {
                // 只读工具：并行执行，按完成顺序放回对应索引
                int idx = i;
                parallel.add(Mono.fromCallable(() -> new IndexedResult(idx, execute(tool, ctx, call.args())))
                        .subscribeOn(Schedulers.boundedElastic()));
            } else {
                // 写工具：串行执行
                results[i] = execute(tool, ctx, call.args());
            }
        }
        if (!parallel.isEmpty()) {
            List<IndexedResult> done = Flux.merge(parallel).collectList().block();
            if (done != null) {
                done.forEach(ir -> results[ir.index()] = ir.result());
            }
        }
        return List.of(results);
    }

    private ToolResult execute(Tool tool, ConversationCtx ctx, Map<String, Object> args) {
        return tool.execute(ctx, args == null ? Map.of() : args).block();
    }

    private Tool findTool(List<Tool> tools, String name) {
        for (Tool tool : tools) {
            if (tool.name().equals(name)) {
                return tool;
            }
        }
        return null;
    }

    /** 一次工具调用（名称 + 入参）。 */
    public record ToolCall(String name, Map<String, Object> args) {
    }

    /** 并行结果带原始索引，保证按输入顺序回填。 */
    private record IndexedResult(int index, ToolResult result) {
    }

    /**
     * Phase 1：参数 schema 以自由对象承载，模型按描述传参；精确 schema 于 Phase 2 补齐。
     *
     * <p>{@code args} 声明为<b>对象</b>而非字符串：工具实现是从顶层取字段的
     * （{@code path}/{@code content}/{@code command}/{@code from}/{@code to}），
     * 引擎侧会把这个单一 {@code args} 对象展平后交给工具（见
     * {@code ReactEngine#parseArgs}），保证 schema 契约与工具入参一致。</p>
     */
    private JsonObjectSchema buildParameters() {
        return JsonObjectSchema.builder()
                .addProperty("args", JsonObjectSchema.builder()
                        .description("工具参数对象，按工具语义填写字段："
                                + "文件类工具用 path / content / command / from / to；"
                                + "其余工具用需求描述字段。")
                        .additionalProperties(true)
                        .build())
                .required("args")
                .build();
    }
}

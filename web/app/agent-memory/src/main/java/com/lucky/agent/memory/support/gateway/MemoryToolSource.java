package com.lucky.agent.memory.support.gateway;

import com.lucky.agent.common.api.Tool;
import com.lucky.agent.common.api.ToolAnnotations;
import com.lucky.agent.common.api.ToolSource;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.memory.support.md.HierarchyMemoryRetriever;
import com.lucky.agent.memory.support.md.MarkdownMemoryWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记忆只读工具源（对标 Claude Code Memory Tool 的检索侧）。
 *
 * <p>提供 {@code memory.search}（按关键词检索两级记忆）与 {@code memory.read}
 * （按索引锚点读取条目全文）。均为<b>框架内部只读 API</b>：路径由 Service 预生成、
 * 不接受外部路径参数、无任何写副作用 → 不构成越权写，不进入执行臂写边界
 * （readOnly 标注 → ToolGateway 并行执行）。写入型记忆工具另行立项。</p>
 */
@Slf4j
@Component
public class MemoryToolSource implements ToolSource {

    private final HierarchyMemoryRetriever retriever;
    private final MarkdownMemoryWriter writer;

    public MemoryToolSource(HierarchyMemoryRetriever retriever, MarkdownMemoryWriter writer) {
        this.retriever = retriever;
        this.writer = writer;
    }

    @Override
    public String namespace() {
        return "memory";
    }

    @Override
    public List<Tool> tools(String workspaceId, String goal) {
        return List.of(new SearchTool(), new ReadTool());
    }

    private String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    /** memory.search：在两级记忆全文中按关键词命中的所有行。 */
    private final class SearchTool implements Tool {
        @Override
        public String name() {
            return "memory.search";
        }

        @Override
        public String description() {
            return "搜索记忆库中与关键词相关的记忆行（用户级 + 当前项目级），返回命中条目";
        }

        @Override
        public ToolAnnotations annotations() {
            return ToolAnnotations.readOnlyTool();
        }

        @Override
        public Mono<ToolResult> execute(ConversationCtx ctx, Map<String, Object> args) {
            String query = str(args, "query").trim();
            if (query.isBlank()) {
                return Mono.just(ToolResult.error("缺少参数 query"));
            }
            String content = retriever.recallFullText(ctx.workspaceId());
            if (content == null || content.isBlank()) {
                return Mono.just(ToolResult.ok("暂无可用记忆"));
            }
            String lower = query.toLowerCase();
            List<String> hits = new ArrayList<>();
            for (String line : content.split("\\R")) {
                if (!line.isBlank() && line.toLowerCase().contains(lower)) {
                    hits.add(trim(line, 120));
                }
            }
            if (hits.isEmpty()) {
                return Mono.just(ToolResult.ok("未找到与「" + query + "」相关的记忆"));
            }
            return Mono.just(ToolResult.ok(String.join("\n", hits)));
        }
    }

    /** memory.read：按索引锚点 id 读取条目全文。 */
    private final class ReadTool implements Tool {
        @Override
        public String name() {
            return "memory.read";
        }

        @Override
        public String description() {
            return "按记忆索引 id 读取条目全文（id 形如 mem-xxxxxx，见 System Prompt 中的记忆索引）";
        }

        @Override
        public ToolAnnotations annotations() {
            return ToolAnnotations.readOnlyTool();
        }

        @Override
        public Mono<ToolResult> execute(ConversationCtx ctx, Map<String, Object> args) {
            String id = str(args, "entryId").trim();
            if (id.isBlank()) {
                return Mono.just(ToolResult.error("缺少参数 entryId"));
            }
            String text = writer.readEntries(ctx.workspaceId(), Set.of(id));
            if (text.isBlank()) {
                return Mono.just(ToolResult.ok("未找到记忆条目 " + id
                        + "（id 无效或已被 Dream 修剪，可从记忆索引中重新选择）"));
            }
            return Mono.just(ToolResult.ok(text));
        }
    }

    private String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
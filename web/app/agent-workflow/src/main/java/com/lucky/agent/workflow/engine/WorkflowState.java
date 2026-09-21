package com.lucky.agent.workflow.engine;

import org.bsc.langgraph4j.state.AgentState;

import java.util.HashMap;
import java.util.Map;

/**
 * LangGraph4j 工作流状态：承载跨节点传递的全局作用域与运行态信息。
 *
 * <p>沿用 LangGraph4j 的 {@link AgentState} 约定（内部即 {@code Map<String,Object>}），
 * 以节点 id 为命名空间存放节点输出，等价于原引擎的全局 {@code VariableScope}。</p>
 *
 * <p>键约定：</p>
 * <ul>
 *   <li>{@code __vars__}：全局变量表（{@code ${var}} 占位解析来源）</li>
 *   <li>{@code __nodeResults__}：节点 id → 该节点输出（{@code Map}），供下游连接器读取</li>
 *   <li>{@code __status__}：运行状态（{@code RUNNING} / {@code COMPLETED} / {@code FAILED}）</li>
 *   <li>{@code __error__}：失败原因（失败时写入）</li>
 * </ul>
 */
public class WorkflowState extends AgentState {

    /** 全局变量表。 */
    public static final String KEY_VARS = "__vars__";
    /** 节点输出表（节点 id → 输出 Map）。 */
    public static final String KEY_NODE_RESULTS = "__nodeResults__";
    /** 运行状态。 */
    public static final String KEY_STATUS = "__status__";
    /** 失败原因。 */
    public static final String KEY_ERROR = "__error__";

    public WorkflowState(Map<String, Object> initData) {
        super(initData);
    }

    /** 构造初始状态：写入初始变量、空的节点结果表与 RUNNING 状态。 */
    public static WorkflowState initial(Map<String, Object> initialVars) {
        Map<String, Object> data = new HashMap<>();
        data.put(KEY_VARS, initialVars == null ? new HashMap<String, Object>() : new HashMap<>(initialVars));
        data.put(KEY_NODE_RESULTS, new HashMap<String, Object>());
        data.put(KEY_STATUS, "RUNNING");
        return new WorkflowState(data);
    }

    /** 全局变量表（可变引用，可直接读写）。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> variables() {
        return (Map<String, Object>) value(KEY_VARS).orElseGet(HashMap::new);
    }

    /** 节点输出表（可变引用）。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> nodeResults() {
        return (Map<String, Object>) value(KEY_NODE_RESULTS).orElseGet(HashMap::new);
    }

    public String status() {
        return (String) value(KEY_STATUS).orElse("RUNNING");
    }

    public String error() {
        return (String) value(KEY_ERROR).orElse(null);
    }

    /** 标记工作流失败（终止性错误，由节点写入，路由据此收尾）。 */
    public void markFailed(String error) {
        data().put(KEY_STATUS, "FAILED");
        data().put(KEY_ERROR, error == null ? "未知错误" : error);
    }

    public void markCompleted() {
        data().put(KEY_STATUS, "COMPLETED");
    }

    public boolean failed() {
        return "FAILED".equals(status());
    }
}

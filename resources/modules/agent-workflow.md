# agent-workflow 模块实现文档

> 对应决策：D32（独立工作流模块，不依赖任何内部 sibling，仅 Spring Boot Web/Jackson/Reactor/Guava）。
> 技术底座：LangGraph4j StateGraph（DAG 与多 Agent 图编排）。
> 文档-实现同步于 2026-09-28；模块已并进 `web/app` 多模块工程并被 `agent-web` 依赖。

## 一、模块定位

提供**独立的工作流编排能力**：工作流定义与配置、节点/步骤管理、DAG 编译（拓扑排序 + 环检测）、条件路由执行、状态机、连接器、触发器、REST 管理接口 + SSE 实例事件流。通过 `LlmAdapter` / `ToolAdapter` / `SandboxAdapter` 三个**集成缝隙**与 `agent-core` / `agent-executor` 对接，模块自身不反向依赖内部业务模块。

## 二、实现框架（与代码包一一对应）

```
agent-workflow/src/main/java/com/lucky/agent/workflow
├── adapter/                    # 集成缝隙（与 agent-core/agent-executor 对接的窄接口）
│   ├── LlmAdapter.java         # LLM 调用缝隙（当前实现 NoopLlmAdapter，真实 LLM 节点待接线）
│   ├── ToolAdapter.java        # 工具调用缝隙（当前实现 NoopToolAdapter，真实工具节点待接线）
│   ├── SandboxAdapter.java     # 沙箱/执行缝隙（LocalSandboxAdapter 已实现，code 节点可用）
│   └── CommandResult.java
├── api/
│   └── WorkflowController.java # REST：定义 CRUD / 启停 / 校验 / 触发 / 实例列表 / SSE 事件流
├── config/
│   ├── WorkflowAutoConfiguration.java
│   └── WorkflowProperties.java
├── domain/
│   ├── WorkflowDef / NodeDef / EdgeDef / TriggerDef / VariableScope
│   ├── WorkflowInstance / NodeInstance / NodePosition
│   └── enums/                  # NodeStatus, RunMode, TriggerType, WorkflowNodeType, WorkflowStatus
├── dto/
│   ├── NodeTypeMeta.java       # 节点类型目录（前端画布）
│   └── TriggerRequest.java     # 触发请求（mode 大小写不敏感）
├── engine/                     # DAG 引擎
│   ├── DagCompiler.java        # DAG → 拓扑排序 + 环检测（编译期静态校验）
│   ├── WorkflowStateMachine.java / WorkflowState.java / NodeResult.java
│   ├── AbstractWorkflowEngine.java / LangGraphWorkflowEngine.java / WorkflowEngine.java
│   ├── ConditionEvaluator.java # 条件路由
│   ├── MappingEvaluator.java / InputMapping.java / OutputMapping.java / TemplateRenderer.java
│   ├── SubflowInvoker.java     # 子流程
│   └── executors/              # 各节点执行器
│       ├── StartNodeExecutor / EndNodeExecutor
│       ├── LlmNodeExecutor / ToolNodeExecutor / CodeNodeExecutor
│       ├── ConditionNodeExecutor / SubflowNodeExecutor
├── event/                      # WorkflowEvent / WorkflowEventBus / WorkflowEventType（SSE 数据源）
├── exception/                  # WorkflowException
├── repository/                 # WorkflowRepository / WorkflowInstanceRepository
│   ├── FileWorkflowRepository / FileWorkflowInstanceRepository（本机落盘）
│   └── InMemoryWorkflowRepository / InMemoryWorkflowInstanceRepository
├── service/
│   ├── WorkflowService.java
│   └── NodeTypeCatalog.java
└── engine/trigger/
    ├── Trigger.java / ManualTrigger / IntervalTrigger / WorkflowScheduler
```

## 三、核心设计

- **定义模型**：`WorkflowDef`（节点 `NodeDef` + 边 `EdgeDef` + 触发器 `TriggerDef`），节点带坐标 `NodePosition`（前端画布双写）。
- **DAG 编译**：`DagCompiler` 在定义保存/校验时做拓扑排序与环检测，非法即拒绝（`WorkflowException`）。
- **运行**：`LangGraphWorkflowEngine` 基于状态机推进节点；条件节点按 `ConditionEvaluator` 路由；代码节点经 `SandboxAdapter` 执行；`SubflowInvoker` 支持嵌套子流程。
- **状态与持久化**：`WorkflowInstance` 状态机 + `FileWorkflowInstanceRepository` 本机落盘（零托管），InMemory 仓库供测试/快速模式。
- **触发器**：ManualTrigger（REST 触发，SYNC/ASYNC 两种模式）与 IntervalTrigger（周期定时），由 `WorkflowScheduler` 统一调度。
- **集成缝隙（重要）**：`LlmAdapter` / `ToolAdapter` 目前为 **Noop 空实现**（NoopLlmAdapter/NoopToolAdapter）——**LLM 节点与工具节点尚不能真实执行**；`SandboxAdapter` 已有 `LocalSandboxAdapter`（本地命令执行），code 节点可用。接入真实 LLM/工具节点 = 替换这两个适配器为 agent-core/agent-executor 桥接实现。
- **强制不可变**：模块不依赖任何内部 sibling 模块（pom 仅 Spring Boot Web/Jackson/Reactor/Guava），保证可独立演进为服务。

## 四、关键配置（WorkflowProperties）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| workflow.repo.mode | file | 仓库模式（file / memory） |
| workflow.scheduler | enabled | 区间触发器调度开关 |
| workflow.llm-adapter | noop | LLM 缝隙实现（noop / 待接入桥接） |

（以 `WorkflowProperties.java` 实际字段为准。）

## 五、验收点

- [x] DAG 可编译、可执行；环检测拒绝非法定义；串行/并行/条件分支正确（DagCompiler 相关测试通过）。
- [x] 定义/实例 CRUD、手动触发（SYNC/ASYNC）、SSE 实例事件流可用（WorkflowController + WorkflowEventBus）。
- [ ] LLM 节点真实执行（替换 NoopLlmAdapter 后）。
- [ ] 工具节点真实执行（替换 NoopToolAdapter 后，经执行臂校验）。
- [ ] 节点越权操作被执行臂阻断（工具节点接线后联调）。
- [ ] 失败节点按策略降级，不静默吞错（现按节点类型抛出 WorkflowException，待定义重试/跳过策略）。
- [ ] 变量作用域隔离，不污染主上下文（VariableScope 已建模，待 LLM/工具节点接线后验证）。
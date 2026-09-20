# agent-workflow

独立的工作流模块：工作流**定义与配置**、**流程节点/步骤创建与管理**、**触发与执行**、**状态流转与流转条件控制**，
并提供节点 / 连接器 / 调度器等可复用组件。低耦合、职责单一、便于扩展。

- 不依赖任何内部 sibling 模块（仅依赖 Spring Boot Web / Jackson / Reactor / Guava）。
- 默认使用内存仓储即可端到端运行；也可切换为文件仓储（落用户本机目录）。
- 通过 `LlmAdapter` / `ToolAdapter` / `SandboxAdapter` 三个「集成缝隙」接口与宿主（如 `agent-core`、`agent-executor`）对接。

## 1. 包结构

```
com.lucky.agent.workflow
├── domain/            领域模型：WorkflowDef / NodeDef / EdgeDef / TriggerDef / WorkflowInstance / NodeInstance / VariableScope
│   └── enums/         WorkflowNodeType / WorkflowStatus / NodeStatus / RunMode / TriggerType
├── engine/            引擎与核心组件
│   ├── DagCompiler           编译：结构校验 + Kahn 拓扑排序 + 环检测
│   ├── WorkflowStateMachine  状态机：工作流状态流转规则
│   ├── WorkflowEngine        调度执行：条件路由 + 连接器映射 + 状态推进 + 结果聚合
│   ├── MappingEvaluator      连接器求值（点路径）
│   ├── ConditionEvaluator    条件表达式求值（安全子集，无 ScriptEngine）
│   ├── TemplateRenderer      ${var} 模板渲染
│   ├── executors/            7 类节点执行器（START/END/LLM/TOOL/CONDITION/CODE/SUBFLOW）
│   └── trigger/              Trigger 抽象 + ManualTrigger / IntervalTrigger + WorkflowScheduler
├── adapter/          集成缝隙：LlmAdapter / ToolAdapter / SandboxAdapter + 默认 Noop/Local 实现
├── repository/       仓储：WorkflowRepository(内存/文件) + WorkflowInstanceRepository
├── event/            事件：WorkflowEvent + WorkflowEventBus（SSE 监控用）
├── service/          WorkflowService：定义 CRUD / 启停 / 校验 / 触发 / 实例查询
├── api/              WorkflowController：REST + SSE
├── dto/              TriggerRequest
├── config/           WorkflowProperties + WorkflowAutoConfiguration
└── exception/        WorkflowException
```

## 2. 核心概念

| 概念 | 说明 |
|------|------|
| `WorkflowDef` | 静态蓝图：节点集合 + 边集合 + 触发器 + 元信息 |
| `NodeDef` | 节点定义：类型 + `config` + 连接器（`inputs`/`outputs`） |
| `EdgeDef` | 边：`source → target`，可选 `condition`（条件边即分支） |
| `NodeExecutor` | 节点执行器接口，按类型分发；新增类型只需实现并注册 |
| `Connector`（Input/OutputMapping） | 节点与全局作用域之间的变量映射 |
| `WorkflowScheduler` | 按触发器驱动执行；手动触发由 API 直接调用引擎 |

### 执行语义

1. `DagCompiler` 编译：结构校验 + 拓扑排序 + **环检测**（有环拒绝执行，防死循环）。
2. 以 START 为起点；节点就绪 = 所有入边已解析（触发或未触发）。
3. 边 `condition` 为 true 则触发；节点入边全部解析且至少一条触发 → 执行，全部未触发 → 跳过并级联。
4. 节点执行前解析输入、执行后回写输出（默认以节点 id 命名空间暴露，便于 `${nodeId.text}` 引用）。
5. 任一节点失败 → 工作流 `FAILED`（fail-fast）；全部完成 → `COMPLETED`。

## 3. 快速开始

### 3.1 编程式（无需 Spring）

```java
MappingEvaluator me = new MappingEvaluator();
ConditionEvaluator ce = new ConditionEvaluator(me);
WorkflowEngine engine = new WorkflowEngine(
        new DagCompiler(), new WorkflowStateMachine(), me, ce, new WorkflowEventBus(),
        List.of(new StartNodeExecutor(), new EndNodeExecutor(), new LlmNodeExecutor(me),
                new ToolNodeExecutor(), new ConditionNodeExecutor(), new CodeNodeExecutor(me), new SubflowNodeExecutor()),
        new NoopLlmAdapter(), new NoopToolAdapter(), new LocalSandboxAdapter(false, 5000, null),
        new InMemoryWorkflowRepository(), new InMemoryWorkflowInstanceRepository(), null);

WorkflowInstance inst = engine.run(def, Map.of("score", 80));
System.out.println(inst.getStatus());   // COMPLETED
```

### 3.2 接入 Spring Boot 应用

在宿主模块 `pom.xml` 加入依赖即可（模块通过自动配置注册全部 Bean）：

```xml
<dependency>
    <groupId>com.lucky.agent</groupId>
    <artifactId>agent-workflow</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 3.3 REST 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/workflows` | 创建定义 |
| GET | `/api/workflows` | 定义列表 |
| GET | `/api/workflows/{id}` | 定义详情 |
| PUT | `/api/workflows/{id}` | 更新定义 |
| DELETE | `/api/workflows/{id}` | 删除定义 |
| POST | `/api/workflows/{id}/enabled?enabled=true` | 启用/停用 |
| POST | `/api/workflows/validate` | 编译校验（静态检查预览） |
| POST | `/api/workflows/{id}/trigger` | 触发执行（可带初始变量 / 执行模式） |
| GET | `/api/workflows/instances` | 运行实例列表 |
| GET | `/api/workflows/instances/{instanceId}` | 实例状态 |
| GET | `/api/workflows/instances/{instanceId}/events` | **SSE** 执行事件流 |

## 4. 配置项（`lucky.workflow`）

```yaml
lucky:
  workflow:
    storage-dir:            # 为空使用内存仓储；设置后落本机目录
    sandbox:
      enabled: false        # 命令沙箱默认关闭，需显式授权
      timeout-ms: 10000
      working-dir:
    execution:
      thread-pool-size: 4
      default-mode: SYNC
      auto-triggers: true   # 是否启用周期等自动触发
```

## 5. 扩展点

| 需求 | 做法 |
|------|------|
| 接入真实模型 | 实现 `LlmAdapter` 并注册为 Bean（`@ConditionalOnMissingBean` 会退让） |
| 接入工具网关 / MCP | 实现 `ToolAdapter` |
| 接入强沙箱（realpath/配额） | 实现 `SandboxAdapter`（替换 `LocalSandboxAdapter`） |
| 新增节点类型 | 实现 `NodeExecutor` 并加入 `workflowNodeExecutors` |
| 新增触发方式（Webhook/MQ） | 实现 `Trigger` 并加入 `workflowTriggers` |
| 更换存储 | 实现 `WorkflowRepository` / `WorkflowInstanceRepository` |

## 6. 测试

```bash
# 需 JDK 21
mvn -pl agent-workflow test
```

覆盖：引擎端到端（分支/跳过/失败快速失败）、DAG 编译（拓扑/环检测/结构校验）、条件求值、自动配置装配与覆盖。

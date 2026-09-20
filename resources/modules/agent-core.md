# agent-core 模块实现文档

> 对应需求文档：§4.4 PLAN-ACT-ASK、§4.5 REACT 统一引擎、§2.1 核心编排

## 一、模块定位

`agent-core` 是框架的**编排中枢**，承载统一的 REACT 推理引擎，并基于同一引擎封装 PLAN / ACT / ASK 三阶段。它不包含任何业务能力，只负责"如何推理"，所有能力（记忆/Skill/MCP/文件）通过工具网关以 Tool 形式注入。

**责任边界**：
- 负责推理循环、步数约束、计划校验、工具调用编排。
- 负责主 Agent 编排与子代理/多 Agent 任务执行调度。
- 不负责具体工具实现（交给 memory/skill/executor 等模块）。
- 不负责权限裁决（交给 permission/executor 的本端防护）。

---

## 二、实现框架（分层）

```
agent-core
├── api/                      # 对外契约（接口 + DTO）
│   ├── Engine.java           # REACT 引擎入口
│   ├── Step.java             # Thought/Action/Observation 单元
│   ├── Plan.java / PlanValidator.java
│   └── dto/                  # ConversationCtx, ToolCall, ToolResult
├── engine/
│   ├── ReactEngine.java      # LangChain4j/LangGraph4j 封装层（统一阶段入口，不实现第二套循环）
│   ├── StepLimitGuard.java   # 步数上限 + 早停
│   └── EarlyStopPolicy.java
├── runtime/
│   ├── ConversationStateManager.java  # 会话状态唯一事实源（适配 LangChain4j/LangGraph4j 会话）
│   ├── AgentEventPublisher.java       # Flux<AgentEvent> 事件发布
│   └── RunBudget.java                 # maxTurns/maxBudget 循环边界检查
├── hook/
│   └── LifecycleHookDispatcher.java   # 内部 Hook 事件 + deny-wins
├── compact/
│   └── CompactionPipeline.java        # 五级压缩流水线接口
├── planactask/
│   ├── PlanGenerator.java
│   ├── ActScheduler.java
│   ├── AskSuspender.java
│   └── Replanner.java
├── gateway/
│   ├── ToolGateway.java      # 工具调用网关（按 name 路由到各模块 Tool）
│   └── ObservationNormalizer.java  # Observation 归一化
├── subagent/
│   ├── SubAgentFactory.java    # 子代理创建
│   ├── SubAgentExecutor.java   # 子代理任务执行
│   ├── TaskDecomposer.java     # 任务分解
│   ├── TaskScheduler.java      # 串行/并行调度
│   ├── TaskProgressTracker.java # 小任务列表与进度追踪
│   └── ResultAggregator.java   # 结果聚合
└── contract/
    └── PlanSchema.java       # 计划 JSON Schema 契约
```

**模块契约原则**：
- `api/` 包暴露所有跨模块调用接口（Engine、ToolGateway、PlanValidator），实现类不跨模块暴露。
- 工具以 `Tool` 接口统一抽象，由 `ToolGateway` 注册分发；各模块（memory/skill/executor）实现 `Tool` 并向 `ToolGateway` 注册。
- 禁止 core 反向依赖其他业务模块实现类，仅依赖 `Tool` 契约。

---

## 三、实现思路（核心设计）

### 3.1 统一 REACT 引擎（底层）

PLAN / ACT / ASK 不是三个独立循环，而是**同一个 REACT 引擎的三个运行模式**。引擎签名统一为：

```java
interface Engine {
    EngineRunResult run(ConversationCtx ctx, Phase phase, Goal goal);
}
```

每轮循环步骤：
1. 组装 System Prompt（按 phase 注入不同角色指令与目标/终止条件）。
2. 调模型 → 解析出 `Thought` + `Action`（或 `FinalAnswer`）。
3. 若 Action 是工具调用 → `ToolGateway.dispatch(action)` → 归一化为 `Observation`。
4. 将 `Thought/Action/Observation` 写入本机记忆与追溯（经 memory 模块）。
5. 检查终止条件（phase 相关）或步数上限 → 否则回到 2。

**早停 / 步数上限**：`StepLimitGuard` 对单 phase 设步数上限（可配，默认 PLAN=12、ACT=30、ASK 无上限直至用户回复）。超限强制转 ASK（向用户提问）或触发 `Replanner` 重规划，防无限试错（降本，见 §6）。

### 3.2 PLAN 阶段

- 目标：产出**可执行计划**（结构化 JSON）。
- `PlanGenerator` 调用引擎，系统提示要求"只规划不执行"。
- 产出经 `PlanValidator` + `PlanSchema` 校验：
  - 合法 → 进入 ACT。
  - 非法（缺字段/无法解析/含越权步骤）→ 拒绝并 `Replanner` 重规划（覆盖 R3）。

### 3.3 ACT 阶段

- 目标：按 Plan 逐步执行单步。
- `ActScheduler` 取计划下一步 → 引擎以"执行单步"为目标的循环 → 工具调用 → Observation → 更新进度。
- 每步复用同一引擎，避免双层嵌套循环失控。

### 3.4 ASK 阶段

- 触发：信息不足 / 需用户决策 / 危险操作确认（与 permission 模块联动）。
- Action 变为"向用户提问"，引擎挂起，用户输入经 Web 层作为 Observation 续跑。

### 3.5 计划 Schema 契约（PlanSchema）

```json
{
  "goal": "string",
  "steps": [
    { "id": 1, "type": "file|shell|ask|tool", "desc": "string",
      "target": "string", "safe": true }
  ],
  "canAutoExecute": false
}
```

校验器拒绝 `safe=false` 且无用户确认的步骤（与 §4.12 危险操作确认联动）。

---

## 四、关键配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| react.plan.maxSteps | 12 | PLAN 阶段步数上限 |
| react.act.maxSteps | 30 | ACT 阶段步数上限 |
| react.earlyStop.confidence | 0.3 | 低置信早停转 ASK |
| engine.model.route | 由 model 模块注入 | 当前单模型退化为直通 |
| core.run.maxTurns | 30 | 每次循环边界检查的回合上限 |
| core.run.maxBudgetTokens | -1 | 会话 token 预算硬门槛，-1 表示不设限 |
| core.subagent.enabled | false | 默认单 Agent，按需启用多 Agent |
| core.subagent.maxConcurrency | 4 | 子代理并行上限 |
| core.subagent.taskTimeoutSec | 300 | 子任务超时 |

---

## 五、与其他模块关系

- `agent-memory`：每轮 Thought/Action/Observation 落本机记忆（§3.5）。
- `agent-model`：提供模型端点与路由。
- `agent-permission`：ASK 危险操作确认触发点。
- `agent-executor` / `agent-skill` / `agent-mcp`：经 `ToolGateway` 注册为 `Tool`。
- `agent-cache` / `agent-common`：缓存命中（语义/工具结果）、并发守卫。

---

## 六、具体设计

> 核心思路：直接复用 LangChain4j 的 React 范式与 Tool 机制，core 本身只做"编排胶水 + 阶段封装 + 约束"，不重写推理内核。本模块只做职责、流程与边界层面的设计，不涉及具体代码实现。

### 6.1 复用 LangChain4j 的方式
- **Tool 机制**：各模块实现 common 的 `Tool`，在 LangChain4j/LangGraph4j 中以 FunctionCall Tool 注册；core 不自己解析工具调用，交给 LangChain4j 的 Tool 调度。
- **React 范式**：LangChain4j 原生支持 Thought→Action→Observation 循环；core 把 PLAN/ACT/ASK 实现为"切换 system prompt + 终止条件的三次 React 运行"，而非自造循环。
- **子 Agent 机制**：LangChain4j 原生支持子 Agent；core 的 `SubAgentFactory` 基于它创建子代理，不自行实现第二套推理循环。
- **工作流机制**：LangGraph4j `StateGraph` 提供 DAG 与多 Agent 图编排；`agent-workflow` 在其上构建工作流，core 不重复实现 DAG 调度。
- **记忆接入**：把 `agent-memory` 包成 LangChain4j 的 Memory Module，按会话注入。

### 6.2 引擎与阶段职责
- **统一引擎**：持有 LangChain4j 的 Agent 实例，按当前阶段组装不同 system prompt 与终止条件后运行一次 React；不维护自己的推理循环。
- **工具网关**：负责把 common 的 `Tool` 适配为 LangChain4j 的 Tool，并按名称分发；各模块启动时注册，LangChain4j/LangGraph4j 回调时由其转交。新增工具对 core 无侵入。
- **计划校验**：对 PLAN 产出的结构化计划做字段与越权步骤校验，非法则触发重规划。
- **步数/早停守卫**：对单阶段设步数上限与低置信早停，超限强制转 ASK 或重规划，防无限试错（降本）。
- **三阶段封装**：PLAN / ACT / ASK 三者均委托同一引擎，仅替换 prompt、目标与终止条件，避免双层嵌套循环失控。
- **会话状态唯一事实源**：`ConversationStateManager` 维护会话状态，引擎每次循环从该状态追加，避免多通道状态散落。
- **事件流**：引擎以 `Flux<AgentEvent>` 输出，Web/CLI 经 `AgentChannel` 消费同一事件流。
- **Hook 分发**：`LifecycleHookDispatcher` 在固定生命周期事件触发内部 Hook，deny-wins，后续可接外部 Shell/Webhook/MCP Hook。
- **压缩流水线**：`CompactionPipeline` 定义五级压缩接口，MVP 实现工具结果裁剪与基础摘要。
- **子代理执行器**：主 Agent 计划中识别可独立执行的子任务后，创建子代理执行；子代理共享 ToolGateway、权限级别与执行臂硬边界，结果聚合后回写主 Agent。

### 6.3 计划 Schema 契约（PlanSchema）

```json
{ "goal":"...", "steps":[
  {"id":1,"type":"file|shell|ask|tool","desc":"...","target":"...","safe":true}
], "canAutoExecute":false }
```
校验器拒绝 `safe=false` 且无用户确认的步骤（→ ASK）。

### 6.4 设计模式应用
- **策略模式**：每个阶段对应一套策略（prompt/终止条件/步数），引擎按策略运行。
- **门面模式**：工具网关对外屏蔽 LangChain4j Tool 适配细节。
- **模板方法**：引擎运行固定"组装 prompt→调模型→dispatch→记录→判终止"骨架，阶段差异下推到策略。
- **责任链**：Observation 先经归一化再入记忆。

### 6.5 子代理与多 Agent 任务执行

- **触发条件**：默认单 Agent；仅当任务可分解且串行/并行执行收益明确时，主 Agent 经 `TaskDecomposer` 拆分子任务。
- **创建与调度**：`SubAgentFactory` 基于 `agent-persona` 的角色/人格与 `agent-skill` 能力集创建子代理；`TaskScheduler` 支持串行、并行、fan-out/fan-in，并行度受 `agent-common` 信号量与线程池约束。
- **任务列表与进度**：`TaskProgressTracker` 维护小任务列表与状态（待执行/执行中/已完成/失败/挂起），通过 SSE 推送任务计划、进度与完成事件；Web/CLI 对话界面展示清单、当前步骤与完成比例。
- **上下文**：每个子代理绑定独立 `taskId`/`sessionId`，中间结果隔离；继承主 Agent 的工作区、权限级别与安全约束。
- **聚合与降级**：`ResultAggregator` 汇总结果；子任务失败/超时按策略重试、降级为单 Agent 或转 ASK，不静默失败。
- **安全与追溯**：子代理不能提升权限、不能绕过危险操作 ASK；每轮 Thought/Action/Observation 落本机记忆与 `.logs/` trace。

### 6.6 验收点（具体）

- [ ] 计划校验对缺字段/含越权步骤的计划返回失败，且重规划被触发。
- [ ] ACT 步数达上限时引擎转 ASK（不无限试错）。
- [ ] ASK 挂起后，用户输入作为 Observation 续跑，会话状态不丢。
- [ ] 每轮 Thought/Action/Observation 经 memory 模块落 `<frameworkRoot>/.memory/` 且 `<frameworkRoot>/.logs/` 留元数据 trace。
- [ ] 新增一个 Tool 只需在模块内实现 `Tool` 并注册，core 无改动（开闭原则）。
- [ ] LangChain4j 原生 React 循环未被 core 重新实现（避免双层循环）。
- [ ] 含两个独立子任务的任务被拆为两个子代理，并行执行并正确聚合。
- [ ] 子代理并发受 `core.subagent.maxConcurrency` 约束，不突破全局模型在途数。
- [ ] 子代理尝试越权/越界操作时被执行臂拒绝，无法提升权限。
- [ ] 子代理失败/超时后按策略重试、降级单 Agent 或转 ASK。
- [ ] 子代理 trace 在 `.logs/` 可追溯，前端/CLI 可查看执行进度。
- [ ] 复杂任务被拆成小任务列表，对话界面实时显示状态与完成比例，与执行步骤一致。

---

## 七、系统提示词分层设计（问题 2：系统提示词怎么设计）

> 设计原则：system prompt 是**分层拼装**的，而非一整段写死。core 在每次 React 运行前，按"固定基座 + 角色人格 + 阶段指令 + 权限约束 + 召回上下文"五层组装，既保证一致性又职责清晰、便于演进。

### 7.1 五层结构
1. **基座层（Base）**：框架身份与总规则——"你是 lucky_agent 的助手，所有文件操作限制在工作空间内，禁止外传用户数据"。恒定不变。
2. **角色/人格层（Persona）**：来自 `agent-persona` 的语气/详尽度/主动性参数，转为约束句段（如"严谨、先给方案再动手"）。
3. **阶段指令层（Phase）**：PLAN 阶段要求"先产出结构化计划、不执行"；ACT 阶段要求"按计划逐步执行、每步汇报"；ASK 阶段要求"仅就高风险点向用户确认"。
4. **权限约束层（Permission）**：来自 `agent-permission` 的级别（只读/修改/全部），直接写进 prompt 让用户意图与硬边界一致（如"当前工作区为 MODIFY，不可删除目录"）。
5. **召回上下文层（Memory + Skill）**：`agent-memory` 召回的双轨记忆 + `agent-skill` 命中的 Skill 说明，作为本次推理的已知事实与可用能力注入。

### 7.2 组装与职责边界
- core 只负责"按层调用各模块产出片段并拼接"，不持有业务内容（内容由各模块负责）——符合单一职责。
- 阶段切换只换"阶段指令层"与终止条件，其余四层保持稳定，避免 prompt 漂移。

### 7.3 验收点
- [ ] 切换人格后，对话请求中角色层片段变化、基座层不变。
- [ ] 改权限级别后，权限约束层片段随 `levelOf` 结果变化（抓包可见）。
- [ ] PLAN 与 ACT 两次运行的 system prompt 仅阶段指令层差异，其余四层一致。

---

## 八、LLM 风险管理（问题 2：风险控制、token 管理、职责）

> 风险管理分三层：① prompt 层约束（权限/危险操作拒绝）② 运行层约束（步数/早停）③ 执行层硬边界（executor 越界）。token 管理走"预算+计量+压缩"三件套。

### 8.1 风险管理的三道闸
- **第一道（提示词约束）**：系统提示词明确"不越界、不上传用户数据、危险操作须 ASK"，由 LLM 软约束。
- **第二道（运行约束）**：`StepLimitGuard` 步数上限 + 低置信早停；`PlanValidator` 拒越权步骤；`DangerousOpDetector` 命中危险动作转 ASK（用户确认才继续）。
- **第三道（硬边界）**：executor 的 `BoundaryGuard` 在真正落盘前做 realpath 越界校验，任何逃逸直接拒绝（R1/R2）。软约束失效也不泄露用户本机。

### 8.2 Token 管理（与 model 模块协同）
- **预算**：每次会话/计划设 token 预算上限；`react.earlyStop` 在接近预算或低置信时提前转 ASK，避免无谓烧 token。
- **计量**：`agent-model` 的 `ModelWrapper` 在每次 `generate` 后回写用量（prompt+completion），经 common 埋点到本机 `.logs/`（属框架隐藏目录，不上云）。
- **压缩（90% 阈值自动触发）**：core 每次模型调用后检查 model 上报的上下文占用率（会话已用 token ÷ 上下文窗口），**≥ `model.context.threshold`（默认 0.9，可配置）即自动触发压缩**——滑动窗口/摘要/下沉，压缩后占用率回落继续；保全计划/安全约束/关键结果不下沉；多模型场景由 router 把压缩请求派给小模型降本；单次压缩无法回落时强制转 ASK（见 model §7.4）。

### 8.3 职责划分
- core：编排 + 阶段约束 + 计划校验 + 早停，是风险"调度者"。
- permission：提供级别与危险操作判定信号，是"决策者输入"。
- executor：执行硬边界，是"最后闸门"。
- model：在途数 + token 计量 + 额度兜底，是"资源守门人"。

### 8.4 验收点
- [ ] 构造越权计划，PlanValidator 拒绝且第二道闸生效；即使 LLM 仍尝试，executor 第三道闸拒绝（三层独立）。
- [ ] 单轮 token 超预算时引擎早停转 ASK，生成停止。
- [ ] `.logs/` 中每轮均记录 token 用量，且抓包验证该日志无外发。

---

## 九、架构锁定项（八项差距确认）

- 控制流固定为 `ConversationStateManager` + `Flux<AgentEvent>` + `RunBudget`（每次循环边界检查 maxTurns/maxBudget）。
- LangChain4j 负责推理循环、工具、记忆与 RAG 组件；LangGraph4j 负责工作流与多 Agent 图编排；工作区存储、沙箱与执行由本地模块与执行臂负责；core 只做阶段封装、策略、规则与事件适配。
- Spring AI（辅助层）承担模型/嵌入/向量库/可观测性接入，通过 SpringAiModelAdapter / SpringAiVectorAdapter 桥接到 LangChain4j；编排统一走 LangChain4j/LangGraph4j，不产生第三套编排抽象。
- Hook 事件名固定：SessionStart / UserPromptSubmit / PreToolUse / PostToolUse / PreCompact / PostCompact / SubagentStart / SubagentStop / Stop。
- 压缩固定为五级 `CompactionPipeline`，压缩失败必须熔断，不能无限重试。
- 提示词固定为静态/动态分界 + 五级覆盖优先级。
- 子代理只回摘要或结构化结果，不提升权限，不绕过 ASK 与执行臂硬边界。

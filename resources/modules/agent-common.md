# agent-common 模块实现文档

> 对应需求文档：§2.1 模块契约原则、§2.2 单机抗并发抽象、§3.0 工具/异常/常量

## 一、模块定位

`agent-common` 是公共契约层：DTO、异常、常量、工具类，以及**统一的并发/缓存抽象**（演进预留）。被所有模块依赖，自身不依赖任何业务模块。

**责任边界**：
- 公共 DTO / 异常 / 枚举（权限级别、Phase 等）。
- 并发抽象（`ConcurrencyGuard` / `RateLimiter`）、Caffeine 封装、目录路径工具。
- 不承载业务逻辑。

---

## 二、实现框架

```
agent-common
├── api/
│   ├── Tool.java               # 统一工具抽象（含 ToolAnnotations）
│   ├── ToolAnnotations.java    # 工具注解（readOnly/destructive/idempotent）
│   ├── ConcurrencyGuard.java   # 并发守卫抽象（信号量/读写锁）
│   └── RateLimiter.java        # 限流抽象（预留 Sentinel 替换）
├── dto/                        # 跨模块 DTO
│   ├── ConversationCtx.java
│   ├── WorkspaceId.java
│   ├── AgentEvent.java
│   └── ToolCall / ToolResult
├── exception/
│   └── AgentException.java     # 统一异常
├── constant/
│   ├── PermissionLevel.java    # 只读/修改/全部
│   ├── PermissionDecision.java # ALLOW / DENY / ASK / DEFER
│   ├── Phase.java              # PLAN/ACT/ASK
│   └── WorkspaceDirs.java      # §3.0 目录常量
├── concurrent/
│   ├── SemaphoreGuard.java     # 模型在途数信号量
│   ├── ReadWriteLockGuard.java # 记忆/文件读写锁
│   └── TokenBucket.java        # 本地令牌桶（Guava）
├── cache/
│   └── CaffeineProvider.java   # 按用户/工作区隔离封装
├── util/
│   ├── PathUtil.java           # realpath 规范化工具（供 executor）
│   └── JsonlUtil.java          # JSONL 读写
└── config/ CommonConfig.java
```

---

## 三、实现思路（核心设计）

### 3.1 模块契约基础

- 定义 `Tool` 统一抽象（含 `ToolAnnotations`），所有能力模块（memory/skill/mcp/executor）实现并注册到 core 的 `ToolGateway`。
- 跨模块只依赖 `api/` 接口与 `dto/`，禁止业务模块间直接依赖实现（§2.1）。

### 3.2 单机抗并发抽象（§2.2）

- `ConcurrencyGuard` / `RateLimiter` 统一抽象，当前用 JDK/Guava 本地实现；未来换 Redis/Sentinel 只需替换 `agent-common` 实现，业务代码不动（§十一演进原则）。
- `SemaphoreGuard`：模型在途数控制（R6）。
- `ReadWriteLockGuard`：记忆/文件并发写防冲突。
- `TokenBucket`：单实例入口与模型端点限流，预留 Sentinel。

### 3.3 目录常量（§3.0）

- `WorkspaceDirs` 集中定义框架运行目录（`.config/` `.memory/` `.platform/` `.rollback/` `.cache/` `.logs/` `.skills/` `.mcp/` `.tmp/` `.trash/` `.agent/` 共十一个**以 `.` 开头的隐藏目录**），全框架统一引用，禁止在 `<frameworkRoot>/` 外写盘。
- **重要**：这些隐藏目录承载框架自身的配置、记忆、缓存、审计、执行臂等运行数据，**只对用户隐藏、用户一般无需直接改动**；用户自己的工作区（工作空间根下的非 `.` 前缀目录与文件，如按 `workspaceId` 隔离的产物目录）一律**可见、可自由操作**，框架不对其做任何隐藏处理。

### 3.4 路径工具

- `PathUtil.realpath()`：规范化路径、阻断 `../` 与符号链接逃逸，供 executor 的 `BoundaryGuard` 复用。

---

## 四、关键配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| common.cache.isolation | user+workspace | Caffeine 隔离维度 |
| common.guard.modelSemaphore | 16 | 模型在途数默认上限 |

---

## 五、与其他模块关系

- 被**所有**业务模块依赖（core/memory/skill/mcp/permission/executor/persona/model/cache/workspace/web）。
- 自身不依赖任何业务模块，保持纯净。

---

## 六、具体设计

> 本模块为纯契约层，设计目标是"小、稳、被所有人依赖"。不过度设计，只沉淀真正被多模块复用的抽象。本模块只做职责、流程与边界层面的设计，不涉及具体代码实现。

### 6.1 核心契约
- **统一工具抽象（Tool）**：所有能力模块实现此抽象，注册到 core 的 `ToolGateway`；含唯一名、描述（供 LLM 语义理解 + Skill 匹配）、入参 Schema（复用于计划校验）、执行入口、`ToolAnnotations`。
- **工具注解（ToolAnnotations）**：`readOnly` / `destructive` / `idempotent` 决定调度方式——只读工具可并行，写工具串行，破坏性工具转 ASK。
- **错误即数据**：工具执行不向上抛异常，统一返回 `ToolResult { ok, data, error }`，由模型读取错误后重试或换路径。
- **并发守卫抽象**：定义读写锁等并发控制契约；本地实现与未来远端实现可互换（演进预案）。
- **限流抽象**：令牌桶式限流契约，单实例入口与模型端点限流，预留 Sentinel 替换点。
- **缓存抽象**：业务只关心 get/put，落盘/内存策略由实现决定。

### 6.2 关键常量与工具
- **`WorkspaceDirs`**：集中定义十一个框架隐藏目录（`.config/.memory/.platform/.rollback/.cache/.logs/.skills/.mcp/.tmp/.trash/.agent`），全框架统一引用，杜绝在 `<frameworkRoot>/` 外写盘。仅这些目录对用户隐藏，用户工作区可见。
- **`PermissionLevel` / `PermissionDecision` / `Phase`**：权限级别、权限裁决（ALLOW/DENY/ASK/DEFER）与三阶段（PLAN/ACT/ASK）枚举，全框架复用。
- **`AgentEvent`**：统一事件结构（thought/action/tool_result/skill_invoke/mcp_invoke/task_plan/task_progress/ask/error/token/stop），Web/CLI 消费同一事件流。
- **`PathUtil.realpath`**：规范化并校验目标路径不越出工作空间；对 `../` 与符号链接逃逸返回异常，供 executor 硬边界复用。

### 6.3 设计模式应用
- **策略模式**：并发守卫/限流/缓存多实现可切换（演进预案）。
- **模板方法**：缓存抽象固定 get/put 骨架，落盘/内存由实现决定。
- **工厂/注册表思路**：`Tool` 由各自模块注册，common 只定义契约不持有实例。

### 6.4 验收点（具体）

- [ ] `Tool` 契约被 memory/skill/mcp/executor 全部实现并能被 core 调用。
- [ ] 切换缓存实现（本地↔远端桩）业务代码零改。
- [ ] `WorkspaceDirs` 路径被所有模块引用，全仓无硬编码 `.config`/`.memory` 字符串散落。
- [ ] `PathUtil.realpath` 对 `../` 与符号链接返回异常（被 executor 单测复用）。
- [ ] 全模块依赖图无环（maven `dependency:tree` 验证）。

---

## 七、工具类（Tool）管理与注册（问题 7：有没有工具类、如何管理）

> common 只定义 `Tool` 契约；**注册与生命周期管理在 core 的 `ToolGateway`**（详见 agent-core）。本模块说明"工具类是什么、如何被统一管理"。

### 7.1 工具契约（统一抽象）
所有可被执行的能力（内置文件/Shell 操作、Skill、MCP）都实现 common 的 `Tool`：`name()` 唯一名、`description()` 供 LLM 与语义匹配、`schema()` 入参 JSON Schema、`execute(ctx, args)` 执行入口。统一契约使三类来源对 core 透明。

### 7.2 注册管理（由 core ToolGateway 负责）
- **启动时注册**：各模块（executor/skill/mcp）在应用启动期把自身 `Tool` 实例注册进 `ToolGateway` 的注册表（内存 Map，name→Tool）。
- **按需可见 vs 全量注册**：注册表持有全量工具，但注入 LLM 时按"Skill 语义 Top-K + 当前权限"筛选，避免工具过多干扰决策（呼应 skill 章节）。
- **热插拔**：Skill 新增/卸载、MCP 授权变更时，通过事件通知 `ToolGateway` 增删对应 Tool，无需重启。
- **分发**：LangChain4j/LangGraph4j 回调工具调用时，`ToolGateway` 按 `name` 找到 Tool 并 `execute`，结果统一回写 `callId` 供可视化串联。

### 7.3 工具类与 LangChain4j 的关系
`Tool` 最终被适配为 LangChain4j 的 FunctionCall Tool 注册进 Agent 实例——即"工具类"是框架对 LangChain4j Tool 的业务层封装，复用其调度而非自造。

### 7.4 验收点
- [ ] 启动后 `ToolGateway` 注册表含 executor/skill/mcp 全部 Tool；新增 Skill 热加载后注册表自动增加。
- [ ] 注入 LLM 的工具集 = 全量注册表中按权限+语义筛选的子集。
- [ ] LangChain4j/LangGraph4j 回调某 `name` 时，`ToolGateway` 正确分发并执行，结果带 `callId`。

---

## 八、横切：监控与统一异常兜底体系（问题 5/6：监控与异常处理）

> 监控与异常兜底属横切关注点，统一在 common 沉淀"埋点契约 + 异常分类 + 降级策略"，各模块落地。

### 8.1 风险/流量/实时消费监控（问题 5）
- **埋点契约**：common 定义统一埋点接口（用量/token/在途数/调用耗时/异常），各模块在关键节点打点，数据落本机 `.logs/`（框架隐藏目录，**不上云**，隐私红线 R4）。
- **风险监控**：危险操作（越权/高危）被 `DangerousOpDetector` 标记后既转 ASK 又记风险日志。
- **流量监控**：在途数（信号量当前值）、调用 QPS 由 model/common 暴露，供 web 面板展示。
- **实时 token 消费**：见 agent-model 第七节的 SSE `token` 事件 + 本机序列。

### 8.2 统一异常分类与兜底（问题 6）
按失败域分级，避免"一处挂全崩"：
- **LLM 侧**：额度不足/限流/超时/内容异常 → 模型模块兜底（见 agent-model 第八节：切备、退避重试、转 ASK）。
- **文件操作失败**（executor）：越界→硬拒；磁盘满/IO 异常→捕获转"磁盘已满"等可读提示，进程不崩；操作前 `.rollback/` 快照支持一键撤销（见 agent-executor）。
- **工具/调用失败**（Tool）：执行抛异常时 `ToolGateway` 捕获，记 `.logs/` 并经 SSE `error` 事件回传前端（带 `fallback` 字段说明兜底动作，如"转 ASK"）；不向上抛崩溃。
- **MCP 连接失败**：探活超时标记下线、自动重连或降级（见 agent-mcp）。
- **通用降级守卫**：cache 异常跳过缓存直走计算（见 agent-cache），保证主链路优先。

### 8.3 异常处理原则
- 永不静默失败、永不伪造结果；能自动恢复的自动恢复（重试/切备/回退），不能的恢复则明确转 ASK 让用户决策。
- 所有异常均有本机日志可追溯，无信息外发。

### 8.4 验收点
- [ ] 制造"工具执行抛异常"，前端收到 `error` 事件且对话不崩、自动转 ASK。
- [ ] 制造"磁盘满"，executor 捕获并回"磁盘已满"可读提示，进程存活。
- [ ] 任意异常均能在 `.logs/` 找到对应记录，且抓包验证日志无外发。
- [ ] 只读工具并行执行、写工具串行执行；工具异常以 `ToolResult.isError` 返回且不崩循环。
- [ ] `AgentEvent` 类型被 Web/CLI 共用，无通道各自定义事件。

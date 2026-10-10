# CLI 方案 v3 合理性审查

> 审查对象：`resources/CLI方案-v3.md`（v3.0）
> 方法：**证据先行**——把方案里每一条「我们缺 X、可以做 Y」的论断，回落到真实代码里验证其**前提是否成立**（内核有没有这个通道、接口存不存在、结构支持不支持）。
> 结论：**方案骨架成立（约七成可直接开工），但有 1 处范畴错误、4 处遗漏前提、2 处高估、1 处低估**。修正后方可按批次 5 开工。

---

## 0. 结论总览

| # | 方案论断 | 评级 | 一句话依据 |
|---|----------|:----:|-----------|
| 1 | `/model` 会话内切换，复用 `extra.modelId`，不动内核 | ✅ **成立** | 内核**每轮**都从 `UserInput.extra` 重读 `modelId`（`ConversationManager:1011-1016`） |
| 2 | `@` 文件补全有实现路径 | ✅ **成立** | `FileService.list(workspaceId, path)` 已存在（`FileService.java:22`） |
| 3 | `/mcp` `/agents` 依赖已补即可做 | ⚠️ **遗漏前提** | 依赖确在（`agent-cli/pom.xml:62-69`），但 `CliServices` **只有 9 个字段、不含任何 registry**（`CliServices.java:22-30`） |
| 4 | `/` 命令菜单（带描述） | ✅ **成立** | JLine `Candidate` 原生支持 `descr`，现只用 `new Candidate(c)` 未填（`CliRunner:239`） |
| 5 | 不引入全屏 TUI | ✅ **成立** | 三条论据全部有据（见 §1.5） |
| 6 | **`/plan` 是三家交集、我们缺** | ❌ **范畴错误** | 本项目 `Phase.PLAN/ACT/ASK` 是**编排阶段**，**不是用户模式**（见 §2.1） |
| 7 | `/init` 生成 `LUCKY.md` 骨架 | ❌ **真实疏漏** | 规则文档是**结构化格式**，必须过 `RuleDocumentCodec`，手写模板会失效（见 §2.2） |
| 8 | `/agents` 对标三家 subagent 管理 | ⚠️ **术语不准** | 本项目是**技能**（`SkillDef`），非子代理（见 §2.4） |
| 9 | `/fork` = 「复制已有行到新文件」 | ⚠️ **低估** | 会话是 **meta + message 双文件**，fork 要同步两者（见 §2.5） |
| 10 | `/diff` 走 `!` 通道 | ⚠️ **高估价值** | 受**权限档**约束，只读工作空间下不可用（见 §2.6） |
| 11 | （方案未提）`/rewind` 需内核支持才可能 | 🔍 **反向发现** | `truncateAfter(sessionId, ts)` **已存在**，比 `/undo` 改造成本更低（见 §3.1） |

**总体判断**：**诊断层（第 1、2 章）质量高**——三家的操作模型解构与差距矩阵基本准确；**方案层（第 3 章）有 1 个结构性错误（`/plan`）**，需先修掉再执行。

---

## 1. 成立项：证据充分，可直接开工

### 1.1 `/model` 会话内切换 —— **成立，且比方案预估的更简单**

方案说「需在 `CommandContext`/`SessionHolder` 增加可变当前值，`baseExtra()` 优先读它」。核实后**完全成立**：

```java
// ConversationManager.java:1011-1016（buildCtx，每轮提交都会走）
String modelId = modelIdOf(input);
if (modelId != null && !modelId.isBlank()) {
    extra.put(MODEL_ID_KEY, modelId);        // MODEL_ID_KEY = "modelId"（:63）
}
```

```java
// ConversationManager.java:1069 modelIdOf —— 从 UserInput.extra() 取
private String modelIdOf(UserInput input) { ... input.extra() ... }
```

**关键事实**：`modelId` 是**每条输入都重新读取**的 per-run 键（不是会话创建时固化）。因此：CLI 只要在**每轮提交时**把当前选中的 modelId 塞进 extra，切换立即生效，**内核零改动**。方案结论准确。

> 附带确认：`buildCtx` 注释（`:1006-1009`）明确 `extra` 只承载 **modelId + `RunOverrides.PASSTHROUGH_KEYS`**（回合/token 白名单），**权限级别、规则链、沙箱一律不在此列** —— 与 v2 §2.5 白名单一致，方案未越界。

### 1.2 `@` 文件补全 —— **成立**

`FileService` 接口已有列目录能力：`Mono<ExecResult> list(String workspaceId, String path)`（`FileService.java:22`）。补全器只需按 `@` 触发、调 `list` 逐级展开，候选天然限定在**工作空间边界内**（与执行臂同源），安全边界无需另造。

### 1.3 `/` 命令菜单 —— **成立**

`SlashCommandRegistry` 已提供 `completions(prefix)`（`:143-150`），且每个命令已有 `summary()`。JLine 的 `Candidate` 构造支持描述文本，当前 `CliRunner:239` 只 `new Candidate(c)`、未填描述与分组——**补上这两个字段即可得到带说明的菜单**，无需换库。

### 1.4 `/mcp` `/agents` 的注册表接口 —— **接口存在（但见 §2.3 的前提遗漏）**

- MCP：`McpRegistry`（接口）、`LocalMcpRegistry`（`init`/`setEnabled`/`scheduledReload`）、`ConnectionManager.status(serverId)`、`McpHealthProbe.probe()` —— 足以实现「列出服务 + 连接状态 + 工具数」。
- 技能：`SkillRegistry`、`LocalSkillRegistry.list()/listEnabled()/find(id)/isReadOnly(id)`、`SkillDef{id, name, description, triggers, ...}` —— 足以实现「列出技能 + 启用态 + 描述」。

### 1.5 不引入全屏 TUI —— **成立，三条论据均有据**

| 方案论据 | 复核 |
|---|---|
| Web 优先、CLI 是第二通道 | ✅ 与 D11 一致 |
| 全屏需字符栅格 diff 引擎＝重写渲染层 | ✅ Claude 是「React → Ink 虚拟 DOM → Screen diff」三层；Codex 是 ratatui 帧缓冲 |
| conpty 风险 + 丧失 scrollback | ✅ 且 Claude 官方把全屏定位为**研究预览**、默认 inline |
| 现有轮末状态行是自觉取舍 | ✅ `StatusBar.java:8-12` 注释原文：「为什么不是底部常驻刷新条……有意识的取舍，不是未实现」 |

**这是方案里最扎实的一条决策**。

---

## 2. 需修正项（按风险从高到低）

### 2.1 ❌【范畴错误】`/plan` —— 本项目没有「用户模式」这个概念

**方案原文**（§1.2）：`/plan` 是「三家的交集，我们都没有」；§2.2 把「模式运行时切换」列为 **▲▲ 高缺口**；§3.2 把它放进 **A 层 P1**。

**核实结果**：本项目的 `Phase` 与三家的 `/plan` **不是同一个东西**。

```java
// Orchestrator.java:502 —— PLAN 是主循环自己发起的编排阶段
EngineRunResult planResult = engine.run(withSuppress(ctx, Phase.PLAN, goal), Phase.PLAN, goal).block();

// ActScheduler.java:36 —— ACT 同样是编排推进
ConversationCtx actCtx = ctx.withPhase(Phase.ACT);

// ConversationManager.java:1027 —— 默认就是 ACT
.phase(Phase.ACT)
```

| | 三家的 `/plan` | 本项目的 `Phase.PLAN/ACT/ASK` |
|---|---|---|
| 是什么 | **用户的偏好开关**（"先给我计划，别直接改文件"） | **一次运行内部的编排阶段** |
| 谁决定 | 用户 | 主循环（`Orchestrator` / `ActScheduler` / `Replanner`） |
| 生命周期 | 跨多轮，直到用户关掉 | 单次运行内，随流程流转 |
| 能否 per-run 覆盖 | 是 | **无通道**（全仓无 `extra("mode")` 读取点） |

**结论**：
- 这不是「我们缺一个命令」，而是**两家在说不同的概念**。把它列进差距矩阵属于**范畴错误**。
- 正确的做法只有两条：**(a) 删除此项**；**(b) 把它重新定义为「本会话后续轮次先出计划」这一*新增语义*** —— 那就**不是纯 CLI 命令**，而是需要内核新增一个 per-run 偏好位，应归 **C 层**，且要新写一篇小设计（提示词如何注入、早停阈值如何变、与现有 PLAN 阶段如何共存）。

**修正建议**：从 **A 层 P1 移除**。若要保留意图，改写为「C3′：新增 per-run『先计划』偏好（新语义，非对齐）」并置 P3。

### 2.2 ❌【真实疏漏】`/init` 必须生成**内核能解析**的规则文档格式

**方案原文**（§3.2 A3）：「生成/补全 `LUCKY.md` 骨架（项目上下文文件）」。**未提任何格式约束。**

**核实结果**：本项目的规则文档是**结构化格式**，由 `RuleDocumentCodec` 双向解析（`RuleDocumentCodec.java`）：

- 规则分节必须以 **`## ` 开头**（`###` 不算边界）；
- 分节标题可带控制标记 **`[P]`（项目作用域）** 与 **`[ ]`（停用）**；
- 设计目标写着「**往返幂等**——`parse(render(items))` 应与 `items` 等价，保证 Web 编辑器『读取 → 修改 → 保存』不会因格式漂移反复改写用户文件」；
- **没有任何 `##` 分节的文件 `isStructured()` 返回 false**，调用方退回「整文件作为单条规则注入」的旧行为。

**风险**：若 `/init` 按「漂亮的 Markdown」手写模板输出（三级标题、列表、无序要点），则：
1. 内核识别不到分节 → 要么整份当一条巨型规则注入，要么按旧行为失效；
2. 一旦 Web 端规则编辑器打开这个文件再保存，`RuleDocumentCodec.render()` 会**重排格式**，用户看到「我刚生成的骨架被改写了」。

**修正建议**：`/init` **必须调用 `RuleDocumentCodec.render(header, items)`** 生成文件，**禁止手写模板字符串**。同时 §6 增一条现场确认：规则文档的**权威存储位置**（工作空间根？`<workspace>/.lucky/`？）与 **Web 端 `RuleController` 的关系**——避免「CLI 写一份、Web 读另一份」。

### 2.3 ⚠️【遗漏前提】`/mcp` `/agents` 需要先给 `CliServices` 扩容

**方案原文**：「依赖已补，具备实现基础」——**这句话只对了一半**。

```java
// CliServices.java:22-30 —— 当前全部字段（9 个），无任何 registry
public record CliServices(CliSessionService sessions,
                          ConversationManager conversations,
                          ConversationStateManager stateManager,
                          WorkspaceConfig workspaces,
                          WorkspaceDirs dirs,
                          MetricsCollector metrics,
                          FileService files,
                          PermissionService permissions,
                          CliConfigResolver configResolver) {}
```

`agent-cli/pom.xml` 确实依赖了 `agent-skill` / `agent-mcp`（`:62-69`），所以 `McpRegistry` / `SkillRegistry` 的 Bean **在 CLI 的 Spring 上下文里是可注入的**——但 **CLI 代码根本拿不到它们**，因为 `CliServices` 没装。

**修正建议**：`/mcp` `/agents` 的**第一步是给 `CliServices` 加两个字段**（`McpRegistry mcp` / `SkillRegistry skills`），并在装配点传入。成本仍小，但方案应把这一步显式写进任务，否则会误判为「纯加命令」。

### 2.4 ⚠️【术语不准】`/agents` 应叫 `/skills`

三家的 `/agents` 指 **subagent**（独立上下文的分身）。本项目的 `agent-skill` 是**技能**（`SkillDef`：可被语义匹配、按需注入的指令包），且「多 Agent 隔离子代理（并发≤4）」是**运行时并发概念，没有静态注册表可列**。

叫 `/agents` 会让用户以为能列出「有哪些子代理」，实际列出的是「有哪些技能」——**名实不符**。

**修正建议**：命令名用 **`/skills`**（或 `/skill`），帮助文本写「列出可用技能与启用状态」。若将来真要做子代理管理，再单开 `/agents`。

### 2.5 ⚠️【低估复杂度】`/fork` 不是「复制一个文件」

**方案原文**：「`SessionRepository` 是 JSONL append-only……fork 就是把已有行复制到一个新文件 + 新头部，成本可控」。

**核实结果**：会话是**双文件**结构：

```java
private Path metaFile(String sessionId)     // SessionRepository.java:364
private Path messageFile(String sessionId)  // :368
// 另有：upsertMeta(:51)、loadMeta(:319)、listByUser(:333) 均依赖 meta
```

`listByUser` 遍历的是 **meta**；`loadMessages`/`loadForReplay` 读的是 **message**。**fork 必须同时**：
1. 复制 message 文件到新 sessionId；
2. **新建 meta**（新 sessionId、保留 userId/workspaceId、新 title）；
3. 否则新会话在 `/sessions` 里**根本不出现**（meta 缺失）。

**修正建议**：描述改为「fork = 复制 message + 重建 meta（双文件）」，实现时**复用 `upsertMeta`**，不要自己拼 meta JSON。成本仍是「中」，但方案的表述会误导实现者。

### 2.6 ⚠️【高估价值】`/diff` 受**权限档**约束

方案把 `/diff` 当成「高频」项（▲▲ 高）。核实 `ShellPrefix` 后需降级：

```java
// ShellPrefix.java:65 → fileService.exec(workspaceId, command) 走完整权限链
// /help 已明示：「前提：当前工作空间权限档须为「全部权限」，否则命令执行会被拒」
// ShellPrefix.java:75-92 —— 若返回 ASK_REQUIRED，还要过 ApprovalHandler 四档裁决
```

即：`/diff` 若走 `!` 同通道（这是**唯一正确**的做法，不得绕过执行臂），则在**只读工作空间下会被拒**。方案未提这个前提。

**修正建议**：保留该项，但**标注约束**「需工作空间具备执行权限」；或者在文档里明确它是「可执行权限下的便利封装」，不承诺在只读空间可用。

### 2.7 ⚠️【实现风险】`@` 补全：同步回调 vs 异步 `Mono`

`FileService.list` 返回 **`Mono<ExecResult>`**，而 JLine 的 `Completer.complete(...)` 是**同步回调**。在补全器里 `block()` 一个 Mono 有**卡住输入**的风险（尤其首次触发、目录大、执行臂有往返时延）。

**修正建议**：进 §5 风险表——「补全候选**异步预取 + 缓存**；不可在 `complete()` 内直接 `block()` 请求执行臂」。方案 §5 只提了「限定深度」，不够。

---

## 3. 方案遗漏的机会（增量发现）

### 3.1 🔍 `/rewind`（回退到任意历史点）比重做 `/undo` 更容易

方案把 `/rewind` 归为「◐ 部分被 /undo 覆盖」、未列改良项。但核实发现**内核基础已经齐了**：

```java
public synchronized int truncateAfter(String sessionId, String ts)   // SessionRepository.java:222
```

配合既有的快照链（`CheckpointSnapshoter`：`checkpoints` + `SnapshotIndex`；`Compensator`：按 checkpointId 恢复文件；`Snapshot{checkpointId, workspaceId, parentId, affectedFiles}`），「回退到上一轮之前」与「回退到任意一**检查点**」在数据层**都已具备**。

**建议**：与其只做「单轮回滚」，不如评估 **`/rewind [序号|checkpointId]`** —— 这是**低成本、高价值**项，方案漏了。可放 B 层或 C 层（取决于是否需要内核暴露 checkpoint 列表）。

### 3.2 🔍 快照链的存在意味着「每步可回滚」是既有能力

`CheckpointSnapshoter.snapshot(...)` 会为受影响文件建 checkpoint 并串成 `parentId` 链。这意味着方案的「恢复文件检查点」不是新能力，**`/undo` 只是它的最小用法**。§3.4 的 C 层可据此把「会话回退」与「文件回退」统一规划，而不是各写一个命令。

---

## 4. 红线复检

| 检查项 | 结论 |
|--------|------|
| D 层「不做」清单（提权/云托管/计费/插件市场/daemon） | ✅ 与 D1/D2/D3 一致，无越界 |
| N1「只读暴露安全态、不提权」 | ✅ 与 D2 一致 |
| N2「复用既有内核入口，不新造存储」 | ✅ 但 `/init` **触碰了新边界**（见下） |
| N3「生效即回显实际值」 | ✅ 与 v2 §2.5 一致 |
| **方案未声明的风险** | ⚠️ **`/init` 写 `LUCKY.md` 与 Web 端 `RuleController`（规则文档编辑器）构成双写面**。规则文档此前是 **Web 端管理**（有 `RuleController`、`RuleStore`、`BasePromptStore`）；CLI 新增写入方，需要先确认「谁是权威、如何避免互相覆盖」。方案 §3.1 只说「不新造存储」，但**没有回答双写冲突**。 |

---

## 5. 修正后的优先级建议

| 原方案 | 修正 | 理由 |
|--------|------|------|
| **A2 `/plan`（P1）** | ⬇️ **移除 / 降为 P3 新语义** | 范畴错误：Phase 是编排阶段，不是用户模式（§2.1） |
| **A3 `/init`（P1）** | ⬆️ **保留但加格式与双写约束** | 必须过 `RuleDocumentCodec`；先定权威源（§2.2、§4） |
| **A5/A6 `/mcp` `/agents`** | ✅ 保留，**补 `CliServices` 扩容**；`/agents` → `/skills` | 遗漏前提 + 术语（§2.3、§2.4） |
| **A4 `/diff`（P1）** | ⬇️ **降为 P2** | 受权限档约束，非普遍可用（§2.6） |
| **C1 `/fork`** | ✅ 保留，描述改为双文件 | 低估复杂度（§2.5） |
| **B1 `@` 补全（P1）** | ✅ 保留，§5 补「异步预取、勿 block」 | 实现风险（§2.7） |
| **B2 `/` 菜单（P1）** | ✅ **保留，建议提到批次 5 首位** | 证据最扎实、成本最低、感知最强（§1.3） |
| **（新增）`/rewind`** | ➕ 建议进 B/C 层评估 | 内核基础已具备，方案漏项（§3.1） |

**修正后的批次 5 建议**：`/` 命令菜单（B2）→ `@` 文件补全（B1）→ 给 `CliServices` 扩容（A5/A6 前置）→ `/skills` + `/mcp`。**`/plan` 移出本批。**

---

## 6. 建议的下一步

1. **先把 §2.1（`/plan`）从 v3 正文移出**：它会让读者误判这是一个"补命令"的活；实际是新语义设计。
2. **`/init` 前先定「规则文档权威源」**：查 `RuleController` 的写路径与存储位置，确认 CLI 写入不与之冲突；不确定就先不做 `/init`。
3. **修正 v3 的 §3.2 / §3.3 / §3.4 三张表**（按 §5 建议），再按批次 5 开工。
4. 其余项（`/model`、`/diff`、`/mcp`、`/skills`、`@` 补全、`/` 菜单、`/fork`）证据充分，**可直接进批次 5/6**。

---

**一句话**：**方案的方向、边界与"不引入全屏 TUI"的判断都站得住；问题集中在"对标时的语义映射"——把三家的用户级命令直接对到本项目的编排级概念上，`/plan` 是典型。修掉语义错位与 3 处前提遗漏后，批次 5 可以开工。**

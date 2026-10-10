# CLI 方案 v3.1：对标 Codex / Claude Code / dsh 的执行版改良方案

> 版本：**v3.1（现行事实源）**　适用范围：`web/app/agent-cli`
> **来源**：本文件 = `CLI方案-v3.md`（v3.0 对标）+ `CLI方案-v3-合理性审查.md`（修订）**合并落盘后的最终可执行版**。v3.0 的两处错误结论（§3.2 的 A2、§3.4 的 C2 依赖）已被本文件取代，**以本文件为准**。
> **文档分工**：`CLI方案-v1.md` = 设计期调研；`CLI方案-v2.md` = 决策定稿（已定什么 / 批次 1–4）；**`CLI方案-v3.1.md`（本文件）= 落地后对标 + 修正后的执行方案**。
> **前提**：v2 的批次 1–4 **已全部落地并验收**（P0 六项 + 统一预算读取点 + enforcer + JLine REPL + `-w` 多工作空间 + 三级配置 + P2 六项），现有 CLI 具备 14 个 slash 命令、JLine 行编辑、headless 三格式、静态蓝色 Banner、退出码契约，**agent-cli 121 用例全绿**。

---

## 0. 相对 v3.0 的修订清单（先看这个）

| # | v3.0 原结论 | v3.1 修订 | 依据（已实读代码） |
|---|-------------|-----------|---------------------|
| 1 | `A2 /plan` 列 **A 层 P1**（三家交集、我们缺） | ❌ **删除**。本项目 `Phase.PLAN/ACT/ASK` 是**一次运行内部的编排阶段**，不是用户模式；两家在说不同概念 | `Orchestrator:502 engine.run(ctx, Phase.PLAN, goal)`、`ActScheduler:36 ctx.withPhase(Phase.ACT)`、`ConversationManager:1027 .phase(Phase.ACT)`；全仓**无** `extra("mode")` 读取点 |
| 2 | `A3 /init` 「走执行臂写文件」 | ⚠️ **改为复用内核 `RuleStore`**（`ruleStore.save`）。走执行臂会**绕过 `RuleDocumentCodec`** 的 header 提取与幂等，正是要避免的坑 | `RuleStore.FILE_NAME="LUCKY.md"`、`RuleStore.save():130` 经 `RuleDocumentCodec.render(header, items)`；Web 端同一入口 |
| 3 | `A3 /init` 未提**双写冲突** | ✅ **已解**：Web `RuleController`（`/api/rules` POST）与 `/init` **同走 `RuleStore.save`** → 单一写入面，无冲突 | `RuleController.doSave:134 → ruleStore.save` |
| 4 | `A3 /init` 未提**已存在** | ✅ **新增**：`WorkspaceConfigCenter` 已在工作空间根**幂等写入默认 `LUCKY.md` 模板**（不覆盖用户内容） → `/init` 价值收窄为「**补全缺失分节 / 重建骨架 / 展示当前规则**」 | `WorkspaceConfigCenter.WS_RULES_FILE="LUCKY.md"`（`:37`）、`:118-128` 幂等写模板 |
| 5 | `A5 /mcp` `A6 /agents` 「依赖已补，具备实现基础」 | ⚠️ **补前提**：`CliServices` **只有 9 个字段、不含任何 registry**，需先**扩容**才能注入；且 `/agents` **改名 `/skills`**（本项目是技能 `SkillDef`，非 subagent） | `CliServices.java:22-30`；`LocalSkillRegistry.list/listEnabled/isReadOnly`；`LocalMcpRegistry.list/find` |
| 6 | `A4 /diff` 列 **P1（高频）** | ⬇️ **降 P2**，标注「**需工作空间具备执行权限**（权限档=全部权限），否则被拒」 | `ShellPrefix:65 fileService.exec(...)` 走完整权限链、`:75-92` ASK_REQUIRED 四档裁决 |
| 7 | `B1 @补全` 只提「限定深度」 | ⚠️ **补实现风险**：`FileService.list` 返回 `Mono`，而 `Completer.complete` 是同步回调 → **必须异步预取 + 缓存，禁止在 `complete()` 内 `block()`** | `FileService.list(...): Mono<ExecResult>` |
| 8 | `C1 /fork`「复制行到新文件」 | ⚠️ **修正**：会话是 **meta + message 双文件**，fork 须**复制 message + 复用 `upsertMeta` 重建 meta**，否则新会话在 `/sessions` 不出现 | `metaFile():364`、`messageFile():368`、`upsertMeta(SessionRef,String):51`、`listByUser():333` |
| 9 | 方案**漏** `/rewind` | ➕ **新增**：`/rewind` 基础已齐备，**比 `/undo` 改造更值得** | `SessionRepository.truncateAfter(sessionId, ts):222`、`removeCheckpoints():172`、`CheckpointSnapshoter`/`Compensator`/`SnapshotIndex` |
| 10 | `B5` 「增补首 token 延迟/吞吐」 | ⬇️ **降级**：`SessionMetrics.cacheHitRate()` **已有可直接展示**；延迟/吞吐**需新增采集**（先评估，不硬承诺） | `SessionMetrics.cacheHitRate():133`、`snapshot():157`（**无** latency/throughput/firstToken 字段） |
| 11 | `C2 per-run mode` 依赖 A2 探测 | ✅ **独立化**：A2 已删，C2 变为「**若将来要新增用户级 mode**」= 新语义，归 **C3′（P3）**，不再挂在 A2 下 | 同上 #1 |

**一句话**：v3.0 的**方向、边界、「不引入全屏 TUI」的判断全部保留**；修订集中在**对标时的语义映射**（把用户级命令直接对到编排级概念）与**3 处前提遗漏 + 2 处价值误判**。

---

## 1. 保留的结论（v3.0 第 1、2 章，无需重写）

三家操作模型解构与差距矩阵（v3.0 §1、§2）**经复核基本准确**，此处只摘要点，细节见 `CLI方案-v3.md`：

- **渲染范式**：Codex 全屏 ratatui / dsh 全屏 alt screen / **Claude 默认 inline（全屏仅「研究预览」）** → 佐证我们**保持 inline**。
- **命令面**：三家把模型切换、模式、上下文、MCP/子代理、diff 做成**会话内命令**；我们仅 14 个，**`/model` 是三家共同的真交集**（`/plan` 不是，见 §0-#1）。
- **心流设计**：`@` 补全、`/` 菜单、工具卡片折叠、Tab 排队 —— **几乎全是我们的空白区**，也是「好用 vs 能用」的分水岭。
- **反向验证红线**：三家**都没做**内置 RAG、自建模型托管、CLI 内跑可视化工作流 → 与 D1/D3/决策 1 一致。

---

## 2. 修正后的改良方案

### 2.1 设计原则（沿用 v2 + v3.0 三条边界）

沿用：**启动期 Picocli / 会话内手写分发**；**per-run 参数走 `ConversationCtx.extra`**（先例键 `modelId`/`confirm`/`personaId`/`suppressStop`）；**能力模块禁 `starter-web`**、**`agent-cli` 禁 `agent-workflow`**（enforcer 固化）。

| # | 边界 | 理由 |
|---|------|------|
| N1 | **只读地暴露安全态，不提供任何提权通道** | D2 不可动摇；三家 Shift+Tab 提权**不抄**，只做「看」 |
| N2 | **新命令一律复用既有内核入口，不新造存储/协议** | 会话 → `SessionRepository`；MCP → `agent-mcp`；技能 → `agent-skill`；**规则 → `RuleStore`**（本版新增此项） |
| N3 | **命令默认不静默**：生效即回显实际值 | 复用 v2 §2.5（`max-turns=5（覆盖自 CLI）`），避免「改了没生效」 |
| N4 | **不碰执行臂边界**：用户级配置读取/展示走内核只读接口；**只有需要"执行"的动作才走执行臂** | 与 D2 一致；`/init` 写规则属「配置编辑」（同 Web），走 `RuleStore`，**不是**执行臂 |

### 2.2 A 层 —— 纯 CLI 侧命令（修正后）

| # | 命令 | 行为 | 落点（精确） | 前置 | 优先级 |
|---|------|------|--------------|------|:------:|
| **A1** | **`/model [名称\|序号]`** | 无参列出可用模型（当前标 ●），带参切换**本会话后续轮次**模型 | `SlashCommandRegistry` 新命令；`CommandContext`/`SessionHolder` 加**可变 `modelId`**；`ReplLoop.baseExtra()`（`:187-190`）改读该字段而非 `options.model` | 无（内核零改动） | **P1** |
| **A5** | **`/mcp`** | 列出已配置 MCP 服务 + 连接状态 + 可用工具数 | `CliServices` **加 `McpRegistry`**（可再注 `ConnectionManager`/`McpHealthProbe`）；读 `LocalMcpRegistry.list()/find()`、`ConnectionManager.status(id)`、`McpHealthProbe.probe()` | **`CliServices` 扩容** | P1 |
| **A6** | **`/skills`**（原名 `/agents`） | 列出可用技能 + 启用态 + 描述 | `CliServices` **加 `SkillRegistry`**；读 `LocalSkillRegistry.list()/listEnabled()/isReadOnly()` | **`CliServices` 扩容** | P1 |
| **A3** | **`/init`** | 在当前工作空间**补全/重建** `LUCKY.md` 结构化骨架，并回显当前规则 | `CliServices` **加 `RuleStore`**；**必须** `ruleStore.save(wsPath, "project", items)`（内部经 `RuleDocumentCodec.render`）。**禁止手写模板字符串，禁止走执行臂直写** | `CliServices` 扩容 | P2 |
| **A4** | **`/diff [--stat]`** | 工作空间 `git diff`，着色 + 统计 | 复用 `ShellPrefix` → `FileService.exec(...)` 通道（**唯一正确做法，不得绕过执行臂**） | **需权限档=全部权限**，否则被拒 | **P2** |

> **A1 为何内核零改动**：`ConversationManager.buildCtx:1011-1016` **每轮**从 `UserInput.extra` 重读 `modelId`（`modelIdOf:1069`），**非**会话创建时固化。CLI 每轮塞 extra 即生效。
> **A3 为何不用执行臂**（本版关键修正）：规则文档是**结构化格式**（`## 分节` + `[P]`/`[ ]` 标记），由 `RuleDocumentCodec` 双向解析、目标「往返幂等」。若按"漂亮 Markdown"手写模板，会 ① 内核识别不到分节退回旧行为；② Web 规则编辑器一保存就被 `render()` 重排（用户看到"文件被改写"）。**走 `RuleStore.save` 才能与 Web 同源。**
> **A5/A6 的第一步不是"加命令"，而是"给 `CliServices` 加两个字段"**——否则 Bean 在上下文里可注入，但 CLI 代码拿不到。

### 2.3 B 层 —— 交互层升级（修正后）

| # | 能力 | 行为 | 落点（精确） | 优先级 |
|---|------|------|--------------|:------:|
| **B2** | **`/` 命令菜单** | 输入 `/` 弹**可过滤**列表（**带 summary 描述**），↑↓ 选、Enter 确认 | `SlashCommandRegistry.completions():143-150` 已有；`CliRunner:239` 现只 `new Candidate(c)`，**补 `descr`/分组即可**（`SlashCommand.summary()` 已有） | **P1（批次 5 首位）** |
| **B1** | **`@` 文件路径补全** | 空格后输入 `@` → 弹当前工作空间文件树（Tab/↑↓ 选，支持子目录） | `CliRunner.installCompleter:233-239` + 新 `WorkspacePathCompleter`；数据源 `FileService.list(workspaceId, path)`——**`Mono`，须异步预取 + 缓存，禁止在 `complete()` 内 `block()`** | **P1** |
| **B3** | **工具卡片合并折叠** | 相邻 `action` + `tool_result` 合并为一行摘要（`🛠 read · ✅ 12 行`），快捷键/`--verbose` 展开 | `EventRenderer.renderAction/renderToolResult`（`:111-151`）、`DATA_PREVIEW_LINES`（`:26`） | P2 |
| **B4** | **Tab 排队（run queue）** | 运行中敲内容 + Tab → 入队，本轮结束后自动作为下一轮提交 | `ReplLoop` 增队列 + `CliTurnExecutor` 回调 | P2 |
| **B5** | **`/tokens` 增补指标** | **先接已有的 `cacheHitRate`**；首 token 延迟 / 吞吐 `tok/s` **需评估是否补采集** | `SessionMetrics.cacheHitRate():133`、`snapshot():157`（当前**无**延迟/吞吐字段） | P2 |
| **B6** | **`/rewind [序号\|checkpointId]`**（新增） | 会话回退到任意历史点 / 检查点（不止上一轮） | `SessionRepository.truncateAfter(sessionId,ts):222` + `CheckpointSnapshoter`/`Compensator`/`SnapshotIndex`（`removeCheckpoints():172` 已备） | P2 |

> **B2 是本方案证据最扎实、成本最低、感知最强的一项** —— 纯 JLine 层，JLine `Candidate` 原生支持 `value/descr`，只是没填。
> **B1 安全边界**：补全只读**目录名**，不读文件内容；候选限定在当前工作空间 realpath 之内（与执行臂同源）。
> **B6 为何比重做 `/undo` 更值**：`truncateAfter` 与快照链**都已存在**，`/undo` 只是它的最小用法。

### 2.4 C 层 —— 需小改内核

| # | 能力 | 内核改动 | 优先级 |
|---|------|----------|:------:|
| **C1** | **`/fork` 会话分叉** | `SessionRepository` 新增 `fork(sessionId)`：**① 复制 message 文件到新 sessionId；② 复用 `upsertMeta(newRef, title)` 重建 meta**（双文件，缺 meta 则 `/sessions` 不显示）；append-only 天然隔离 | P2 |
| **C3′** | **per-run「先计划」偏好**（**新语义，非对齐**） | 若要保留"先给我计划再动手"的意图：新增一个 per-run 偏好位（提示词注入 + 早停阈值），**与现有 PLAN 编排阶段共存**，需**单独小设计** | P3 |
| **C3** | **生命周期 hooks**（`PreToolUse`/`PostToolUse`/`Stop`…） | 一整套事件总线 + 配置 + 沙箱；与「CLI 是第二通道」定位不匹配 | P3（**不承诺**） |

### 2.5 D 层 —— 明确不做（守红线，5 项）

| # | 不做项 | 理由 |
|---|--------|------|
| D1 | `Shift+Tab` / `--dangerously-*` 类**运行时提权** | 违反 D2 |
| D2 | 云端任务托管 / 远程会话 | 违反 D1/D3「零托管」 |
| D3 | 计费、额度、`--max-budget-usd` 计费语义 | 违反「不涉及计费」 |
| D4 | 插件市场 + 无审核分发 | 供应链风险 |
| D5 | client/server 分离 + 常驻 daemon | 本地单用户，多进程只增运维 |

### 2.6 关键决策：不引入全屏 TUI（保留 v3.0，论证有效）

**结论：保持 inline（行式流式 + 轮末状态行）。** 四条论据经复核全部有据：① Web 优先（D11），CLI 是第二通道；② 全屏需**字符栅格 diff 引擎** = 重写渲染层（Claude 三层管线 / Codex 帧缓冲）；③ Windows conpty 全屏脆弱 + 丧失原生 scrollback（**Claude 官方也默认 inline**）；④ 现有轮末状态行是**自觉取舍**（`StatusBar.java:8-12` 注释原文）。

**吸收 vs 不吸收**：吸收心流元素（`/` 菜单、`@` 补全、卡片折叠、统计、Tab 排队）；不吸收渲染架构（全屏 alt buffer、三层虚拟 DOM、常驻输入框、屏内滚动）。

**未来重开触发条件**：当 CLI 需要 **① 内联 diff 逐行确认** 或 **② 后台任务面板** 时，全屏才有不可替代价值。**不是永不，是此时不做。**

---

## 3. 分批路线图（修正后）

| 批次 | 内容 | 性质 | 完成判据 |
|:----:|------|------|----------|
| **5** | **B2 `/` 命令菜单 → B1 `@` 文件补全 → `CliServices` 扩容（A5/A6 前置）** | 纯 CLI 交互层，无内核改动 | 实机：敲 `/` 弹**带描述**菜单、敲 `@` 弹文件树；单测守约 |
| **6** | **A1 `/model` + A5 `/mcp` + A6 `/skills` + A4 `/diff`** | 纯 CLI 命令层 | 各命令端到端可验证；`/model` 切换后**下一轮**模型确实变了（看状态行/`/tokens`） |
| **7** | **A3 `/init`（`RuleStore`）+ B3 工具卡片折叠 + B5 指标（先接 cacheHitRate）** | A3 经内核 `RuleStore` | `/init` 只在工作空间内落**可被 `RuleCodec` 解析**的文件；Web 规则页读同一份 |
| **8** | **C1 `/fork` + B6 `/rewind` + B4 Tab 排队** | 含内核小改 | 全量 `clean test` 零回归；`/fork` 后原会话不被污染；`/rewind` 后 `/sessions` 仍可见新会话 |
| 观察 | C3′ per-run「先计划」偏好、C3 hooks | P3，不承诺 | — |

**批次 5 先行**：`/` 菜单 + `@` 补全都是 JLine 层、无内核依赖、用户感知最强，且互不耦合。

---

## 4. 批次 5 任务卡（可直接开工）

> 每张卡 = 一个可独立提交的改动，含落点、前置、验收。

**T5-1 `CliServices` 扩容（A5/A6 前置）**
- 改动：`CliServices` record 增 `McpRegistry mcp`、`SkillRegistry skills`（后续批次再加 `RuleStore rules`）；装配点 `CliRunner:186` 传入。
- 验收：编译通过；`SlashCommandRegistryTest` 的 `new CliServices(...)` 同步更新；新增两个字段可被命令取到。

**T5-2 `/` 命令菜单（B2）**
- 改动：`CliRunner:239` 的 `new Candidate(c)` → `new Candidate(c, summary, group, null)`（带描述与分组）；必要时 `SlashCommand` 补 `group()`。
- 验收：实机敲 `/` 弹出带说明的候选；继续打字可过滤；Tab 补全行为不回退；新增单测断言 `Candidate.descr` 非空。

**T5-3 `@` 文件路径补全（B1）**
- 改动：新建 `WorkspacePathCompleter`，在 `installCompleter` 中按"光标前是 `@` 且非 `@` 命令上下文"触发；数据源 `FileService.list`，**异步预取 + 本地缓存**，`complete()` 只读缓存。
- 验收：实机敲 `@` 弹当前工作空间目录；大目录不卡输入（首触发异步）；候选不越出工作空间 realpath；单测覆盖"缓存命中/未命中不阻塞"。

---

## 5. 风险与取舍（修正后）

| # | 风险 | 表现 | 缓解 |
|---|------|------|------|
| 1 | **补全拖慢输入** | `@` 补全扫描大目录树卡顿 | 限深度/数量；**异步预取 + 缓存**；**禁止 `complete()` 内 `block()` Mono**（#0-7） |
| 2 | **`/model` 切换与内核不同步** | CLI 认为切了，某条主环不认 | 复用 `extra.modelId`（内核每轮重读）；切换后**回显实际生效模型**（N3） |
| 3 | **`/init` 写坏规则文件** | 手写模板致 `RuleCodec` 解析失败、或与 Web 互相覆盖 | **强制走 `RuleStore.save`**（同源）；**不手写模板**（#0-2/#0-3） |
| 4 | **`/diff` 在只读空间不可用** | 用户敲 `/diff` 被权限档拒 | 文档与 `/help` 标注「**需执行权限**」；被拒时给明确提示而非静默（#0-6） |
| 5 | **`/fork` 后会话不显示** | 只复制 message、漏 meta | fork **必须** `upsertMeta` 重建 meta（#0-8） |
| 6 | **命令面膨胀** | 14 → 22+，`/help` 变长 | `/help` 按**主题分组**（会话/模型/扩展/诊断）；超 ~35 时评估 Picocli 子命令模型（v2 §1.4 已留口） |

---

## 6. 实施期需现场确认（非决策项）

| # | 事项 | 口径 | 阻塞 |
|---|------|------|------|
| 1 | **模型列表来源**（`/model` 无参列什么） | 复用既有模型注册表（`settings.json` / `ModelController`），**不新造清单** | A1 |
| 2 | `McpRegistry` 的**服务/工具查询接口** | 现场读接口，`/mcp` 只调不改 | A5 |
| 3 | `SkillRegistry` 的**注册表查询接口** | 同上 | A6 |
| 4 | **规则文档权威存储位置**与 **Web `RuleController` 的关系** | 已核：全局 `<frameworkRoot>/LUCKY.md`、项目 `<workspacePath>/LUCKY.md`，均经 `RuleStore`；确认 CLI 只走同一入口 | A3 |
| 5 | `SessionMetrics` 是否可扩**首 token 延迟/吞吐** | 现无字段；有则 B5 直接展示，无则评估补采集 | B5 |
| 6 | `@` 补全的**性能上限**（工作空间最大目录规模） | 定扫描深度/数量阈值 | B1 |

---

## 附录 A：证据索引（本版新增/更新）

| 结论 | 证据位置 |
|------|----------|
| `modelId` 为 per-run 键，内核每轮重读 | `ConversationManager.buildCtx:1011-1016`、`modelIdOf:1069`、`MODEL_ID_KEY`（`:63`） |
| `Phase` 是编排阶段、非用户模式 | `Orchestrator:502`、`ActScheduler:36`、`ConversationManager:1027` |
| 全仓无 per-run mode 读取点 | grep `extra("mode")` 无命中；主环 mode 由 `Phase` 驱动 |
| 规则文档=结构化格式、双向幂等 | `RuleDocumentCodec`（`parse/render/isStructured/extractHeader`） |
| 规则存储唯一入口 `RuleStore` | `RuleStore.FILE_NAME="LUCKY.md"`、`globalFile():54`、`projectFile():59`、`save():130` |
| Web 规则写入同源 | `RuleController`（`/api/rules` POST `doSave:134 → ruleStore.save`） |
| 工作空间已幂等写默认 `LUCKY.md` | `WorkspaceConfigCenter.WS_RULES_FILE`（`:37`）、`:118-128` |
| `CliServices` 无 registry（9 字段） | `CliServices.java:22-30`；装配点 `CliRunner.java:186` |
| 技能注册表接口 | `LocalSkillRegistry.list():70 / listEnabled():75 / isReadOnly():111` |
| MCP 注册表/连接/健康接口 | `LocalMcpRegistry.list():58 / find():68`、`ConnectionManager.status():42`、`McpHealthProbe.probe():33`、`McpSession.tools():70` |
| `@` 补全数据源为 `Mono` | `FileService.list(workspaceId, path)` |
| `/` 菜单已具 `completions`，仅缺 `descr` | `SlashCommandRegistry.completions():143-150`、`CliRunner:239`、`LineEditor.MutableCompleter:187-203` |
| `SessionMetrics` 有 cacheHitRate、无延迟/吞吐 | `SessionMetrics.cacheHitRate():133`、`snapshot():157` |
| 会话双文件 + fork 相关 | `metaFile():364`、`messageFile():368`、`upsertMeta():51`、`loadForReplay():150`、`listByUser():333` |
| rewind 基础齐备 | `truncateAfter():222`、`removeCheckpoints():172`、`CheckpointSnapshoter`/`Compensator`/`SnapshotIndex` |
| 工具结果折叠现状 | `EventRenderer.DATA_PREVIEW_LINES=6`（`:26`）、`renderAction/renderToolResult`（`:111-151`） |
| 状态行为自觉取舍 | `StatusBar.java:8-12` |
| 静态蓝色 Banner 已落地 | `repl/Banner.java`（`BRAND="lucky_agent"`，65 列，无动画） |

## 附录 B：三家命令面速查（供 `/help` 分组与命名参考）

**Codex CLI**（全屏 TUI）：`/model` `/permissions` `/plan` `/new` `/resume` `/fork` `/compact` `/clear` `/diff` `/review` `/init` `/status` `/statusline` `/theme` `/agent` `/apps` `/plugins` `/mcp` `/mention` `/copy` `/ps` `/stop` `/experimental` `/personality` `/fast` `/logout` `/feedback` `/debug-config` `/exit`

**Claude Code**（inline / `--tui fullscreen`）：`/model` `/effort` `/fast` `/plan` `/permissions` `/sandbox` `/context` `/cost` `/usage` `/compact` `/clear` `/resume` `/branch` `/rename` `/rewind` `/diff` `/init` `/memory` `/agents` `/hooks` `/mcp` `/plugin` `/skills` `/ide` `/export` `/copy` `/background` `/tasks` `/doctor` `/status` `/statusline` `/theme` `/vim` `/keybindings` `/btw` `/goal` `/cd` 等 60+

**dsh cli**（全屏 alt screen）：`/exit` `/help` `/clear` `/session` `/model` `/permission`；操作键：`↑/↓` 跨会话历史、`Shift+Tab` 循环权限档、`Ctrl+O` 展开工具组、`Ctrl+C` 取消当前轮

---

**一句话**：**地基先打（`/` 菜单 + `@` 补全 + `CliServices` 扩容），楼层跟上（`/model` `/mcp` `/skills` `/diff`），跨楼后处理（`/fork` `/rewind`）；`/plan` 删除、`/init` 改走 `RuleStore`、渲染范式不动、心流元素全收。**

# CLI Agent 方案：从「内核能跑」到「终端好用」

> 版本：v1.0（**调研与诊断版；决策已全部定稿于 [`CLI方案-v2.md`](./CLI方案-v2.md) v2.1**）　适用范围：`web/app/agent-cli`（CLI 通道）
> 目标：把 CLI 从「复用内核的占位外壳」做成与 Web 通道**能力对等、体验达标**的第二交互入口；不改内核语义，不新增业务逻辑，权限仍由执行臂硬边界裁决。
>
> 📌 **阅读顺序**：本版 §2（主流 CLI 调研）与 §3（现状诊断）为事实底稿，仍然有效；**§7 的 7 个待确认问题已在 v2 §0「决策台账」全部关闭**，请以 v2 为准。

---

## 0. 结论摘要

| # | 结论 | 证据 |
|---|------|------|
| 1 | **内核完好，通道几乎不可用**。CLI 实测能成功调用模型（`inputTokens=302 / outputTokens=208`）、客观验证通过，但终端只输出 INFO 日志，**用户看不到模型回答** | 实机运行，见 §3.2 |
| 2 | **根因是时序倒置**：`submit().block()` 先跑完，再 `subscribe()` 事件流。事件全部落在 `AgentEventPublisher` 的 pending 队列里被一次性排空 → **结构性失去实时性**，且大任务可能触发 2000 条上限丢事件 | `CliRunner.java:83,88` |
| 3 | **存在安全缺陷**：ASK 确认回传的是伪造操作 `{opType:WRITE, path:"", args:{}}`，与用户实际授权的那次操作不对应 —— 等于「按一次 y 放行任意后续写操作」 | `CliRunner.java:97` |
| 4 | **能力不对等**：`agent-cli` 的 pom **不含** `agent-skill` / `agent-mcp`（Web 有），故 CLI 下 Skill 与 MCP 均不可用，违反「通道一致」原则 | 依赖对比，见 §3.3 |
| 5 | **主流 CLI 的共性只有四条**：headless 模式、会话可恢复、权限可交互裁决、结构化输出。其余（插件市场、ACP 协议、检查点）是加分项而非入场券 | §2 横向调研 |

**建议**：不做「大改重写」，先做 **P0 六项**（时序、ASK 真实性、事件全覆盖、UTF-8、会话落盘、依赖补齐）—— 这六项全部在 `agent-cli` 内可完成，**无需改动内核**。

---

## 1. 调研方法与本报告的可靠性边界

**方法**：以各项目官方文档 / 官方仓库 / 官方 CLI `--help` 为主，辅以公开技术文章。不使用推测补全。

**边界**（务必先读）：

1. CLI 的 flag 与 slash 命令**随版本高频变动**，本报告描述的是调研时点的快照，实施前需以当时版本复核。
2. 「主流怎么做」只作为**设计参考**，不作为**需求依据**。本项目是本地优先、单用户、零托管，与云端 CLI（Codex Cloud、Gemini 免费额度）场景不同，凡涉云端/计费/多租户的能力一律不采纳。
3. 报告中标注为「未确证」的条目见附录 B，不得作为决策依据。

---

## 2. 主流 Agent CLI 横向调研

### 2.1 Claude Code（Anthropic，Node/TS，闭源）

**核心特征**：把「Agent 能力」做成 CLI 的一等公民，功能面最宽，是当前事实上的功能基线。

| 维度 | 内容 |
|------|------|
| 运行模式 | 交互式 REPL / `-p`（`--print`）headless / 一次性管道 |
| 关键参数 | `-c`（`--continue` 续最近会话）、`-r`（`--resume` 指定会话）、`--output-format text\|json\|stream-json`、`--permission-mode default\|acceptEdits\|plan\|auto\|dontAsk\|bypassPermissions`、`--add-dir`、`--mcp-config`、`--max-turns`、`--max-budget-usd` |
| 交互操作 | `Shift+Tab` 循环切换权限模式；`Esc` 中断；`Esc Esc` 回退（rewind）；`Ctrl+R` 展开 |
| 输入前缀 | `/` slash 命令（约 40 个）、`!` 直执行 shell、`@` 文件引用 |
| 上下文文件 | `CLAUDE.md` 四级：managed（系统级）→ `~/.claude/CLAUDE.md` → `./CLAUDE.md` → `./CLAUDE.local.md`；支持 `@import`（最大深度 4）；建议 < 200 行，上限 4 MiB |
| 配置分层 | 5 层 settings，list 型字段做合并而非覆盖 |
| 权限规则 | `Bash(npm run *)` / `Read(./src/**)` 语法，判定顺序恒为 deny → ask → allow |
| 扩展 | ~36 个 hook 事件（退出码 2 = 阻断）、subagents（`.claude/agents/*.md`）、MCP（local/project/user 三种作用域）、plugins + marketplace、output styles |
| 自检 | `/doctor`、`/context`、`/usage`、statusLine |

**对本项目的启示**：`CLAUDE.md` 四级 + `@import` 与本项目 `LUCKY.md` 两级（框架根 + 工作空间）思路同源，**我们已经有关键的那一层**，缺的是「项目本地覆盖」与「分节规则启用开关」的 CLI 可视化管理。

> ⚠️ 安全提示：该 CLI 提供 `--dangerously-skip-permissions` 与 settings 层的权限绕过配置。本项目 D2 已定「权限由执行臂硬边界强制，LLM 软约束仅作体验引导」，**此类逃逸开关一律不实现**。

### 2.2 Codex CLI（OpenAI，Rust，Apache-2.0）

**核心特征**：把「沙箱」和「审批」做成两个**正交**维度，安全模型最清晰。

| 维度 | 内容 |
|------|------|
| 子命令 | `codex`、`exec`(=`e`)、`resume`、`fork`、`review`、`apply`、`login`、`mcp`、`mcp-server`、`sandbox`(=`debug`)、`execpolicy`、`features`、`plugin`、`doctor`、`archive`、`completion`、`app-server`、`update` |
| headless | `codex exec --json` / `-o <file>` / `--output-schema <schema>` / `--ephemeral`（不落盘会话） |
| 安全模型 | `sandbox_mode`（`read-only` / `workspace-write` / `danger-full-access`）× `approval_policy`（`untrusted` / `on-request` / `never`），**两轴独立组合** |
| 沙箱实现 | macOS Seatbelt；Linux bubblewrap + seccomp（Landlock 为 legacy）；Windows 原生 / WSL2 |
| 策略引擎 | `execpolicy` 用 **Starlark** 写命令白/黑名单规则 |
| 配置 | `~/.codex/config.toml`（TOML）+ 项目 `.codex/config.toml`（仅信任仓库生效）+ `-c key=value` 内联覆盖；profiles 机制 |
| 上下文文件 | `AGENTS.md` 链：全局 → 仓库根 → 各级目录（每级取一个），上限 32 KiB（`project_doc_max_bytes`），`project_doc_fallback_filenames` 可兼容读取 `CLAUDE.md` |
| 会话 | `~/.codex/sessions/*.jsonl` + `history.jsonl` + `state.db`，支持 resume / fork |
| 网络 | `network_proxy` 域名级 allow/deny |

**对本项目的启示**：**「沙箱模式 × 审批策略」两轴正交**是本报告认为最值得借鉴的一条。本项目现只有「权限级别」一轴（只读/修改/全部），审批是 ASK 单点。可考虑在 CLI 侧显式呈现两轴，但**执行臂契约不改**。
另：`--output-schema` 强制结构化输出，对脚本化集成价值高，列为 P2。

### 2.3 Gemini CLI（Google，Node ≥ 20，Apache-2.0）

**核心特征**：**检查点（Checkpointing）** 与 **自定义 slash 命令**做得最完整；退出码有正式约定。

| 维度 | 内容 |
|------|------|
| 子命令 | `gemini`、`mcp`、`extensions`、`skills`、`hooks migrate` |
| 关键参数 | `-p` + `--output-format text\|json\|stream-json`、`--approval-mode default\|auto_edit\|yolo\|plan`（**单轴**）、`--policy` / `--admin-policy`（Policy Engine） |
| **退出码契约** | `0` 成功、`1` 通用错误、`42` 输入/参数错误、`53` 超时或用户中止（**已文档化**） |
| 沙箱 | macOS Seatbelt（6 档 profile）/ Docker-Podman / gVisor / LXC-LXD / Windows 原生；支持运行时 **Sandbox Expansion**（临时提权再收回） |
| 检查点 | 写工具执行前创建 shadow git repo；`/restore [id]` 回滚**文件 + 对话**；`/rewind` 回退对话 |
| 自定义命令 | `~/.gemini/commands/*.toml`，支持 `{{args}}`、`!{shell}`、`@{file}`；子目录形成命名空间（`git/commit.toml` → `/git:commit`） |
| 扩展 | `gemini-extension.json` 打包 commands/hooks/skills/agents/MCP/themes，GitHub 直分发无审核 |
| 其他 | `context.fileName` 可指向 `AGENTS.md`；`~/.gemini/keybindings.json` 自定义按键；支持 ACP + A2A 协议；免费额度 60 req/min、1000 req/day |

**对本项目的启示**：
- **检查点 = shadow git + 文件&对话双回滚** —— 本项目已有 `rollbackDir` 与 `rollbackToNode()`，CLI 可直接暴露为 `/undo`，属**低成本高价值**（P2）。
- **`commands/*.toml` 自定义命令**：本项目可直接复用 `agent-skill` 的 Markdown + frontmatter 加载器，不必新造格式（P2）。
- **退出码正式文档化**：我们当前完全没有约定，属 P1 必修项。

### 2.4 DeepSeek Harness（DSH，官方，Node/TS，MIT）

**核心特征**：**「Agent = Model + Harness」** 的显式宣言 —— 只做 harness（外壳/运行时），模型由用户自带。这与本项目定位高度同构。

| 维度 | 内容 |
|------|------|
| 仓库/包 | `github.com/deepseek-ai/deepseek-harness`，npm `@deepseek-ai/dsh` |
| 内核 | **Cordis 插件内核**，「一切皆插件」 |
| 模式 | Standard / Code / Minimal / Creator 四档 |
| 主入口 | `npx @deepseek-ai/dsh web` → **本机 Web UI（127.0.0.1:3080）**，而非终端优先 |
| 配置 | `~/.dsh` |
| 会话 | **append-only 事件日志**驱动 resume / fork / search / replay |
| 阶段 | `developer preview` v0.1 |

**对本项目的启示**：
1. **「Web 优先、CLI 后续」的战略选择被官方背书** —— DSH 把 Web UI 作为主入口，与本项目 D11 一致，方向没错。
2. **append-only 事件日志**是会话恢复的理想形态。本项目 `SessionRepository` 已是 JSONL append-only（`JsonlUtil.append`），**结构上已经具备** resume/fork/search 的基础，缺的只是 CLI 侧的暴露与索引。
3. DSH 的模式切换（Standard/Code/Minimal/Creator）与本项目 PLAN/ACT/ASK 三模式是**不同切面**：DSH 切「工具集与提示词档位」，我们切「循环步数与能力上限」。可借鉴其「模式决定可用工具集」的思路，列为 P3。
4. 注意：v0.1 developer preview，接口不稳定，**只作参考不作依赖**。

### 2.5 Aider（Python，Apache-2.0）

**核心特征**：**git-native + repo map**，是「代码库上下文压缩」做得最扎实的 CLI。

| 维度 | 内容 |
|------|------|
| 交互 | prompt-toolkit 行编辑 REPL；`-m/--message`、`-f/--message-file` 一次性模式 |
| Slash 命令 | 30+：`/add /drop /diff /undo /run /test /lint /commit /architect /ask /code /web /read-only /tokens /think-tokens /reasoning-effort /settings /load /save` |
| 模式 | `code` / `ask` / `architect`（双模型：强模型规划 + 弱模型落地）/ `help` |
| **Repo Map** | tree-sitter 解析 + 类 PageRank 排序，按 token 预算选关键符号；`--map-tokens 1024` 控制配额 |
| 自动化开关 | `--yes-always`（全自动确认）、`--dry-run`、`--no-auto-commits`、`--read` |
| 配置 | `.aider.conf.yml` 三级（home → repo root → cwd），`AIDER_*` 环境变量镜像 |
| 模型 | LiteLLM 100+ 供应商 + Ollama 本地 |
| 显著缺失 | **无 MCP** |

**对本项目的启示**：
- **`architect` 双模型模式**（强模型规划 / 弱模型执行）对本项目有直接价值：内核已有模型角色概念（`settings.json` 里 `role: main|memory`），CLI 可暴露 `--architect-model`。列为 P3 观察。
- **配置三级（home → repo → cwd）+ `LUCKY_*` 环境变量镜像**：比 Claude 的 5 层更贴合本地单用户，**建议直接照此设计**（见 §4.9）。
- Repo map 与本项目无关（我们有语义检索 + 工作空间边界），不采纳。

### 2.6 Crush（Charm，Go，FSL-1.1-MIT）

**核心特征**：TUI 美学标杆（Bubble Tea + Lip Gloss），且**权限与 hooks 兼容 Claude Code**。

| 维度 | 内容 |
|------|------|
| 交互 | Bubble Tea v2 TUI；Cobra 命令解析 |
| headless | `crush run`（非交互） |
| 配置 | `crush.json` + **`crushrc`（内嵌 Bash 解释器的脚本化配置）** |
| 安全 | 权限模型 + **hooks 与 Claude Code 兼容** |
| 扩展 | 官方 MCP Go SDK + Agent Skills + **内置 LSP** |
| 会话 | SQLite（`sqc`）持久化 + **VCR 录制回放** |

**对本项目的启示**：
- **TUI 观感是 CLI 的护城河**。本项目终端渲染目前是「emoji + println」，若要做到「好用」，JLine + ANSI 彩色/状态栏/Panel 是必要投入（§4.5）。
- **LSP 集成作为工具**——本项目工具链里没有 LSP，是能力缺口，但与 CLI 方案无直接关系，单列待办。
- **无需内嵌脚本解释器**（`crushrc`），本机已有 shell 直执行（`!` 前缀）足够。

### 2.7 Goose（Block → Linux Foundation AAIF，Rust + Electron）

**核心特征**：**Recipe（YAML 声明式工作流）** 是其旗舰能力；子代理支持并行与文件范围隔离。

| 维度 | 内容 |
|------|------|
| 命令 | `goose session`（交互）、`goose run -t/-i/--recipe/--no-session/--output-format text\|json\|stream-json` |
| Slash 命令 | `/plan /endplan /compact /mode /extension /skills /recipe /model` |
| 扩展 | MCP 原生（rmcp 库，70+ 扩展） |
| 权限 | Smart Approve + `--container` |
| 会话 | `sessions.db` + 按会话导出 JSON |
| 上下文 | **`.goosehints`** |
| 多 Agent | 子代理**并行**执行，各自**隔离文件范围** |

**对本项目的启示**：
- `/plan` `/endplan`（显式进入/退出规划态）与本项目 PLAN 模式对应，**CLI 应提供同等显式切换**（比 `Shift+Tab` 循环更可发现）。
- `/compact` 手动触发压缩：本项目已有五级压缩管线（D16），**但 Web 侧只能自动触发**，CLI 可先做手动暴露（P2）。
- Recipe 与本项目 **Workflow 引擎**功能重叠（P0/P1 已落地）。**注意**：当前 `agent-cli` 不依赖 `agent-workflow`，CLI 无法跑工作流（见 §7 待确认 1）。

### 2.8 opencode（sst → anomalyco，TS/Bun + Go TUI）

**核心特征**：**client/server 分离** —— TUI 只是众多客户端之一，走 SSE `/event`。

| 维度 | 内容 |
|------|------|
| 架构 | TS/Bun 服务端 + Go Bubble Tea TUI（TUI 正在迁 TS），通过网络协议解耦 |
| headless | `opencode run` |
| 自定义命令 | `.opencode/command/*.md`，支持 `$ARGUMENTS`、`@file`、`@agent` |
| 工具 | MCP + 本地工具 `.opencode/tool/` + **内置 LSP** |
| 检查点 | **每步执行前做 git-tree 快照** |
| 会话 | SQLite |
| 上下文 | `AGENTS.md`；上下文达 95% 自动压缩 |

**对本项目的启示**：client/server 分离的价值在于「一个内核，多前端」。本项目 `AgentChannel` 契约（`subscribe`/`submit`/`cancel`/`snapshot`）已是同一思路，**但 CLI 尚未实现该接口**（见 §3.4）——优先补齐契约实现，而非重造协议层。
**不采纳**：把 CLI 拆成独立常驻服务端。本地单用户，多一个进程只增加运维成本。

### 2.9 OpenHands CLI（Python，MIT）—— 简述

Docker/进程/远程三种沙箱、事件流（Action/Observation 经中央 Hub）、CodeAct 1.0 动作空间、`.agents/skills/` 目录。

**启示**：**Action/Observation 事件流**与本项目 `action` / `tool_result` 事件同构；**沙箱三档（Docker / 进程 / 远程）** 值得在文档里预留演进位置，但本项目 D21 已定「Phase 1 进程内/IPC」，暂不引入容器。

### 2.10 能力对比矩阵

图例：`✅ 完整` / `◐ 部分` / `✗ 无` / `— 不适用`

| 维度 | Claude Code | Codex | Gemini CLI | DSH | Aider | Crush | Goose | opencode |
|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| 交互式 REPL | ✅ | ✅ | ✅ | ◐(Web 优先) | ✅ | ✅ | ✅ | ✅ |
| headless/非交互 | ✅ | ✅ | ✅ | ◐ | ✅ | ✅ | ✅ | ✅ |
| 结构化输出 | ✅ | ✅ | ✅ | ◐ | ✗ | ◐ | ✅ | ◐ |
| 会话恢复 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 会话分叉 fork | ◐ | ✅ | ◐ | ✅ | ✗ | ✗ | ✗ | ✗ |
| 权限交互裁决 | ✅ 多维 | ✅ 双轴 | ✅ 单轴 | ◐ | ◐ | ✅ | ✅ | ✅ |
| OS 级沙箱 | ◐ | ✅ | ✅ | ✗ | ✗ | ✗ | ◐ | ✗ |
| 配置多层 | ✅ 5 层 | ✅ | ✅ | ◐ | ✅ 3 层 | ◐ | ◐ | ◐ |
| 上下文文件 | ✅ 4 级 | ✅ AGENTS.md 链 | ✅ | ✗ | ✗ | ◐ | ◐ | ✅ |
| 自定义命令 | ✅ md | ✗ | ✅ toml | ◐ | ✅ slash | ◐ | ✅ recipe | ✅ md |
| 检查点/回滚 | ✅ rewind | ✗ | ✅ 双回滚 | ✗ | ◐ `/undo` | ◐ VCR | ✗ | ✅ git 快照 |
| MCP | ✅ | ✅ | ✅ | ◐ 插件 | ✗ | ✅ | ✅ 70+ | ✅ |
| 多 Agent | ✅ subagents | ✗ | ◐ | ◐ | ◐ architect | ✗ | ✅ 并行 | ✅ |
| 内置 LSP | ✗ | ✗ | ✗ | ✗ | ✗ | ✅ | ✗ | ✅ |
| 退出码契约 | ◐ | ◐ | ✅ | ✗ | ✗ | ✗ | ◐ | ✗ |
| 开源许可 | 闭源 | Apache-2.0 | Apache-2.0 | MIT | Apache-2.0 | FSL-1.1-MIT | 开源 | 开源 |

### 2.11 「值得抄的 10 条」与「明确不抄的 5 条」

**值得抄（按性价比排序）**

| # | 借鉴点 | 来源 | 成本 | 分期 |
|---|--------|------|------|------|
| 1 | 退出码正式契约（0/1/参数/权限/预算） | Gemini | 极低 | P1 |
| 2 | `--output-format text\|json\|stream-json` | Claude/Codex/Gemini/Goose | 低 | P1 |
| 3 | 会话 `--continue` / `--resume` | 全部 | 低（复用 `SessionRepository`） | P0 |
| 4 | 配置三级（home → repo → cwd）+ `LUCKY_*` 环境变量镜像 | Aider | 低 | P1 |
| 5 | 常驻状态栏（模型 / 权限档 / 会话 ID / token） | Claude statusLine | 中 | P1 |
| 6 | 显式 `/plan` `/endplan` 模式切换（优于循环切换） | Goose | 低 | P1 |
| 7 | `/undo` 文件 + 对话双回滚 | Gemini | 中（已有 `rollbackToNode`） | P2 |
| 8 | 自定义命令目录（`commands/*.md`） | Gemini/opencode | 中 | P2 |
| 9 | 沙箱模式 × 审批策略**两轴正交**呈现 | Codex | 中高（仅通道侧展示） | P2 |
| 10 | 手动 `/compact` 触发压缩管线 | Goose | 中（需内核暴露入口） | P2 |

**明确不抄**

| # | 不抄项 | 理由 |
|---|--------|------|
| 1 | `--dangerously-skip-permissions` 类逃逸开关 | 违反 D2「权限由执行臂硬边界强制」 |
| 2 | 云端任务托管（Codex Cloud / Cloud Tasks） | 违反 D1/D3「零托管、数据不出本机」 |
| 3 | 计费与额度体系（免费额度、`--max-budget-usd` 计费语义） | 违反 AGENTS §二-4「不涉及计费」 |
| 4 | 插件市场 + 无审核的社区扩展分发 | 本地执行权限过重，供应链风险不可接受 |
| 5 | client/server 分离与常驻守护进程 | 本地单用户，多进程只增运维成本（D21 已定进程内） |

---

## 3. 本项目 CLI 现状诊断

### 3.1 代码盘点

`agent-cli` 当前**只有 3 个类、共 243 行**：

| 文件 | 行数 | 职责 | 问题密度 |
|------|-----:|------|----------|
| `CliApplication.java` | 22 | Spring Boot 入口，`web-application-type=none` | 无 |
| `CliRunner.java` | 118 | REPL 主循环 + 工作空间解析 | **高** |
| `CliChannel.java` | 103 | 事件渲染 + ASK 交互 | **高** |
| `target/agent-cli-1.0.0.jar` | — | 已 repackage，可执行 | — |

**构建产物已就绪**：`spring-boot-maven-plugin` 的 `repackage` 已配置，`mainClass=com.lucky.agent.cli.CliApplication`，产出可直接 `java -jar` 的 fat jar。

### 3.2 实机验证证据

**实验**：

```
printf "你好，请只回复四个字：模型已接通\nexit\n" \
  | D:/jdk21/bin/java.exe -jar agent-cli/target/agent-cli-1.0.0.jar
```

**结果**：

```
…Started CliApplication in 3.11 seconds…
════════ Lucky Agent CLI —— 复用同一内核/记忆/执行臂 / 工作空间: 3d02cc29-… ════════
> [deepseek-v4-flash 请求成功 cost=1696ms inputTokens=302 outputTokens=208 thinkingLength=652]
{"done": true, "missing": [], "summary": "已按要求仅回复四个字\"模型已接通\"…"}
```

**判读**（这是本方案最重要的实验）：

| 观察 | 含义 |
|------|------|
| Spring 上下文 3.11s 启动成功 | 装配无缺 Bean、无循环依赖，`scanBasePackages` 下的精简 classpath 可用 |
| 工作空间解析成功 | `resolveWorkspaceId()` 正常 |
| 模型调用成功（302/208 token，1.7s） | 模型网关、密钥解密、网络链路全部正常 |
| 客观验证返回 `{"done": true}` | 验证链正常 |
| **终端没有出现模型回答** | **渲染层未生效** —— 内核产出的事件没有被用户看到 |
| 中文日志行 GBK 乱码 | Windows 控制台代码页（936）与 UTF-8 输出不匹配 |

**结论**：CLI 的**内核侧 100% 可用**，问题**全部集中在通道层**（118 + 103 行）。这是个好消息 —— 修复面很小。

### 3.3 缺陷清单（按风险从高到低）

#### P0（阻断，必须修）

| # | 缺陷 | 代码证据 | 后果 |
|---|------|----------|------|
| **P0-1** | **时序倒置：先跑完再订阅** | `CliRunner.java:83` `submit(...).block()` 在前，`:88` `publisher.stream(...)` 订阅在后 | 事件全部进 `AgentEventPublisher` pending 队列被一次性排空 → **无任何实时性**；且队列上限 2000，超限丢最旧事件 |
| **P0-2** | **ASK 确认回传伪造操作** | `CliRunner.java:97` `Map.of("opType","WRITE","path","","args",Map.of())` | 与用户实际授权的那次操作**不对应**。用户点一次 `y` 相当于放行后续任意写操作 —— 安全缺陷 |
| **P0-3** | **事件类型漏 4 类且 default 静默丢弃** | `CliChannel.java:37-75` switch 覆盖 10 种字符串字面量，缺 `progress` / `options` / `skill_invoke` / `mcp_invoke`，`default -> { }` | `OPTIONS` 事件（多选一决策）被静默吞掉 → 内核挂起等待选择而终端无提示，**会话直接卡死** |
| **P0-4** | **会话不落盘，无历史** | `CliRunner.java:54` 每次启动 `UUID.randomUUID()` | 无法 `--continue` / `--resume`；上下文跨进程完全丢失；`SessionRepository` 明明已具备能力而未被使用 |
| **P0-5** | **能力缺失：无 Skill / MCP** | `agent-cli/pom.xml` 依赖 8 个模块，**不含** `agent-skill` / `agent-mcp`（`agent-web` 两个都有） | CLI 下 Skill 加载与 MCP 工具**完全不可用**，违反 AGENTS §三「通道一致」原则 |
| **P0-6** | **Windows 中文乱码** | 实测日志乱码；JVM/控制台编码未对齐 | 中文提示词、文件路径、模型输出的可读性受损 |

#### P1（可用性）

| # | 缺陷 | 代码证据 | 后果 |
|---|------|----------|------|
| P1-1 | 裸 `Scanner`，无行编辑/历史/补全/多行 | `CliRunner.java:63`、`CliChannel.java:24` | 长提示词无法编辑；上箭头取不回历史；无 Tab 补全 |
| P1-2 | `CliChannel` 非 Spring Bean | `CliRunner.java:38` `new CliChannel()` | 无法注入依赖、无法单测、无生命周期管理 |
| P1-3 | 未实现 `AgentChannel` 契约 | `CliChannel` 无 `implements AgentChannel` | 与 `WebChannel` 语义漂移；契约声明「Web 与 CLI 复用」未落实 |
| P1-4 | 无 `-p` / `--output-format` headless | `CliApplication.java:20` 直接进 REPL，`args` 丢弃 | 无法脚本化、无法被上层编排调用 |
| P1-5 | 无 exit code 约定 | 全部 `System.out.println`，无 `System.exit` | 无法被 CI/脚本判定成败 |
| P1-6 | 无 slash 命令、无状态栏 | 无相关代码 | 模型/权限/会话状态不可见；无元操作入口 |
| P1-7 | `content_delta` 硬编码 payload key | `CliChannel.java:41` `p.get("delta")` | 未用 `AgentEvent.KEY_*` 常量，契约变动时静默失效 |
| P1-8 | 工作空间只取第一个，不可选 | `CliRunner.java:103-117` | 多工作空间场景无法指定；偏好 FULL/MODIFY 的启发式无提示 |

#### P2（体验）

| # | 缺陷 | 说明 |
|---|------|------|
| P2-1 | 无 token/成本聚合 | `token` 事件有渲染但无累计；`model-usage.json` 已存在未被 CLI 读取 |
| P2-2 | 无 `--max-turns` / `--max-budget` 覆盖 | `CoreProperties` 是全局单例，per-run 覆盖需内核配合（见 §7） |
| P2-3 | ASK 只有 y/N 二元 | 缺「本次会话内始终允许」「写入规则文件」三档记忆 |
| P2-4 | Ctrl+C 语义未接 | `ConversationStateManager.SessionState.requestCancel()` 已存在，CLI 未使用 |
| P2-5 | 无 `/undo` | `rollbackDir` + `rollbackToNode()` 已存在，CLI 未暴露 |

### 3.4 与 `AgentChannel` 契约的偏离

契约（`agent-common/.../contract/AgentChannel.java`）声明四个方法，要求「Web 与 CLI 复用」，并明确「通道只负责渲染和输入，不持有业务逻辑；所有状态查询走 `ConversationStateManager`，通道不得自行缓存会话」。

| 契约方法 | `WebChannel` | `CliChannel` | 偏离 |
|----------|:---:|:---:|------|
| `subscribe(SessionRef) → Flux<AgentEvent>` | ✅ | ✗ 未实现 | CLI 在 Runner 里直接取 `publisher.stream()`，绕开契约 |
| `submit(SessionRef, UserInput) → Mono<RunResult>` | ✅ | ✗ 未实现 | 同上 |
| `cancel(SessionRef) → Mono<Void>` | ✅ | ✗ 未实现 | CLI 无取消能力 |
| `snapshot(SessionRef) → Mono<SessionSnapshot>` | ✅ | ✗ 未实现 | CLI 无法查快照 |

**额外偏离**：`CliChannel.handleAsk()` 内部持有 `volatile boolean pendingConfirm` 状态 + `takePendingConfirm()` 双阶段读取 —— 这正是契约禁止的「通道自行缓存会话状态」。且该状态在 `CliRunner` 里被跨方法消费（`CliRunner.java:96`），形成隐式耦合。

---

## 4. 目标设计

### 4.1 设计原则

1. **只改通道，不改内核**。P0/P1 全部应在 `agent-cli` 内完成；任何需要动内核的项单列并显式标注。
2. **实现 `AgentChannel`，不另起一套**。CLI 与 Web 消费同一 `Flux<AgentEvent>`、同一 `ConversationStateManager`、同一 `SessionRepository`。
3. **事件流是「推进信号」，存储是「事实源」**。与 Web 侧已确立的原则一致：订阅后立即取一次快照，事件只用于增量刷新。**不得**假设事件流可靠重放。
4. **权限不设逃逸口**。审批只能「放行本次」「本会话内始终允许」「写入规则文件」三档，不能越过执行臂。
5. **命令行不新增业务逻辑**。每个 slash 命令必须映射到已存在的 Bean/方法；否则不开。

### 4.2 模块与包结构

```
com.lucky.agent.cli
├── CliApplication            入口：参数解析 → headless 或 REPL
├── CliOptions                参数模型（picocli @Command，替代裸 args）
├── channel/
│   ├── CliChannel            implements AgentChannel（契约对齐）
│   ├── EventRenderer         事件 → 终端输出（14 类全覆盖）
│   └── Theme                 ANSI 色板 + 无 TTY 降级
├── repl/
│   ├── ReplLoop              行读取 → 输入分发（命令/前缀/普通提示词）
│   ├── LineEditor            JLine 封装（历史/补全/多行/中断）
│   ├── StatusBar             footer 状态栏（模型/权限/会话/token）
│   └── SlashCommandRegistry  slash 命令注册与分发
├── command/                  各 slash 命令实现（薄壳，全部转发到 Bean）
│   ├── SessionsCommand  NewCommand  ResumeCommand  ClearCommand
│   ├── ModelCommand     ModeCommand PermCommand    WorkspaceCommand
│   ├── CompactCommand   UndoCommand TokensCommand
│   ├── HelpCommand      DoctorCommand  QuitCommand
│   └── shell/ShellPrefixCommand     `!` 前缀直执行
├── session/
│   └── CliSessionService     基于 SessionRepository：last/load/list/save/append
├── approval/
│   └── ApprovalHandler       ASK / OPTIONS 交互，回传**真实** op payload
└── headless/
    ├── PrintModeRunner       `-p` 单次执行
    └── OutputFormatter       text | json | stream-json
```

**职责边界**：`repl` 只做输入编排；`channel` 只做事件渲染；`command` 全部转发到内核 Bean；`session` 只做会话读写封装。**任何一处不得内联编排逻辑**。

### 4.3 启动参数面

```
用法：lucky-cli [选项] [提示词]
       lucky-cli -p "提示词" --output-format json
       echo "提示词" | lucky-cli -p -
```

| 参数 | 语义 | 分期 |
|------|------|------|
| `-p, --print` | headless 单次执行后退出；`-` 表示从 stdin 读取 | P1 |
| `-c, --continue` | 续最近一个会话 | P0 |
| `-r, --resume <id\|序号>` | 指定会话恢复 | P0 |
| `-w, --workspace <id\|路径>` | 指定工作空间（默认：唯一/FULL/MODIFY 优先，并打印所选） | P1 |
| `-m, --model <name>` | 覆盖本次运行的模型（按 `settings.json` 的 `name` 匹配） | P1 |
| `--mode plan\|act\|ask` | 覆盖运行模式 | P1 |
| `--output-format text\|json\|stream-json` | 仅对 `-p` 生效 | P1 |
| `--max-turns <n>` | 单次运行回合硬上限（映射内核 `runMaxTurns`） | P2 |
| `--max-budget <tokens>` | 单次运行 token 预算（映射 `runMaxBudget`） | P2 |
| `--plain` | 关闭 ANSI 与富渲染，纯文本输出 | P1 |
| `--show-thinking` | 回显推理链（默认不回显，见 §4.6） | P1 |
| `--no-color` | 同 `--plain` 的色彩部分 | P1 |
| `--version` / `-h, --help` | 版本与用法 | P1 |
| `--cwd <path>` | 指定项目本地配置查找起点 | P2 |

**明确不提供**：`--dangerously-*`、`--yolo`、`--skip-permissions`、任何绕过 `permission-rules.json` 的参数。

### 4.4 一轮对话的时序（P0-1 的修复）

这是本方案**唯一需要精确到订阅顺序**的地方，必须显式两步。

**错误顺序（现状）**

```
t0  submit().block()          ← 内核开始跑；事件无订阅者 → 全进 pending 队列
t1  （内核跑完，6~30s）        ← 用户在此期间看不到任何输出
t2  consume(stream).block()   ← 订阅触发 doOnSubscribe → 一次性排空队列 → 瞬间打印完
t3  结束
```

**正确顺序**

```
t0  publisher.reset(sessionId)          ← 清掉上一轮残留（关键：stream 是冷流 + pending 排空）
t1  consume(stream).subscribe()         ← 先注册订阅者 → 此后 publish 走实时分发路径
t2  conversationManager.submit(...)     ← 异步发起，不 block
t3  实时渲染（thought / action / tool_result / content_delta …）
t4  收到 STOP 事件 或 submit Mono 完成   ← 双信号，任一到达即结束
t5  dispose 订阅
```

**代码骨架**

```java
String sessionId = ref.sessionId();
stateManager.publisher().reset(sessionId);                 // t0

Sinks.Empty<Void> finished = Sinks.empty();
Disposable sub = stateManager.publisher()
        .stream(sessionId)
        .transform(events -> channel.consume(events, ref, finished::tryEmitEmpty))   // t1
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(null, err -> {
            renderer.renderFatal(err);
            finished.tryEmitEmpty();
        });

try {
    RunResult r = conversationManager.submit(ref, UserInput.of(content)).block(TURN_TIMEOUT);  // t2
    if (r != null && r.error() != null && !r.error().isBlank()) renderer.renderError(r.error());
    // t4：等 STOP；异常路径下最多再等 STOP_GRACE，避免永久挂起
    finished.asMono().block(Duration.ofSeconds(STOP_GRACE_SEC));
} finally {
    sub.dispose();                                          // t5
}
```

**为什么不用 `Mono.when(submit, consume)`**：`when` 是**并发订阅**，而 `submit` 内部是 `Mono.fromCallable(...).subscribeOn(boundedElastic)` —— 一旦被订阅就立刻开跑，与事件流订阅**赛跑**。若 submit 先拿到线程并抢先发布首条事件，那一刻订阅者尚未注册，事件仍会进 pending 队列（虽然随后被排空，但**首屏延迟不可控**）。这是真实竞态，**必须显式两步**，不能依赖「参数顺序 = 订阅顺序」的隐式行为。

**双信号的必要性**：`publisher.complete(sessionId)` 会终止流，此时 STOP 事件可能不到达；而异常路径下 submit 可能立刻失败。任一信号到达即结束，可避免「内核已结束、CLI 仍在等 STOP」的挂死。

### 4.5 事件渲染：14 类全覆盖

`CliChannel.render()` 必须覆盖 `AgentEventType` 全部 14 类。**注意**：`e.type()` 返回的是 `jsonValue()` 字符串，而 Java `switch` 的 case 标签必须是编译期常量，**不能写 `AgentEventType.CONTENT_DELTA.jsonValue()`**。

**推荐做法**：在 `AgentEventType` 增加静态映射方法（`agent-common`，纯新增、向后兼容）：

```java
private static final Map<String, AgentEventType> BY_JSON =
        Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(AgentEventType::jsonValue, e -> e));

public static AgentEventType fromJsonValue(String v) {
    return BY_JSON.get(v);   // 未知返回 null，由调用方兜底渲染
}
```

渲染映射表：

| 事件类型 | 终端呈现 | 流式 | 备注 |
|----------|----------|:---:|------|
| `thought` | `💭` 灰字（默认折叠为一行摘要，`--show-thinking` 全展） | ✗ | 与 Web 通道一致：推理产物默认不外显 |
| `progress` | `·` 暗灰一行（阶段切换/安全阀） | ✗ | **当前缺失** |
| `content_delta` | 直接 `print` + flush（打字机） | ✅ | 用 `KEY_DELTA`，不硬编码 `"delta"` |
| `action` | `🛠 调用 <tool>(<args 摘要>)`，风险标记着色 | ✗ | `risk` 字段决定颜色 |
| `tool_result` | `✅/❌ <summary>`；`data` 过长折叠显示前 N 行 + `…(+M 行)` | ✗ | `--plain` 时完整打印 |
| `skill_invoke` | `🧩 Skill: <skillId> (match=<match>)` | ✗ | **当前缺失**（且 CLI 无 skill 模块） |
| `mcp_invoke` | `🔌 MCP: <serverId>/<tool>` | ✗ | **当前缺失**（且 CLI 无 mcp 模块） |
| `task_plan` | 编号列表 + 总数 | ✗ | |
| `task_progress` | 原地刷新同一行 `[■■■□□] 3/5 <status>` | ✅ | 用 `\r` 覆盖，非 TTY 时降级为逐行 |
| `ask` | `⚠️ <question>` + 操作详情（opType/path/args）+ `(y/N/a=always)` | ✗ | 见 §4.8 |
| `options` | 编号选项 + `recommended` 标记 + 自定义输入提示 | ✗ | **当前缺失，且会静默卡死会话** |
| `error` | `❌ [<stage>] <msg>` + `回退=<fallback>` | ✗ | |
| `token` | 状态栏更新（非刷屏）；`/tokens` 查明细 | ✗ | 默认不逐条打印 |
| `stop` | `🏁 <reason>` + 本轮耗时/token 汇总 | ✗ | 触发 `finished` 信号 |

**`default` 分支不得静默**：未知类型统一渲染为 `ℹ️ [<type>] <payload 单行 JSON>`，避免「契约新增事件而 CLI 静默吞掉」再次发生。

### 4.6 会话与恢复

**存储零新增** —— `SessionRepository` 已提供全部所需能力（JSONL append-only）：

| 能力 | 已有方法 | CLI 用法 |
|------|----------|----------|
| 元数据落盘 | `upsertMeta(ref, title)` | 首条用户消息时写入标题（取前 N 字） |
| 消息追加 | `appendMessage(sessionId, role, content, ts, checkpointIds, thinking)` | 每轮结束追加 user/assistant |
| 回放 | `loadForReplay(sessionId) → List<ReplayMessage(role, content, thinking)>` | `--resume` 时重建上下文 |
| 列表 | `listByUser(userId)` | `/sessions` |
| 删除 | `delete(sessionId)` | `/rm` |
| 回滚 | `truncateAfter(sessionId, ts)` | 配合 `/undo` |

**`--continue` / `--resume` 语义**

```
-c, --continue   取 listByUser(当前用户) 中 lastModifiedAt 最新的一条 → 复用其 sessionId
-r, --resume <n>  n 为纯数字 → 取列表第 n 条；否则按 sessionId 前缀匹配
```

**回放展示契约（必须遵守）**：

> `SessionSnapshot.MessageRecord` **刻意不含 `thinking`** —— 推理产物不外泄，这是已锁定的通道隔离契约（由 `SessionRepositoryReplayTest` 守住）。

因此：

- `/sessions`、`--resume` 的**历史回显**走 `loadMessages()` / 快照，**不显示 thinking**；
- `loadForReplay()` 仅在**重建内核上下文**时使用（必须带 thinking，否则推理模型 + tools 场景会 HTTP 400）；
- CLI 若需展示推理链，只展示**本轮新产生**的 `thought` 事件，不回放历史 thinking。`--show-thinking` 仅影响本轮。

### 4.7 Slash 命令

**硬约束**：每个命令必须映射到已存在的 Bean / 方法；无对应能力的不开。

**CLI 本机元操作**（不触内核）

| 命令 | 语义 | 映射 | 分期 |
|------|------|------|------|
| `/help` | 命令列表 + 当前按键说明 | — | P1 |
| `/quit`、`/exit` | 退出（保留 `exit`/`quit` 兼容） | — | P1 |
| `/clear` | 清屏（不删会话） | — | P1 |
| `/plain` | 切换富渲染/纯文本 | — | P1 |
| `/sessions` | 列出会话（序号/标题/时间/消息数） | `listSessions(userId)` | P1 |
| `/new` | 开新会话（换 UUID） | — | P1 |
| `/resume <n\|id>` | 切会话并回放 | `loadMessages` | P1 |
| `/rm <n\|id>` | 删除会话 | `delete` | P2 |
| `/doctor` | 自检：模型连通/密钥解密/工作空间/权限规则/数据目录可写 | 组合调用 | P1 |

**内核语义操作**（转发）

| 命令 | 语义 | 映射 | 分期 |
|------|------|------|------|
| `/model [name]` | 查看/切换本次会话模型 | `ModelConfigStore` | P1 |
| `/mode plan\|act\|ask` | 切换运行模式 | 内核模式参数 | P1 |
| `/perm` | 查看当前权限档（只读/修改/全部）；**不可提升** | `WorkspaceConfig` + `permission-rules.json`（只读展示） | P1 |
| `/workspace [id]` | 查看/切换工作空间（会开新会话） | `WorkspaceConfig` | P1 |
| `/tokens` | 本轮/累计 token 与耗时 | `model-usage.json` | P2 |
| `/compact` | 手动触发上下文压缩 | 五级压缩管线（需内核暴露入口） | P2 |
| `/undo [n]` | 回滚文件 + 对话到检查点 | `rollbackToNode` / `rollbackDir` | P2 |
| `/skills` `/mcp` | 列出可用 Skill / MCP Server | `agent-skill` / `agent-mcp`（**需先补依赖**） | P2 |

**输入前缀**（Claude Code 式，语义直观且实现成本低）

| 前缀 | 语义 | 实现 |
|------|------|------|
| `/` | slash 命令 | `SlashCommandRegistry` |
| `!` | 直接执行 shell，结果作为普通输出（**不经 LLM**） | `ProcessBuilder`，**仍须过执行臂权限校验** |
| `@` | 文件引用，把文件内容注入本轮上下文 | JLine 补全 + 路径 realpath 校验 |

### 4.8 权限与审批

**修复 P0-2（当前最严重）**

现状回传：`{opType:"WRITE", path:"", args:{}}` —— 与用户确认的操作无关。**必须改为回传 `ask` 事件 payload 中**完整的 `op` 对象**（`opType` / `path` / `args`）。

**目标交互（三档裁决）**

```
⚠️ 需要授权：写入文件
   操作：WRITE  路径：/home/u/ws/src/main/App.java
   参数：{ "mode": "overwrite", "bytes": 2048 }
   授权范围？
     [y] 仅本次
     [a] 本会话内对该路径始终允许
     [r] 写入规则文件（持久，需二次确认路径）
     [N] 拒绝（默认）
```

- `y` → 回传 `{confirm: <真实 op>}`
- `a` → 加入**会话内存**允许集，后续同路径不再询问（**仅内存，进程退出即失效**）
- `r` → 写入 `~/.lucky_agent/config/permission-rules.json`（该文件已存在），要求显式二次确认 + 打印将被写入的规则
- `N` → 回传拒绝

**OPTIONS 事件**（多选一）

```
❓ <question>
   1) <label>            ← [推荐] 时高亮
   2) <label>
   3) 自定义输入…         ← allowCustom=true 时出现
选择 [1-3]：
```

回传走 `ConversationManager.resolveChoice(sessionId)`（已存在）。

**状态栏常驻显示**：`model=deepseek-v4-flash │ mode=ACT │ perm=MODIFY │ session=3d02cc29 │ tokens=302/208`。**权限档只读展示，CLI 无提升入口**。

### 4.9 配置分层

**采用 Aider 式三级 + `LUCKY_*` 环境变量镜像**（不用 Claude 的 5 层 —— 本地单用户无托管层、无企业策略层，多出的层只会增加「为什么这个值没生效」的排查成本）。

**优先级（高 → 低）**

```
1. 命令行参数
2. 环境变量            LUCKY_MODEL / LUCKY_WORKSPACE / LUCKY_MODE / LUCKY_OUTPUT_FORMAT …
3. 项目本地配置        <cwd>/.lucky/settings.json          ← 新增（对应 .claude/settings.local.json）
4. 用户全局配置        ~/.lucky_agent/settings.json + ~/.lucky_agent/config/*.json   ← 已有
5. 内置默认值          CoreProperties 的紧凑构造器兜底
```

**现有资产映射表**（不新造文件）

| 主流 CLI 对应物 | 本项目既有物 | 状态 |
|-----------------|--------------|:---:|
| `CLAUDE.md` 全局 / `AGENTS.md` | `<frameworkRoot>/LUCKY.md`（兼 system prompt 基座，`##` 分节规则） | ✅ 已有 |
| 项目 `CLAUDE.md` / `AGENTS.md` | `<workspacePath>/LUCKY.md`（优先级高于全局） | ✅ 已有 |
| `~/.claude/settings.json` | `~/.lucky_agent/settings.json`（模型列表，API Key AES-GCM 加密） | ✅ 已有 |
| `.claude/settings.local.json` | `<cwd>/.lucky/settings.json` | ❌ 需新增 |
| 权限规则文件 | `~/.lucky_agent/config/permission-rules.json` | ✅ 已有 |
| MCP 配置 / 授权 | `~/.lucky_agent/mcp/` + `config/mcp-auth.json` | ✅ 已有 |
| hooks | `AgentLifecycleEvent` + 有序监听器链（D13） | ✅ 已有（内核侧） |
| `~/.claude/commands/*.md` | 建议 `<frameworkRoot>/commands/*.md` | ❌ 需新增（P2） |
| `.claude/agents/*.md` subagents | 无对应（D10 子代理由内核按需创建，非用户声明） | 语义不同，不引入 |
| `.goosehints` | 由 `LUCKY.md` 项目层承担 | 不引入 |

**关键约束**：`LUCKY.md` 已在文件头声明「用户改完保存即生效，无需重启」。CLI 必须**继承**该语义 —— 不得在启动时缓存提示词快照。

### 4.10 Headless / 管道模式

```bash
# 文本模式（默认）
lucky-cli -p "解释这个报错"
echo "解释这个报错" | lucky-cli -p -

# JSON：单条结果对象（对齐 RunResult）
lucky-cli -p "…" --output-format json | jq -r .output

# stream-json：NDJSON，一行一个 AgentEvent（便于 jq 流式消费）
lucky-cli -p "…" --output-format stream-json | jq -c 'select(.type=="action")'
```

**输出契约**

| 格式 | 结构 |
|------|------|
| `text` | 仅最终回答正文（**不含** thought / 进度 / 工具噪声），末尾单个换行 |
| `json` | `{"sessionId","output","error","status","turns","tokens":{"input","output"},"durationMs"}` |
| `stream-json` | 每行一个 `AgentEvent` 的 JSON 序列化（**不包 SSE 的 `event:/data:` 壳**，便于 `jq`/`grep` 直接消费） |

**headless 模式额外约定**

- 默认 `--no-session` 语义？ → **建议默认仍落盘**（本地优先，便于事后 `-r` 排查），提供 `--ephemeral` 关闭（对齐 Codex）。
- 非 TTY 时自动启用 `--plain`（无 ANSI）。
- ASK 出现在 headless 时：默认**拒绝并返回退出码 3**，避免脚本静默挂起；提供 `--auto-approve-scope none|session` 显式选择（**仍受执行臂权限级别硬约束**）。

### 4.11 退出码契约

当前完全缺失。**建议采用 6 个码，不做冗余细分**：

| 码 | 含义 | 触发条件 |
|:--:|------|----------|
| `0` | 成功 | 本轮正常结束且无未决授权 |
| `1` | 通用失败 | 内核报错、模型调用失败、未捕获异常 |
| `2` | 用法错误 | 参数非法、工作空间不存在、无法解析的选项 |
| `3` | 权限拒绝 | ASK 被拒（交互或 headless 默认拒绝） |
| `4` | 预算耗尽 | 回合/token 预算触顶（`BUDGET_EXHAUSTED`） |
| `5` | 未决挂起 | 会话结束时仍存在未决 ASK/OPTIONS（异常路径） |

**不采纳** Gemini 的 `42`/`53` —— 「输入错误」与「参数错误」在本项目语义重合，用 `2` 一个码即可；超时归入 `1` 或 `5`（由是否可恢复决定）。

### 4.12 依赖与打包

**新增依赖（需老大确认）**

| 依赖 | 用途 | 必要性 |
|------|------|--------|
| `org.jline:jline` | 行编辑、历史、Tab 补全、多行输入、ANSI、键盘绑定 | **必需**（P1 的核心体验来源） |
| `info.picocli:picocli` | 参数解析（`@Command`/`@Option`），替代手写 `args` | 建议 |
| `com.fasterxml.jackson.core:jackson-databind` | `--output-format json/stream-json` | 已在（经 agent-common 传递） |

**必须补齐的模块依赖（P0-5）**

```xml
<dependency><groupId>com.lucky.agent</groupId><artifactId>agent-skill</artifactId></dependency>
<dependency><groupId>com.lucky.agent</groupId><artifactId>agent-mcp</artifactId></dependency>
```

**硬约束（项目规则）**：

> 能力模块**绝不引** `spring-boot-starter-web` / `-tomcat`。
> `agent-cli` 保持 `spring-boot-starter` 即可，**不得**依赖 `agent-web`。

若 `agent-workflow` 也要进 CLI，需先确认（见 §7）。

**打包与分发**

1. `agent-cli/target/agent-cli-1.0.0.jar` 已是可执行 fat jar，`java -jar` 即可。
2. `lucky.bat` / `lucky.sh` 增加分流：
   ```bat
   rem lucky.bat
   if /i "%1"=="cli" (
     shift
     java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar "%APP%\agent-cli\target\agent-cli-1.0.0.jar" %*
     exit /b %ERRORLEVEL%
   )
   ```
3. **UTF-8 修复（P0-6）**三重保障：
   - JVM 参数：`-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`（JDK 19+ 支持 stdout/stderr 独立编码属性）
   - Windows 控制台：启动脚本内 `chcp 65001 >nul`
   - 代码侧：`System.out` 包一层 `PrintStream(new FileOutputStream(FileDescriptor.out), true, UTF_8)`
4. **启动耗时**：实测 3.11s（Spring 上下文）。P1 可用 `spring.main.lazy-initialization=true` 或 CLI 专用精简装配降低，**但需实测验证不破坏 Bean 初始化顺序**（有 `@PostConstruct` 依赖链时 lazy 会改变时序）。

---

## 5. 分期路线图

### P0 —— 让 CLI「能用」（全部在 `agent-cli` 内，不动内核）

| # | 任务 | 验收标准（可自动验证） |
|---|------|------------------------|
| 1 | 时序修复：先订阅后提交（§4.4） | 提交一个 6s 生成任务，**首条事件到达时间 < 300ms**（当前为「跑完后一次性打印」） |
| 2 | ASK 回传真实 `op`（§4.8） | 单测：构造 `ask` payload → 断言回传对象逐字段等于事件中的 `op`；**断言不含硬编码 `path:""`** |
| 3 | 事件 14 类全覆盖 + `fromJsonValue`（§4.5） | 单测：对 14 类各造一条事件 → 断言均有非空输出；未知类型走 `default` 且**不静默** |
| 4 | 会话落盘 + `-c` / `-r`（§4.6） | 端到端：跑一轮 → 进程退出 → `-c` 续聊 → 断言上下文含上轮内容 |
| 5 | 补 `agent-skill` / `agent-mcp` 依赖 | `mvn -pl agent-cli dependency:tree` 含两模块；启动无缺 Bean；`/skills` 能列出条目 |
| 6 | UTF-8（§4.12-3） | Windows 下输出中文路径与中文回答无乱码 |

**P0 完成定义**：终端能实时看到模型输出，且能用 `-c` 继续上一轮对话。

### P1 —— 让 CLI「好用」

| # | 任务 | 验收标准 |
|---|------|----------|
| 1 | `CliChannel implements AgentChannel`（四方法全实现） | 契约测试：同一会话经 Web/CLI 两通道提交，断言事件序列等价 |
| 2 | JLine REPL：历史 / 补全（`/` 命令、`@` 路径）/ 多行 / Ctrl+C 取消 | 手工验收清单 + `/help` 打印按键说明 |
| 3 | Slash 命令（本机元操作 + 内核转发，§4.7 P1 部分） | 每个命令有单测；`/doctor` 输出 5 项检查结果 |
| 4 | 状态栏（模型/模式/权限/会话/token） | 截图验收；`--plain` 时自动隐藏 |
| 5 | headless `-p` + `--output-format`（§4.10） | `stream-json` 输出可被 `jq -c` 逐行解析；`json` 符合声明 schema |
| 6 | 退出码契约（§4.11） | 6 个码各有对应单测 |
| 7 | 配置三级 + `LUCKY_*`（§4.9） | 单测：三级同名键 → 断言高优先级胜出 |
| 8 | `--workspace` / `--model` / `--mode` 覆盖 | 单测 + 手工 |
| 9 | `!` shell 前缀、`@` 文件引用（均过执行臂校验） | 单测：越界路径被拒 |

### P2 —— 让 CLI「顺手」

手动 `/compact`、`/undo` 双回滚、`/tokens` 成本聚合、自定义命令目录 `commands/*.md`、per-run 预算覆盖（**需内核支持**）、`--output-schema`、两轴安全模型展示（sandbox × approval）、检查点自动创建。

### P3 —— 进阶（本方案不承诺）

ACP / A2A 协议接入、TUI 多 Agent 并行面板、repo map、`architect` 双模型模式、插件/扩展机制。

---

## 6. 风险与取舍

| # | 风险 / 取舍 | 说明 | 缓解 |
|---|-------------|------|------|
| 1 | **Spring 上下文 3.1s 启动** ≠ 原生 CLI 的「瞬时感」 | 原生 CLI（Rust/Go）启动 < 100ms。我们为「复用同一内核」付了这个代价 | 接受。P2 再评估 lazy-init；**不为启动速度重写内核** |
| 2 | **终端能力碎片化** | Windows conpty + 代码页 936、SSH 无 ANSI、重定向无 TTY —— 富渲染处处可能崩 | JLine 统一抽象 + 强制 UTF-8 + 保留 `--plain` 完整降级路径；**富渲染任何一处失败都必须能降级而非崩溃** |
| 3 | **事件流非可靠通道** | `AgentEventPublisher` pending 队列上限 2000，超限丢最旧；`multicast()` 不补发历史 | 已在 Web 侧确立「快照是事实源，事件只是推进信号」；CLI 沿用同一原则，`--resume` 一律走 `SessionRepository` 而非重放事件 |
| 4 | **不提供权限逃逸开关会「不够爽」** | 主流 CLI 都有 `--dangerously-*` | 取舍明确：D2 定了权限由执行臂硬边界强制。用「三档授权记忆」补偿体验，不用逃逸口 |
| 5 | **Slash 命令膨胀** | Claude 约 40 个、Aider 30+，容易变成杂物抽屉 | 硬约束：每个命令必须映射既有 Bean，且 `--help` 中按「本机/内核/前缀」分组展示 |
| 6 | **双通道漂移** | `WebChannel` 与 `CliChannel` 长期各改各的 | 建立**通道契约测试**：同一会话、同一输入，断言两通道观察到的 `AgentEvent` 类型序列一致（P1-1 的验收项） |
| 7 | **补 `agent-skill`/`agent-mcp` 依赖后启动变慢 / Bean 冲突** | 新增模块可能引入额外 `@Configuration` | 补完后跑全模块 `clean test`（基线 228 用例）+ CLI 冷启动实测，确认无退化 |
| 8 | **P0-2 修复是行为变更** | 旧的伪造确认「能跑通」某些场景（运气好 op 恰好相同） | 必须配单测 + 手工验证一次真实写入场景，确认修复后仍能正常授权 |

---

## 7. 待确认事项（需老大决策）

> ⚠️ **本节已失效（2026-09-24）**：7 个问题已全部关闭，结论见 [`CLI方案-v2.md`](./CLI方案-v2.md)（v2.1 定稿）§0 决策台账与 §2/§3/§4/§5。保留原文仅为留痕。
> 关闭摘要：① 不接 Workflow ② **引 JLine + Picocli**（v2 §1 论证）③ **不改签名**、改 4 处读取点 + 删 1 处死字段（v2 §2）④⑤⑥ 做 ⑦ 接受小改内核（`/compact` 入口）。

| # | 问题 | 影响 | 我的建议 |
|---|------|------|----------|
| 1 | CLI 是否需要能跑 **Workflow**？（`agent-workflow` 是否进 CLI classpath） | 决定 P1/P2 范围；`/run <workflow>` 是否落地 | 先不接。Web 侧画布是工作流的主场，CLI 跑图收益低；等 CLI 站稳再评估 |
| 2 | 是否引入 **JLine + Picocli** 两个第三方依赖？ | P1 体验的天花板；项目一直在控制依赖 | **引入 JLine**（行编辑不可手写），Picocli 可选（可先手写解析） |
| 3 | `--max-turns` / `--max-budget` 的 per-run 覆盖是否要动内核？ | `CoreProperties` 是全局单例，per-run 覆盖需扩展 `ConversationManager.submit` 签名或走 `UserInput.extra` | 走 `UserInput.extra`（**不改签名，向后兼容**），P2 做 |
| 4 | 是否支持多工作空间快捷切换（`-w`）？ | 现状只取第一个 | 支持，且**必须打印所选工作空间**（当前静默选择，是排查噩梦的源头） |
| 5 | 会话是否支持多用户（`--user`）？ | 现状硬编码 `local-user` | 不做。本地单用户，D3 已定「数据不出本机」 |
| 6 | 是否要做「项目本地配置」`<cwd>/.lucky/settings.json`？ | 对应 `.claude/settings.local.json`；本地单机多项目场景有用 | 做，P1 |
| 7 | `/compact` 手动触发需内核暴露入口，是否接受改内核？ | 五级压缩管线当前只有自动触发 | 接受（小改动，加一个 `Mono<Void> compact(SessionRef)`） |

---

## 附录 A：证据索引（文件:行）

| 结论 | 证据位置 |
|------|----------|
| 时序倒置（P0-1） | `web/app/agent-cli/src/main/java/com/lucky/agent/cli/CliRunner.java:83`（`submit().block()`）、`:88`（`publisher.stream()`） |
| 伪造 ASK 确认（P0-2） | `CliRunner.java:97` |
| ASK 状态由通道缓存（违反契约） | `CliChannel.java:79-98`（`handleAsk` + `pendingConfirm` + `takePendingConfirm`）、`CliRunner.java:96` |
| 事件类型缺 4 类 + default 静默（P0-3） | `CliChannel.java:37-75` vs `agent-common/.../dto/AgentEventType.java`（14 个枚举值） |
| payload key 硬编码（P1-7） | `CliChannel.java:41`（`p.get("delta")`） |
| 会话随机生成，无持久化（P0-4） | `CliRunner.java:54` |
| 能力模块缺失（P0-5） | `web/app/agent-cli/pom.xml`（8 个依赖，无 skill/mcp）vs `web/app/agent-web/pom.xml`（含 skill、mcp、workflow） |
| `agent-core` 传递依赖链不含 skill/mcp | `web/app/agent-core/pom.xml` |
| 契约定义 | `agent-common/.../contract/AgentChannel.java` |
| Web 侧正确实现（参照） | `agent-web/.../stream/WebChannel.java`（四方法全实现） |
| 事件发布器 pending 队列语义 | `agent-core/.../util/runtime/AgentEventPublisher.java:49,71,96,107,115` |
| 会话仓库全部能力（零新增存储） | `agent-core/.../SessionRepository.java`（`upsertMeta:45`、`appendMessage:62/79`、`loadMessages:105`、`loadForReplay`、`listByUser`、`truncateAfter`、`delete`） |
| 取消能力已存在 | `ConversationStateManager.java:167`（`requestCancel`） |
| OPTIONS 答复入口已存在 | `ConversationManager.java:314`（`resolveChoice`） |
| 预算/回合配置项 | `agent-core/.../config/CoreProperties.java`（`runMaxTurns`、`runMaxBudget`、`orchestratorMode`…） |
| 本机数据目录（配置分层现状） | `~/.lucky_agent/`：`settings.json`、`LUCKY.md`、`config/{workspaces,permission-rules,skill-state,mcp-auth}.json`、`agent/`、`model-usage.json` |
| 上下文文件两级语义 | `RuleStore.java`（全局 `<frameworkRoot>/LUCKY.md` + 项目 `<workspacePath>/LUCKY.md`）、`BasePromptStore.java` |
| 一键启动脚本（需加分流） | `lucky.bat`、`lucky` |
| 可执行产物已就绪 | `agent-cli/pom.xml`（`spring-boot-maven-plugin` + `repackage`）、`target/agent-cli-1.0.0.jar` |

## 附录 B：未确证项（不得作为决策依据）

| # | 条目 | 状态 |
|---|------|------|
| 1 | Codex profiles 是否从 `config.toml` 迁移到 `$CODEX_HOME/<name>.config.toml` 独立文件 | 未确证，属规划中/已迁移存疑 |
| 2 | DeepSeek Harness v0.1 的插件 API 是否稳定、`~/.dsh` 配置结构 | developer preview，接口可能变动 |
| 3 | 各 CLI 的 flag 全集与 slash 命令全集 | 随版本变动，本报告为调研时点快照 |
| 4 | Crush 的 `crushrc` 内嵌 Bash 解释器的沙箱边界 | 未深入核实 |
| 5 | Gemini CLI 沙箱的 6 档 Seatbelt profile 具体参数 | 未逐档核实 |

---

## 附：一句话行动建议

**先做 P0 六项**（预计改动集中在 `CliRunner` 118 行 + `CliChannel` 103 行的重写，以及 pom 加两行依赖）。这六项做完，CLI 就从「内核能跑、用户看不见」变成「能实时对话、能续聊、中文不乱码」。**再谈体验**。

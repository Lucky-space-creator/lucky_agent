# CLI 方案 v3：对标 Codex / Claude Code / dsh 的落地后改良

> 版本：v3.0（**已有合理性审查：`CLI方案-v3-合理性审查.md`；其中指出 `/plan` 属范畴错误（§2.1）、`/init` 有格式约束（§2.2）、`/mcp` `/agents` 漏了 `CliServices` 扩容前提（§2.3）。本轮正文的错误定位**尚未落盘修订**，以审查报告为准**）　适用范围：`web/app/agent-cli`
> **三份文档的分工**：`CLI方案-v1.md` = 设计期调研（主流怎么做 / 我们差在哪）；`CLI方案-v2.md` = 决策定稿（已定什么 / 为什么这么选 / 批次 1–4）；**本版 = 落地后对标**（现有 CLI 已能做什么 / 对比三家最新操作模型还差什么 / 下一批改什么、不改什么）。
> **前提**：v2 的批次 1–4 **已全部落地并验收**（P0 六项 + 统一预算读取点 + enforcer + JLine REPL + `-w` 多工作空间 + 三级配置 + P2 六项），现有 CLI 具备 14 个 slash 命令、JLine 行编辑、headless 三格式、静态蓝色 Banner、退出码契约。
> **本版不再重复 v1 的功能罗列**，只做「**操作模型层面的深度对标**」——把三家的滑动条（快捷键、命令面、会话模型、心流设计）拆开，逐项对照我们已落地的东西，给出下一批改良清单。

---

## 0. 摘要

### 0.1 三家的共识（一句话各一条）

| 工具 | 一句话操作模型 | 对我们最有价值的一条 |
|------|----------------|----------------------|
| **Codex CLI**（Rust + ratatui，全屏 TUI） | 「把沙箱与审批做成两轴正交 + slash 命令即工作流入口」 | **`/` 键入即弹可过滤命令菜单 + Tab 把命令排队到下一轮**（命令的可发现性与并发操作） |
| **Claude Code**（Node + 自研 Ink，inline/alt-buffer 双渲染） | 「Agent 能力做成一等公民，会话内一切皆命令（60+）」 | **StatusLine 常驻 + `@` 文件补全 + `/context` 可视化占用**（把状态与上下文变成一眼可见） |
| **dsh**（TS + 全屏 alt screen，插件内核） | 「一切皆插件；CLI 是 Web UI 之外的另一个客户端」 | **工具调用卡片合并折叠（Ctrl+O）+ 会话统计栏（首 token 延迟/吞吐/缓存命中）**（流式噪音治理） |

### 0.2 本版的结论

1. **命令面是我们的最大结构性缺口**：三家都把「模型切换、模式切换、上下文查看、MCP/子代理管理、diff 审阅」做成**会话内命令**；我们目前**只有 14 个**，且**模型/模式只能靠启动参数**，会话内改不了。
2. **交互层缺两块地基**：`@` 文件路径补全（三家全有，我们零）、`/` 命令弹窗（我们有 Tab 补全但无描述菜单）。
3. **流式噪音治理三家都在做**，我们是「工具结果 data 折 6 行」，缺少**相邻工具调用合并折叠**。
4. **一项关键决策：不引入全屏 TUI**（理由见 §3.6）——吸收三家的**心流设计元素**，不吸收它们的**渲染架构**。
5. 改良分 4 层：**A 纯 CLI 低风险（P1，6 项）｜B 交互层（P1/P2，5 项）｜C 需小改内核（P2/P3，3 项）｜D 明确不做（守红线，5 项）**。

---

## 1. 三家 CLI 的操作模型解构

> 本章只讲「**做法与操作**」——用户具体敲什么、看什么、按什么键，不重复功能罗列。

### 1.1 渲染范式：三家分成了两种流派

| | Codex CLI | Claude Code | dsh (cli) |
|---|---|---|---|
| 技术栈 | Rust + **ratatui** | Node + **自研 Ink 分支**（React for terminal） | TypeScript + 全屏 alt screen |
| 主渲染 | **全屏 TUI**（占满终端） | **inline 为主**，`/tui fullscreen` 可切 **alt buffer** | **全屏 alt screen**（vim/htop 式） |
| 输入区 | 顶部/固定 | 底部固定输入框 | 底部固定输入栏 |
| 滚动 | 屏内滚动 | 依赖终端 scrollback | 屏内滚动 |
| 渲染管线 | 单层帧缓冲 | **三层**：React 组件 → Ink 虚拟 DOM → Screen 字符栅格 diff | 帧缓冲 |
| 关键取舍 | 布局可控、体验一致；**丧失终端原生 scrollback** | 默认保住 scrollback（可复制粘贴）；全屏模式解决闪烁 | 极致流畅；丧失 scrollback |

**读出来的信号**：全屏 TUI 换来「布局与流畅」，代价是「**丧失原生 scrollback 与鼠标复制**」——Claude Code 官方把全屏明确定位为「研究预览」，默认走 inline。**这是三家里唯一一个「大厂用 inline 兜底、把全屏当实验特性」的证据**，对我们的选型（§3.6）很关键。

### 1.2 命令面：从「启动参数」到「会话内一等公民」

三家都把高频操作做成 **slash 命令**，按主题归类后对比：

| 主题 | Codex | Claude Code | dsh (cli) | 本项**
|---|---|---|---|---|
| 会话 | `/new` `/resume` `/fork` `/compact` `/clear` | `/clear` `/resume` `/branch`(`/fork`) `/compact` `/rename` `/rewind` | `/session` | 部分有 |
| 模型 | `/model`（含 reasoning effort） `/fast` `/personality` | `/model` `/effort` `/fast` `/passes` | 自定义 provider | **仅启动参数** |
| 模式 | `/plan` | `/plan` | 权限徽章循环 | **无** |
| 权限 | `/permissions` `/sandbox-add-read-dir` | `/permissions` `/sandbox`（Shift+Tab 循环） | Shift+Tab 循环三档 | `/safety`（只读展示） |
| 上下文 | `/init` `/compact` `/status` `/diff` | `/init` `/context` `/cost` `/memory` `/status` | — | `/tokens` `/doctor` |
| 扩展 | `/mcp` `/apps` `/plugins` `/agent` `/experimental` | `/mcp` `/agents` `/hooks` `/plugin` `/skills` `/ide` | 插件体系 | **无** |
| 工具/审阅 | `/review` `/diff` `/mention` `/copy` `/statusline` `/theme` | `/diff` `/pr-comments` `/security-review` `/export` `/copy` `/rewind` `/btw` | 工具卡片折叠 | `/undo` |
| 诊断 | `/doctor` `/debug-config` `/feedback` | `/doctor` `/status` `/usage` `/insights` | 统计栏 | `/doctor` |
| 后台 | `/ps` `/stop` | `/background` `/tasks` `/desktop` | — | **无** |

**读出来的信号**：
- **`/model` 与 `/plan` 是三家的交集**，我们**都没有**（模型只能启动时定，模式由内核自动判）。
- **`/init`（生成上下文文件）三家都有**（`AGENTS.md` / `CLAUDE.md`），我们无。
- **`/mcp`、`/agents`（子代理）、`/hooks`** 是「扩展可视化管理」，我们无（但 `agent-mcp`/`agent-skill` 依赖已补，**具备实现基础**）。
- `/diff` 在 Codex/Claude 都是高频；我们只有 `!git diff`（裸 shell 前缀，不聚合、不着色）。

### 1.3 会话模型：resume / fork / compact / rewind

| 能力 | Codex | Claude Code | dsh | 现有 CLI |
|---|---|---|---|---|
| 续最近 | `codex resume` / `-c` | `-c` | 重开续上次 | ✅ `-c` |
| 按 ID 恢复 | `codex resume <id>` | `-r <id>` | `/session` | ✅ `-r` |
| **分叉 fork** | ✅ `codex fork` | ✅ `/branch` | ✅ | ❌（内核无 fork） |
| 压缩 | `/compact` | `/compact` | — | ✅ `/compact` |
| **回退 rewind** | 无（靠 Git checkpoint） | ✅ `/rewind` + Git checkpoint | — | ◐ `/undo`（仅上一轮） |
| 会话搜索 | 跨本地 chat 搜索 | `/resume` 带搜索 | ✅ 事件日志 search | ◐ `/sessions` 前 20 |
| 重命名 | `/title` | `/rename` | — | ❌（内核自动命名） |

**读出来的信号**：三家把会话当成**可分支、可回退的一等对象**；我们停在「列表 + 恢复 + 压缩 + 单轮回滚」。**fork 与 rewind 是两个真实的形态差**，但 fork 需要内核新增（§3.4）。

### 1.4 安全交互：两轴与「循环切换」

| | Codex | Claude Code | dsh | 现有 CLI |
|---|---|---|---|---|
| 呈现 | `sandbox_mode` × `approval_policy` **矩阵** | 权限规则 deny→ask→allow | 权限徽章三档 | `/safety` 两轴**只读展示** |
| 运行时切换 | `/permissions` | **Shift+Tab 循环** default→acceptEdits→plan | **Shift+Tab 循环** read-only→workspace-write→danger-full-access | ❌ 不可切（D2） |
| 逃逸开关 | `--dangerously-bypass…` | `--dangerously-skip-permissions` | danger-full-access | ❌ **明确不做** |

**读出来的信号**：三家都允许**运行时放宽权限**，这是它们「高危但高效」的来源。**我们的 D2 明确禁止 CLI 提供提权通道**（权限由执行臂 realpath 硬边界强制）。因此 Shift+Tab 循环这类「心流神器」**不能照抄**——但**「用快捷键快速查看当前安全态」是可以做的**（只读，不切换）。

### 1.5 心流设计：快捷键、Tab 排队、工具卡片、状态栏

这是三家差距最大、也最容易被忽略的一层：

| 设计元素 | 谁有 | 具体操作 | 现有 CLI |
|---|---|---|---|
| **`/` 弹可过滤命令菜单** | Codex 全有 / Claude | 敲 `/` 弹列表，继续打字过滤，↑↓ 选，Tab 补全参数 | ◐ **仅 Tab 补全命令名，无描述菜单** |
| **`@` 文件路径补全** | 三家全有 | 敲 `@` 弹工作区文件树，Tab 选 | ❌ **零**（`@` 只在提交时展开） |
| **Tab 把命令排队到下一轮** | Codex | 运行中敲命令 + Tab → 当前轮结束后自动执行 | ❌ |
| **工具卡片合并折叠** | dsh（Ctrl+O）/ Codex | 相邻工具调用合并一行，快捷键展开 | ◐ **data 折 6 行，但 action/result 不合并** |
| **Markdown 终端渲染** | dsh / Claude | 粗体/斜体/代码/列表用终端样式渲染 | ❌ **原样 println** |
| **StatusLine 常驻** | Claude（可脚本化，JSON 入 stdout） | 底部常驻行：context%、cost、git 分支 | ◐ **轮末一行（非常驻）** |
| **会话统计栏** | dsh | turns/steps、**首 token 延迟**、吞吐、缓存命中率 | ◐ `/tokens` 有 token，缺延迟/吞吐 |
| **Steer（运行中引导）** | Codex | 运行中追加指令，不打断本轮 | ❌ |
| **后台任务面板** | Codex `/ps` / Claude `/tasks` | 长命令转后台，可查看 | ❌ |
| **快捷键体系** | 三家 | Ctrl+O 展开 / Esc Esc 回退 / Ctrl+B 后台 / Ctrl+G 外编 | ◐ 仅 Ctrl+C / Ctrl+D |

**读出来的信号**：**「心流设计」几乎全是我们的空白区**，而它恰恰是「好用」与「能用」的分水岭。其中 **`@` 文件补全** 与 **`/` 命令菜单** 是**投入产出比最高的两项**——它们不触碰内核，纯 JLine 层，且立刻改变日常手感。

### 1.6 headless 与脚本化

| | Codex | Claude Code | 现有 CLI |
|---|---|---|---|
| 一次性执行 | `codex exec` | `-p` | ✅ `-p` / 位置参数 |
| 结构化输出 | `--json` / `-o <file>` | `--output-format text\|json\|stream-json` | ✅ 同三格式 |
| **强制 schema** | ✅ `--output-schema <schema>` | 无 | ❌ |
| 临时会话 | ✅ `--ephemeral`（不落盘） | 无 | ❌ |
| 输入格式 | — | `--input-format stream-json` | ❌ |

**读出来的信号**：headless 的**基线我们已对齐**（这点 v2 做得好）。缺的是**进阶自动化**（schema 约束、ephemeral、流式输入），属 P3。

### 1.7 三家共同「没做」的（反向验证我们的红线）

| 三家都没做 | 说明 | 与我们的红线 |
|---|---|---|
| 内置 RAG / 向量库 | 都靠 MCP 或外挂 | 我们无此野心 |
| 自建模型托管 | 全部 BYO-Key / 本地模型 | ✅ 一致（D1/D3） |
| CLI 内跑可视化工作流 | 都推到 Web/IDE/云 | ✅ 一致（决策 1） |
| 会话存数据库但可导出 | 都提供 JSON 导出 | 我们 JSONL 已够 |

**结论**：v2 定下的边界（不托管、不接工作流、不做计费）**被三家反向验证**——没人在这上面跟我们走岔。

---

## 2. 现状盘点：现有 CLI vs 三家基线

### 2.1 已达标项（不必再动，避免重复投入）

| 项 | 现状 | 证据 |
|---|---|---|
| 三前缀输入 | `/` 命令、`!` 直执行、`@` 文件引用 | `ReplLoop.java:91-120` |
| JLine 行编辑 | 历史落盘（`cli-history`）、命令行补全 | `LineEditor.java:80-109` |
| 会话恢复 | `-c` / `-r` / `/sessions` / `/resume`（含 ID 前缀、序号） | `SlashCommandRegistry.java:298-396` |
| 上下文压缩 | `/compact`（内核入口） | `:747-777` |
| 双回滚 | `/undo` | `:780-811` |
| 用量查看 | `/tokens`（含窗口占用） | `:567-640` |
| 环境自检 | `/doctor`（含配置来源） | `:643-718` |
| 安全呈现 | `/safety` 两轴只读 | `:814-853` |
| headless | `-p` + text/json/stream-json | `CliOptions.java:80-83` |
| 三级配置 | 命令行 > 环境变量 > 项目本地 + `/reload` | `:862-893` |
| 事件全覆盖 | 14 类事件经枚举穷举，未知不静默 | `EventRenderer.java:41-67` |
| 自定义命令 | `commands/*.md` | `:896-918` |
| 退出码契约 | 0/1/2/3/4/5 | `CliExitCode` |

**结论**：**「能用」这段已经走完，甚至超过多数开源 CLI**。下一步是「好用/顺手」。

### 2.2 差距矩阵（按主题，▲=缺口）

| 主题 | 三家基线 | 现有 | 差距等级 |
|---|---|---|---|
| 模型运行时切换 | `/model` | 仅 `--model` | **▲▲ 高** |
| 模式运行时切换 | `/plan` | 无 | **▲▲ 高** |
| 上下文文件生成 | `/init` | 无 | **▲ 中** |
| diff 审阅 | `/diff` | 仅 `!git diff` | **▲▲ 高** |
| MCP 查看 | `/mcp` | 无（依赖已补） | **▲ 中** |
| 子代理/技能查看 | `/agents` | 无（依赖已补） | **▲ 中** |
| 上下文可视化 | `/context` | `/tokens` 部分覆盖 | **▲ 低** |
| 会话导出/复制 | `/export` `/copy` | 无 | **▲ 中** |
| 会话分叉 | `/fork` | 无（内核无） | **▲ 中**（需内核） |
| `@` 文件补全 | 三家全有 | 零 | **▲▲ 高** |
| `/` 命令菜单 | 三家全有 | Tab 补全（无描述） | **▲▲ 高** |
| 工具卡片合并折叠 | dsh/Codex | 仅 data 折行 | **▲ 中** |
| Markdown 渲染 | dsh/Claude | 无 | **▲ 低**（成本中） |
| 常驻状态栏 | Claude | 轮末一行 | **▲ 低**（见 §3.6 取舍） |
| 统计指标（延迟/吞吐） | dsh | 缺 | **▲ 低** |

### 2.3 差距归因：分清「地基」与「楼层」

- **地基层（改一次、多处受益）**：`@` 文件补全、`/` 命令菜单、工具卡片折叠 —— 都在 JLine/EventRenderer 层，**不碰内核**。
- **楼层（各是各）**：`/model` `/plan` `/init` `/diff` `/mcp` `/agents` —— 每个是独立的一个命令实现。
- **另一栋楼（需内核）**：`/fork`、per-run mode 覆盖。

**实施顺序据此定**：先打地基（A/B 层的补全与菜单），再盖楼层（命令面），最后处理跨楼（内核）。

---

## 3. 改良方案

### 3.1 设计原则（沿用 v2，新增 3 条边界）

沿用：**启动期用 Picocli、会话内手写分发**（v2 §1）；**per-run 参数走 `ConversationCtx.extra`**（v2 §2.3，先例键 `modelId`/`confirm`/`personaId`/`suppressStop`）；**能力模块禁 `starter-web`**、**`agent-cli` 禁 `agent-workflow`**（enforcer 固化）。

本版新增 3 条边界：

| # | 边界 | 理由 |
|---|------|------|
| N1 | **只读地暴露安全态，不提供任何提权通道** | D2 不可动摇；三家的 Shift+Tab 提权**不抄**，但「按快捷键看当前安全态」可做 |
| N2 | **新命令一律复用既有内核入口，不新造存储/协议** | 会话走 `SessionRepository`、MCP 走 `agent-mcp`、技能走 `agent-skill`——**不引入第二套**（沿用 v2 §3.1 精神） |
| N3 | **命令默认不静默**：生效即回显实际值 | 复用 v2 §2.5 的处理（`max-turns=5（覆盖自 CLI）`），避免「改了没生效」的排查噩梦 |

### 3.2 A 层 —— 纯 CLI 侧、低风险（P1，6 项）

| # | 命令/参数 | 行为 | 落点 | 说明 |
|---|---|---|---|---|
| A1 | **`/model [名称\|序号]`** | 无参列出可用模型（当前标 ●）；带参切换**本会话后续轮次**模型 | `SlashCommandRegistry` 新命令 + `CommandContext` 加可变 `modelId` | **复用 `extra.modelId` 先例**，无需改内核；`ReplLoop.baseExtra()` 改读可变字段而非 `options.model` |
| ~~A2~~ | ~~**`/plan` `/act` `/ask`**~~ | ⚠️ **经审查撤回**：本项目 `Phase.PLAN/ACT/ASK` 是**编排阶段**（主循环推进），**非用户模式**，与三家的 `/plan` 语义不对应 —— 已移出本批。若要「先出计划」需按**新增语义**单独设计。依据：`CLI方案-v3-合理性审查.md` §2.1 | — | — |
| A3 | **`/init`** | 在**当前工作空间**生成/补全 `LUCKY.md` 骨架（项目上下文文件） | 走执行臂写文件（**受权限校验**，非直写） | 对齐三家 `/init`；**必须经执行臂**，不得 CLI 侧直写（D2） |
| A4 | **`/diff [--stat]`** | 展示工作空间 `git diff`，着色 + 统计 | 复用 `!` 前缀的执行臂通道，做包装 | 高频；现只能靠 `!git diff`（无着色/无统计） |
| A5 | **`/mcp`** | 列出已配置 MCP 服务、连接状态、可用工具数 | `agent-mcp` 的配置/注册表接口 | 依赖已在（v2 P0-5）；**只读展示** |
| A6 | **`/agents`** | 列出可用技能（`agent-skill`）与子代理定义 | `agent-skill` 的注册表 | 让「Agent 为什么调了这个技能」可查 |

> **A1/A2 的关键设计**：模型与模式是**会话级可变状态**。现状 `baseExtra()` 直接读 `options.model`（启动值），要支持运行时切换，需在 `CommandContext`/`SessionHolder` 增加**可变当前值**，`baseExtra()` 优先读它。**这是本层唯一的架构小改，但仍在 CLI 包内**。

### 3.3 B 层 —— 交互层升级（P1/P2，5 项）

| # | 能力 | 行为 | 落点 | 优先级 |
|---|---|---|---|---|
| B1 | **`@` 文件路径补全** | 行首/空格后输入 `@` → 弹出当前工作空间文件树（Tab/↑↓ 选择，支持子目录） | `CliRunner.installCompleter`（现有只处理命令）+ 新 `WorkspacePathCompleter` | **P1（最高性价比）** |
| B2 | **`/` 命令菜单** | 输入 `/` 即弹可过滤列表（**带 summary 描述**），继续打字缩小范围，↑↓ 选、Enter 确认 | `MutableCompleter` 的 `Candidate` 增加 `descr`；`SlashCommand.summary()` 已有 | **P1** |
| B3 | **工具卡片合并折叠** | 相邻 `action` + `tool_result` 合并为一行摘要（`🛠 read · ✅ 12 行`），`--verbose`/快捷键展开 | `EventRenderer.renderAction/renderToolResult` | P2 |
| B4 | **Tab 排队（run queue）** | 运行中敲内容 + Tab → 入队，本轮结束后自动作为下一轮提交 | `ReplLoop` 增加队列 + `CliTurnLoop` 回调 | P2 |
| B5 | **/tokens 增补延迟与吞吐** | 增加「首 token 延迟 / 输出吞吐 tok/s / 缓存命中率」 | `MetricsCollector` 快照（若已有则直接展示） | P2 |

> **B1/B2 是可以立刻提升手感的两项**：三家的「`@` 补全 + `/` 菜单」是标配，而我们是空白。JLine 的 `Completer` 天然支持 `Candidate.value/descr`（菜单带描述），**实现成本低、收益立竿见影**。
> **B1 的安全边界**：补全只读目录名，**不读文件内容**；且候选限定在当前工作空间 realpath 之内（与执行臂边界同源）。

### 3.4 C 层 —— 需小改内核（P2/P3，3 项）

| # | 能力 | 内核改动 | 优先级 |
|---|---|---|---|
| C1 | **`/fork` 会话分叉** | `SessionRepository` 新增 `fork(sessionId)`：复制历史到新会话 ID（JSONL append-only 天然友好） | P2 |
| C2 | **per-run mode 覆盖** | 若 A2 探测发现 `extra` 无 mode 通道，则在 `RunOverrides`（v2 已建）旁扩一个 `mode` 读取点 | P2（依赖 A2 探测结果） |
| C3 | **生命周期 hooks** | 新增 hook 事件总线（`PreToolUse`/`PostToolUse`/`Stop`…），配置在用户级 | P3（大工程，**先不做**） |

> **C1 的价值**：Claude `/branch` 与 Codex `fork` 都是高频——「同一个问题走两条路线，不污染原会话」。我们 `SessionRepository` 是 JSONL append-only（v1 §2.4 已确认），**fork 就是把已有行复制到一个新文件 + 新头部**，成本可控。
> **C3 的取舍**：Claude 有 36 个 hook 事件，是它「可脚本化治理」的核心。但这是**一整套事件总线 + 配置 + 沙箱**的工程，与「CLI 是第二通道」的定位不匹配，**列为 P3 观察，不承诺**。

### 3.5 D 层 —— 明确不做（守红线，5 项）

| # | 不做项 | 理由 |
|---|--------|------|
| D1 | `Shift+Tab` / `--dangerously-*` 类**运行时提权** | 违反 D2；三家的提权是「高危高效」的来源，我们不接 |
| D2 | 云端任务托管 / 远程会话 | 违反 D1/D3「零托管」 |
| D3 | 计费、额度、`--max-budget-usd` 计费语义 | 违反项目「不涉及计费」定调 |
| D4 | 插件市场 + 无审核社区分发 | 供应链风险；`agent-mcp` 的显式配置已够 |
| D5 | client/server 分离 + 常驻 daemon | 本地单用户；多进程只增运维成本 |

### 3.6 关键决策：不引入全屏 TUI（论证）

**结论**：**保持 inline（行式流式 + 轮末状态行），不引入 Codex/dsh 式全屏 TUI**。

| 论据 | 说明 |
|------|------|
| ① 战略定位 | 本项目 **Web 优先**（D11），CLI 是第二通道。全屏 TUI 是「重资产」，投入产出不匹配第二通道 |
| ② 渲染层重写 | 现有正文是 **`content_delta` 逐字流式**（打字机）。全屏需要**字符栅格 diff 引擎**（Claude 是「React → Ink 虚拟 DOM → Screen diff」三层管线；Codex 是 ratatui 帧缓冲）——等于重写整个渲染层 |
| ③ 平台风险 | Windows conpty 下全屏 raw mode 已知脆弱（本项目已踩过 JLine/Win 的坑，见 `PITFALLS.md`）；全屏会**丧失原生 scrollback 与鼠标复制**（Claude 官方也因此默认 inline，全屏仅「研究预览」） |
| ④ 已有取舍是自觉的 | `StatusBar` 注释已明确：轮末一行**刻意**回避了「底部常驻条与流式正文互相覆盖产生垃圾字符」的问题（`StatusBar.java:8-12`） |

**吸收 vs 不吸收**：

| 吸收（心流元素，不依赖全屏） | 不吸收（渲染架构） |
|---|---|
| `/` 命令菜单、`@` 补全（B1/B2） | 全屏 alt buffer |
| 工具卡片折叠（B3） | 三层虚拟 DOM 渲染 |
| 统计指标增补（B5） | 常驻输入框 |
| Tab 排队（B4） | 屏内滚动 |

**未来重开的触发条件**（写清楚，避免以后反复争论）：当 CLI 需要 **① 内联 diff 审阅**（大块改动逐行确认）或 **② 后台任务面板** 时，全屏才有不可替代的价值。**不是永不，是此时不做。**

---

## 4. 分批路线图

| 批次 | 内容 | 性质 | 完成判据 |
|:----:|------|------|----------|
| **5** | **B1 `@` 文件补全 + B2 `/` 命令菜单** | 纯 CLI 交互层，无内核改动 | 实机：敲 `@` 弹文件树、敲 `/` 弹带描述菜单；单测守约；`vue-tsc` 无关 |
| **6** | **A1 `/model` + A4 `/diff` + A5 `/mcp` + A6 `/agents`** | 纯 CLI 命令层 | 各命令端到端可验证；`/model` 切换后**下一轮**模型确实变了（看状态行/`/tokens`） |
| **7** | **A2 `/plan`（含模式探测）+ A3 `/init` + B3 工具卡片折叠** | A2 若探测到内核无通道则降级 C2 | `/plan` 生效或明确标注「需内核支持」；`/init` 只在工作空间内落文件 |
| **8** | **C1 `/fork` + C2 per-run mode + B4 Tab 排队 + B5 统计增补** | 含内核小改 | 全量 `clean test` 零回归；`/fork` 后原会话不被污染 |

**批次 5 先行**：两项都是 JLine 层、无内核依赖、用户感知最强，且互不耦合。

---

## 5. 风险与取舍

| # | 风险 | 表现 | 缓解 |
|---|------|------|------|
| 1 | **补全拖慢输入** | `@` 补全扫描大目录树导致卡顿 | 限定深度 + 目录数上限；异步候选；只在 `@` 触发时扫描 |
| 2 | **`/model` 切换与内核不同步** | CLI 认为切了，内核某条主环不认 | **必须先做 v2 §2.3 的 `RunOverrides` 同源校验**；切换后**回显实际生效模型**（N3） |
| 3 | **`/plan` 语义漂移** | CLI 的「模式」与内核 PLAN/ACT/ASK 含义不一致 | 实施前现场确认内核 mode 定义与入口，**语义以内核为准**，CLI 只做转发 |
| 4 | **`/init` 越权写文件** | 绕过执行臂直写工作空间 | **强制经执行臂**（`!` 前缀同一通道），受权限档与 realpath 边界约束 |
| 5 | **命令面膨胀** | 14 → 22+，`/help` 变长 | `/help` 按**主题分组**（会话/模型/扩展/诊断）；命令数超 ~35 时再评估 Picocli 子命令模型（v2 §1.4 已留口） |
| 6 | **fork 的历史一致性** | 分叉后两条会话互相影响 | fork 只复制**不可变历史**，不共享后续写入；`SessionRepository` append-only 天然隔离 |

---

## 6. 实施期需现场确认（非决策项）

| # | 事项 | 口径 | 阻塞 |
|---|------|------|------|
| 1 | 内核是否已支持 **per-run mode** 覆盖（`extra` 里的 mode 键） | 读 `ConversationManager#buildCtx` + `ReactEngine`，确认是否已有先例键；**有则 A2 纯 CLI，无则走 C2** | A2 |
| 2 | **模型列表来源**（`/model` 无参时列什么） | 复用既有 `settings.json` 的模型注册表（`role: main\|memory`），**不新造模型清单** | A1 |
| 3 | `agent-mcp` 暴露的**服务/工具查询接口**签名 | 现场读接口，`/mcp` 只调不改 | A5 |
| 4 | `agent-skill` 的**注册表查询接口** | 同上 | A6 |
| 5 | `MetricsCollector` 快照是否已含**首 token 延迟/吞吐** | 有则 B5 只改展示；无则评估是否补采集 | B5 |
| 6 | `@` 补全的**性能上限**（工作空间最大目录规模） | 定扫描深度/数量阈值 | B1 |

---

## 附录 A：证据索引（本版新增）

| 结论 | 证据位置 |
|------|----------|
| 现有 slash 命令共 14 个 | `SlashCommandRegistry.withBuiltins()`（`SlashCommandRegistry.java:62-79`） |
| 无 `/model` `/plan` `/init` `/diff` `/mcp` `/agents` | 同上（注册表内无这些名字） |
| 模型仅启动参数、无运行时切换 | `CliOptions.java:61-62`；`ReplLoop.baseExtra()` 直读 `options.model`（`ReplLoop.java:187-190`） |
| 补全只覆盖命令名，无文件路径补全 | `CliRunner.installCompleter`（`:233-239`）只调 `registry.completions()`；`LineEditor.MutableCompleter`（`:187-203`） |
| `Candidate` 未带描述（无菜单） | `CliRunner.java:239` 仅 `new Candidate(c)`，未设 `descr` |
| 工具结果折叠为 6 行、action/result 不合并 | `EventRenderer.DATA_PREVIEW_LINES=6`（`:26`）；`renderAction`/`renderToolResult`（`:111-151`） |
| 状态行为「轮末一行」且是自觉取舍 | `StatusBar.java:8-12`（注释明示「为什么不是底部常驻刷新条」） |
| 事件 14 类全覆盖、未知不静默 | `EventRenderer.render`（`:41-67`）、`renderUnknown`（`:264-268`） |
| 历史文件位置与 attach 修复 | `LineEditor.historyFile`（`:71-73`）、`history.attach(reader)`（`:107`） |
| 内核无会话 fork | 全仓 `agent-core` grep `fork` 无命中 |
| 无 per-run mode 覆盖通道 | 全仓 grep `"mode"` 仅 `agent-workflow/TriggerRequest`（工作流触发参数），非主环 |
| `RunOverrides` 已建（v2 §2.3） | `agent-core/runtime/budget/RunOverrides.java` |
| 静态蓝色 Banner 已落地 | `repl/Banner.java`（`BRAND="lucky_agent"`，65 列，无动画） |

## 附录 B：三家命令面速查（供 `/help` 分组与命名参考）

**Codex CLI**（全屏 TUI）：`/model` `/permissions` `/plan` `/new` `/resume` `/fork` `/compact` `/clear` `/diff` `/review` `/init` `/status` `/statusline` `/theme` `/agent` `/apps` `/plugins` `/mcp` `/mention` `/copy` `/ps` `/stop` `/experimental` `/personality` `/fast` `/logout` `/feedback` `/debug-config` `/exit`

**Claude Code**（inline / `--tui fullscreen`）：`/model` `/effort` `/fast` `/plan` `/permissions` `/sandbox` `/context` `/cost` `/usage` `/compact` `/clear` `/resume` `/branch` `/rename` `/rewind` `/diff` `/init` `/memory` `/agents` `/hooks` `/mcp` `/plugin` `/skills` `/ide` `/export` `/copy` `/background` `/tasks` `/doctor` `/status` `/statusline` `/theme` `/vim` `/keybindings` `/btw` `/goal` `/cd` 等 60+

**dsh cli**（全屏 alt screen）：`/exit` `/help` `/clear` `/session` `/model` `/permission`；操作键：`↑/↓` 跨会话历史、`Shift+Tab` 循环权限档、`Ctrl+O` 展开工具组、`Ctrl+C` 取消当前轮

---

**一句话**：**地基先打（`@` 补全 + `/` 菜单），楼层跟上（`/model` `/diff` `/mcp` `/agents`），跨楼后处理（`/fork` `/plan`）；渲染范式不动，心流元素全收。**

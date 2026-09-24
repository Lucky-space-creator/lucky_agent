# CLI 方案 v2：决策定稿 + 技术选型论证

> 版本：v2.1（**全部决策已定稿，可开工**）　适用范围：`web/app/agent-cli`
> 定位：`CLI方案-v1.md` 回答「主流怎么做 / 我们差在哪」；本版回答「已定什么 / 为什么这么选 / 下一步怎么做」。
> **本版不重复 v1 的调研数据与缺陷清单**，只引用其结论编号，并展开两个曾被提出的技术问题（选型、预算覆盖问题点）的论证与定稿。
>
> 变更记录：`v2.0` 决策 2 / 3 待定，附论证 → `v2.1` **采纳全部推荐**，决策 2 定为「JLine + Picocli 都引」、
> 决策 3 定为「做，拆 3a(P1) + 3b(P2)」，并新增 §6.1 开工顺序与 §7 实施期现场确认清单。

---

## 0. 决策台账（5 条全部定稿）

| # | 决策 | 结论 | 影响面 | 本版章节 |
|---|------|------|--------|----------|
| 1 | CLI 接不接工作流 | **不接** | pom 不加 `agent-workflow`；`/run`、`/workflows` 命令不开；v1 §4.7 中相关行作废；v1 §7-1 关闭 | §3 |
| 2 | 终端库与参数解析选型 | **JLine 3 + Picocli 同步引入**（不引 `spring-shell`） | P1 体验上限；`agent-cli` 新增 2 个第三方依赖（均零/少传递依赖） | §1 |
| 3 | per-run 预算/回合覆盖 | **做**；**不改任何方法签名**。拆 3a 统一读取点（P1，纯重构）+ 3b 通道暴露（P2） | 新增 `RunOverrides`；改 4 处读取点 + 删 1 处死字段 + 修 langgraph 双来源 | §2 |
| 4 | 支持 `-w` 多工作空间 | **做**；无参数时排除 `builtin`、Banner 必打印所选、多候选不猜 | `CliOptions` 增参数；`/workspace` 命令 | §4 |
| 5 | 新增项目本地配置层 | **做**；三级配置 + **键白名单过滤**（禁放密钥/权限规则） | `<cwd>/.lucky/settings.json`；`/reload` | §5 |

**一句话**：5 条决策全部定稿，**无待决策项**；从 §6.1 的批次 1 直接开工。

### 0.1 本版新增的两条工程决策（论证见对应章节）

| 决策 | 结论 | 理由摘要 |
|------|------|----------|
| 依赖红线用**构建期硬约束**而非注释 | **引入 `maven-enforcer-plugin`** | 把「文档里的规矩」变成「构建失败」，从根上杜绝误加（§3.2） |
| 输出通道唯一化 | `repl` / `channel` 包**禁止直接使用 `System.out`**，统一走 `Terminal.writer()` | JLine 接管终端后残留 `System.out` 会撕裂输出、冲掉状态栏（§1.5） |

---

## 1. 决策 2 定稿：JLine + Picocli —— 不是二选一

> 结论已定：**两个都引**，不引 `spring-shell`。本章为论证留档。

### 1.1 先厘清：两者根本不在同一层

这是选型的第一要务。二者**解决的是不同生命周期的问题**，可以并存，也可以只用其一：

| | Picocli | JLine 3 |
|---|---------|---------|
| 作用层 | **进程启动时的一次性参数解析** | **REPL 内每一行输入的编辑与读取** |
| 输入 | `String[] argv`（由 shell 切好） | 用户按键流（raw mode） |
| 产出 | 类型化的 `@Option` 字段 | 一行字符串 / 编辑事件 |
| 生命周期 | 启动一次，之后不再参与 | 全程常驻，直到 REPL 退出 |
| 现状替代物 | 手写 `args` 解析（当前是 `CliApplication` 直接丢弃 args） | 裸 `Scanner`（`CliRunner.java:63`） |

**结论**：现状的两个痛点各有归属 —— 「启动参数被丢弃」是 Picocli 的领域；「裸 Scanner 无行编辑」是 JLine 的领域。**不存在「选 A 还是选 B」，只存在「各自要不要引」。**

### 1.2 逐项对比

| 维度 | Picocli | JLine 3 |
|------|---------|---------|
| 解决的问题 | `@Command`/`@Option` 声明式解析、类型转换、`--help` 自动生成、子命令、错误提示统一 | 行编辑、光标移动、历史（↑↓）、Tab 补全、多行输入、括号粘贴、Ctrl 键绑定、ANSI 色彩、终端能力探测 |
| 手写替代成本 | **约 80~120 行**（13 个参数，含校验与 `--help` 文本） | **不可手写**（跨平台 raw mode + ANSI + conpty 等价于重写一个终端库） |
| 依赖体积 | 单 jar，**零传递依赖**（~400 KB） | 主 jar + `jline-terminal-jni`/`-ffm` provider（Windows 需额外 provider） |
| 生态地位 | Java CLI 参数解析事实标准（Quarkus CLI 等） | 终端行编辑事实标准（Groovy Shell、Kafka CLI、Spark Shell、Spring Shell 底层） |
| 与本项目的耦合面 | 仅 `CliOptions` 一处 | `repl` 全包 + 事件渲染（ANSI）+ 状态栏 |
| 关键风险 | 几乎无（纯解析，不碰 IO） | **终端接管后必须统一输出通道**（见 §1.5） |
| 必需性 | **可选**（成本低但非阻断） | **必需**（P1「好用」目标的天花板即在此） |

### 1.3 为什么不选 spring-shell

`spring-shell` 是 Java + Spring 生态里「做 CLI」的自然候选，且它会自动装配 Spring 上下文。**但它与本项目的设计冲突**：

| 冲突点 | 说明 |
|--------|------|
| **自带命令体系** | 它有自己的 `@ShellComponent` + `@ShellMethod` 注册模型，与我们既定的 slash 命令分层、前缀语义（`/` `!` `@`）打架 |
| **自带输出体系** | 它接管终端渲染，而我们需要**事件驱动的流式渲染**（14 类 `AgentEvent`，含 `content_delta` 打字机、`task_progress` 原地刷新）—— 与「命令 → 返回值」模型不匹配 |
| **装配耦合** | 它会往 Spring 上下文里注入自己的配置与生命周期，而我们需要 CLI 上下文**尽量精简**（当前启动已 3.11s，见 v1 §3.2） |
| **体积与控制力** | 为拿到行编辑能力引入一整套框架，属于「用大炮打蚊子」；JLine 是它的底层，我们直接用底层更可控 |

**判断**：`spring-shell` 是给「以命令为中心」的工具用的；我们是「以流式会话为中心」，**不用**。

### 1.4 推荐结论（**已采纳为决策**）

> ✅ **两个都引。JLine 3 必需，Picocli 同步引入。不引 `spring-shell`。**

理由（按重要性）：

1. **JLine 不可替代**：行编辑/历史/补全/ANSI/Windows conpty 是 P1 体验的全部来源，手写不现实。
2. **Picocli 成本极低、收益明确**：单 jar、零传递依赖、不碰 IO，换来 `--help` 自动生成、类型转换、参数错误提示一致。手写虽可行，但那是**为省一行依赖写 100 行不会维护的解析代码**。
3. **两者有协同空间**（这是「两个都引」而非「只引 JLine」的真正理由）：Picocli 可提供 `picocli-shell-jline3` 桥接模块，把 `@Command` 模型直接变成 JLine 的 `Completer` → **同一份参数定义既生成 `--help` 又驱动 Tab 补全**，避免两套定义漂移。

**边界（必须划清，否则后续一定有人用错）**：

| 场景 | 用什么 | 理由 |
|------|--------|------|
| **进程启动参数**（`-p` / `-c` / `-w` / `--output-format`） | **Picocli** | 典型 argv 语义，`--opt value` 形态 |
| **REPL 内 slash 命令**（`/model`、`/sessions`） | **自写轻量 dispatcher + JLine `Completer`** | slash 命令参数极简（0~1 个位置参数），Picocli 的 `--opt` 语义与 REPL 体感不符；且 `/help` 需要的是「分组命令列表」而非 Picocli 的 `Usage: … Options: …` |

> **为什么不把 slash 命令也建成 Picocli 子命令模型**：技术上可行（`new CommandLine(spec).parseArgs(tokens)`），能用上免费的补全。但代价是 slash 命令的补全输出会带 Picocli 的 argv 形态（`--xxx` 选项提示），需要额外裁剪；且命令数约 20、参数极简，收益不抵耦合。**若后续 slash 命令数超过 ~35 或出现多级子命令，再考虑迁移到 Picocli 子命令模型。**

**版本注意事项**（实施时必须现场确认，不照抄本文）：

- JLine 3 的 `jline-terminal-jni` / `jline-terminal-ffm` provider 在 Windows conpty 下的行为**随小版本变化**，选型时需锁定并实测。
- `picocli-shell-jline3` 与 JLine 主版本强绑定，需核对兼容矩阵。

### 1.5 已预见的工程风险（引 JLine 后的真实代价）

| # | 风险 | 具体表现 | 缓解 |
|---|------|----------|------|
| 1 | **输出通道撕裂** | JLine 接管终端后，代码里残留的 `System.out.println` 会**绕过终端抽象**，导致光标错位、状态栏被冲掉、行编辑区残留垃圾 | 硬约束：`repl`/`channel` 包内**禁止直接使用 `System.out`**，统一走 `Terminal.writer()`；加 ArchUnit 或 grep 门禁 |
| 2 | **无 TTY 环境崩溃** | 重定向/CI/管道下 `Terminal` 创建失败或行为异常 | 启动时探测 `System.console() == null` → 自动降级 `--plain`（纯 `PrintStream`），不走 JLine |
| 3 | **Windows 中文乱码（P0-6）与 JLine 耦合** | 终端编码、JLine provider、JVM 编码三方需一致 | 启动脚本统一 `chcp 65001` + `-Dfile.encoding=UTF-8` + JLine 显式指定 UTF-8；实测验收 |
| 4 | **Ctrl+C 语义冲突** | JLine 会拦截按键，与「取消当前运行」需要区分「中断输入」与「取消任务」 | 明确绑定：`Ctrl+C`（运行中）= 取消本次运行（`requestCancel`）；`Ctrl+D` = 退出 REPL；运行中再按 `Ctrl+C` = 退出进程 |
| 5 | **历史文件落盘位置** | JLine 默认写在 `~/.jline` 之类 | 显式指定到 `<frameworkRoot>/agent/cli-history`，纳入既有目录规范（D4） |

---

## 2. 决策 3 定稿：per-run 回合/预算覆盖 —— 做，但不改签名

> 结论已定：**做**，拆 3a（P1）/ 3b（P2）。核心判断 —— **工程量不在 CLI，在于先统一内核的 4 个读取点**。本章为问题点剖析与实施方案留档。

### 2.1 问题不在 CLI，在内核：同一份配置有 4 个读取点、3 种载体

我按「`runMaxTurns` / `runMaxBudget` 被谁读」做了全量排查：

| # | 读取位置 | 载体 | 语义 | 问题 |
|---|----------|------|------|------|
| 1 | `ConversationStateManager.SessionState:147` | 自建 `RunBudget`（会话级） | 会话生命周期内持有 | **死字段** —— 只有 `private final RunBudget budget`(:133)、构造赋值(:147)、getter(:224) 三处，**全仓零调用者** |
| 2 | `Orchestrator:131` | 自建 `RunBudget`（每次编排） | reactor 主环安全阀 | 与 #3 语义重叠、来源相同 |
| 3 | `RuntimeSessionFactory.open:36` | `BudgetScope(GLOBAL)` | thin / langgraph 的四级预算根 | 由 `BudgetMiddleware` 消费 |
| 4 | `LangGraphOrchestrator:510` | **直读 `properties.runMaxTurns()`** | langgraph 回合安全阀 | **双来源**：同一条主环里既有 `sessions`（#3）又直读 properties |

**归纳**：

- `RunBudget.maxTurns` / `maxBudgetTokens` 是 `final`（见 `RunBudget.java`）→ **无法修改，只能新建实例替换**。
- 4 个读取点里有 1 个是**死代码**，1 个是**双来源**（langgraph 同时经 `RuntimeSessionFactory` 与直读 properties），另 2 个语义重叠。
- 这三条问题**都是既有架构债，不是 CLI 引入的**。CLI 只是第一个真正需要 per-run 覆盖的通道。

### 2.2 为什么不能「只改一处」

假设只在 CLI 侧把覆盖值塞进 `UserInput.extra`，然后只改 `RuntimeSessionFactory`：

| 场景 | 结果 |
|------|------|
| `core.orchestrator-mode=thin`（默认灰度之外） | ✅ 生效（走 `RuntimeSessionFactory`） |
| `core.orchestrator-mode=reactor`（当前默认） | ❌ **不生效** —— 走 `Orchestrator:131` 自建 budget |
| `core.orchestrator-mode=langgraph` | ⚠️ **半生效** —— 预算生效，但 `:510` 的回合安全阀仍读全局值 |

**这正是 `RuntimeSessionFactory` 这个类存在的意义** —— 它的类注释写着：*「存在的意义是消除引导逻辑漂移……任何一处漏改都会让两个主循环的行为不一致」*。而 per-run 覆盖如果不统一，就会**重新打开这个口子**，且症状是静默的（用户传了 `--max-turns 5`，某条主环不认，任务照跑到 30 回合）。

**结论：per-run 覆盖的工程量不在 CLI，在于先统一读取点。**

### 2.3 采用的方案：零签名改动（✅ 已采纳）

好消息 —— 我核查了调用链，**`ConversationCtx` 已经是贯穿载体，且已经承载了 `UserInput.extra`**：

| 事实 | 证据 |
|------|------|
| `ConversationCtx` 有 `Map<String,Object> extra`（`@Builder.Default new HashMap<>()`） | `ConversationCtx.java` |
| **三条主环都已把 `base.extra()` 合并进 ctx** | `Orchestrator.java:637`、`ThinAgentLoop.java:157`、`LangGraphOrchestrator.java:559` —— 均为 `extra.putAll(base.extra())` |
| `RuntimeSessionFactory.open(SessionRef, **ConversationCtx ctx**, …)` **已接收 ctx** | `RuntimeSessionFactory.java` |
| `Orchestrator.run(SessionRef, **ConversationCtx ctx**, …)` **已接收 ctx** | `Orchestrator.java` |
| `ThinAgentLoop` 调用 `sessions.open(ref, **ctx**, publisher, runtime)` | `ThinAgentLoop.java:63` |
| extra 已是既定的 per-run 透传通道（先例存在） | `"confirm"`、`"modelId"`、`"personaId"`、`"suppressStop"` 均走 extra |

**因此**：读取点改成从 `ctx.extra()` 取值，**不需要改任何方法签名**。

```
统一入口（新增，agent-core/runtime/budget/RunOverrides.java）：
    static RunOverrides from(ConversationCtx ctx, CoreProperties props)
        - maxTurns  = ctx.extra().get("maxTurns")  解析成功则用，否则 props.runMaxTurns()
        - maxBudget = ctx.extra().get("maxBudget") 解析成功则用，否则 props.runMaxBudget()
        - 解析失败 → 记 WARN 日志 + 用默认值 + 发一条 progress 事件告知（不静默）
```

改造点（4 处，全部小改）：

| # | 位置 | 改法 |
|---|------|------|
| 1 | `ConversationStateManager.SessionState:133/147/224` | **删除死字段** `budget` 与 getter（零调用者，删除无行为影响） |
| 2 | `Orchestrator:131` | `new RunBudget(...)` → `RunOverrides.from(ctx, properties).toRunBudget()` |
| 3 | `RuntimeSessionFactory.open:36` | `properties.runMaxBudget()` → `RunOverrides.from(ctx, properties)` |
| 4 | `LangGraphOrchestrator:510` | 直读 `properties.runMaxTurns()` → 改读 `#3` 建的同一个 `BudgetScope`，消除双来源 |

### 2.4 实施拆分（**已采纳为决策**）：两步走，风险隔离

> ✅ **决策：拆 3a（P1）+ 3b（P2）两步执行。**

| 步 | 内容 | 性质 | 验收 | 期次 |
|----|------|------|------|------|
| **3a** | 统一读取点：新增 `RunOverrides`、改 4 处、删死字段 | **纯重构，行为不变** | 全量 `clean test` 228 用例零回归；新增单测：不传 extra 时取值 == 原 `properties` 值 | **P1** |
| **3b** | CLI 暴露 `--max-turns` / `--max-budget` 并透传进 `UserInput.extra` | 新增能力 | 端到端：`--max-turns 2` 时断言第 3 回合被拦下并返回退出码 4 | **P2** |

**为什么 3a 放 P1 而不是 P2**：它是**纯重构且修掉两处既有缺陷**（死字段 + langgraph 双来源），可以独立验证、独立回滚，与 CLI 无耦合。先做掉，3b 就只是「加两个参数」。

**为什么 3b 放 P2**：P0/P1 的目标是「能用 + 好用」，预算调节属于进阶诉求；且 3a 完成后 3b 成本极低，不影响主线。

### 2.5 允许覆盖的范围（白名单）与禁止项（黑名单）

预算与回合是**安全阀**，通道能调它，但必须限定边界：

| | 项 | 说明 |
|---|---|---|
| ✅ 可覆盖 | `runMaxTurns`（`maxTurns`） | 单次运行的回合硬上限 |
| ✅ 可覆盖 | `runMaxBudget`（`maxBudget`） | 单次运行的 token 预算 |
| ✅ 可覆盖 | `modelId` | **先例已存在**（`ConversationManager:914`） |
| ❌ 不可覆盖 | 拆解步数上限（thin 的 8 步）、子代理深度（≤2）、单轮派发上限（≤4） | 这些是**防失控的硬约束**，与「用户想跑长任务」不是一回事 |
| ❌ 不可覆盖 | `subagentTaskTimeoutSec` 等超时 | 同上 |
| ❌ 不可覆盖 | **权限级别、`deny/ask/allow` 规则** | D2：由执行臂硬边界强制，通道无入口（见 v1 §4.8） |
| ❌ 不可覆盖 | 并发上限（`subagentMaxConcurrency`） | 影响本机资源，非会话级参数 |

**强制要求**：覆盖生效时**必须打印实际生效值**（如 `max-turns=5（覆盖自 CLI）`）。当前 `resolveWorkspaceId()` 静默选工作空间已是排查噩梦的源头，**不要再制造第二个静默覆盖**。

---

## 3. 决策 1 定稿：CLI 不接工作流

### 3.1 固化内容

| 项 | 决定 |
|----|------|
| `agent-cli/pom.xml` | **不添加** `agent-workflow` 依赖 |
| CLI 命令面 | **不开** `/run`、`/workflows`（v1 §4.7 中相关行作废） |
| 理由（记录用） | 工作流的表达力在画布（编排 + 连线 + 变量作用域可视化）；终端里跑图收益低、调试成本高。Web 是工作流的主场 |
| 未来重开条件 | 当 CLI 具备 `--recipe` 式声明式工作流需求时（参考 Goose Recipe），再评估。**不是永不，是此时不做** |

### 3.2 加强防线：用构建期硬约束，而不是靠注释

当前项目规则「能力模块**绝不引** `spring-boot-starter-web`/`-tomcat`」是**靠人记**的。加上本决策后，「哪些模块可以依赖、哪些绝对不可以」变成了两条硬规则。✅ **已定：引入 `maven-enforcer-plugin` 固化**（我已核实：父 pom 当前**没有** enforcer，也没有 `bannedDependencies`）：

```xml
<plugin>
  <artifactId>maven-enforcer-plugin</artifactId>
  <executions>
    <execution>
      <id>ban-forbidden-deps</id>
      <goals><goal>enforce</goal></goals>
      <configuration>
        <rules><bannedDependencies>
          <excludes>
            <!-- 规则 1：能力模块绝不引 Web 容器 -->
            <exclude>org.springframework.boot:spring-boot-starter-web</exclude>
            <exclude>org.springframework.boot:spring-boot-starter-tomcat</exclude>
            <!-- 规则 2：见『CLI 不接工作流』决策（仅 agent-cli 模块配置） -->
            <exclude>com.lucky.agent:agent-workflow</exclude>
          </excludes>
        </bannedDependencies></rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

**收益**：把「文档里的规矩」变成「构建失败」，从根上杜绝后人误加（包括我以后误加）。

**注意**：`agent-cli` 的 `bannedDependencies` 需**只在该模块**配置（parent 配会让 `agent-web` 自己就违规）。实施时用模块内 `<plugin>` 覆盖，或在 CLI 模块单独声明 `bannedDependencies` 且 `excludes` 只列 `agent-workflow`。

---

## 4. 决策 4 定稿：多工作空间 `-w`

### 4.1 现状的两个隐患（已核实）

看 `CliRunner.resolveWorkspaceId()`（`:103-117`）：

1. **静默选择**：偏好 `FULL` / `MODIFY`，否则取 `listWorkspaces().get(0)`。**用户完全不知道选中的是哪个** —— 排查噩梦的源头。
2. **可能落进内置默认工作空间**：`Workspace` 有 `builtin` 标志，其物理路径位于**框架根内**（`~/.lucky_agent/workspace`，`Workspace.java` 注释明示）。若用户只注册了内置工作空间（或它恰好排第一且权限为 MODIFY），CLI 就会**在框架根内运行 Agent** —— 而框架根里放着 `config/`、`memory/`、`skills/`、`sessions/`。权限边界虽然由执行臂强制，但「默认落点选在框架自己的数据目录里」在体验与心智上都不合适。

### 4.2 设计

| 项 | 设计 |
|----|------|
| 参数 | `-w, --workspace <id\|名称\|路径>`；匹配顺序：`workspaceId` 精确 → `name` 精确 → `name` 模糊 → `path` realpath 归一后精确（用 `WorkspaceConfig.listWorkspaces()` + `physicalPathOf` 自行匹配，接口本身不提供按名查找） |
| 无参数时 | 保持现状的优先级（`FULL`/`MODIFY` 优先）**但排除 `builtin`**；仅当无其它工作空间时才用内置；**并在 Banner 打印所选**：`工作空间: <name> (<id>) · 权限: MODIFY · 路径: /…` |
| 多候选且无 `-w` | 若存在多个候选（同为 FULL/MODIFY），**不猜** —— 打印候选列表并要求用 `-w` 指定（交互式 REPL 下可直接问；headless 下报退出码 2） |
| REPL 内切换 | `/workspace`（列出 + 标出当前）、`/workspace <id\|名称>`（切换） |
| 切换副作用 | `SessionRef` 含 `workspaceId` → **切换工作空间 = 开新会话**（不可在同会话内换），UI 必须明确提示 |
| 补全 | JLine `Completer` 动态从 `listWorkspaces()` 取候选 |
| headless | `-p -w <id>` 必须显式给出（无交互机会），否则退出码 2 |

---

## 5. 决策 5 定稿：项目本地配置层

### 5.1 优先级（高 → 低）

```
1. 命令行参数
2. 环境变量                 LUCKY_MODEL / LUCKY_WORKSPACE / LUCKY_MODE / LUCKY_OUTPUT_FORMAT / LUCKY_MAX_TURNS …
3. 项目本地                 <cwd>/.lucky/settings.json          ← 本版新增
4. 用户全局                 ~/.lucky_agent/settings.json + ~/.lucky_agent/config/*.json
5. 内置默认                 CoreProperties 紧凑构造器兜底
```

**为什么是 3 级不是 5 级**：Claude Code 的 5 级包含「系统 managed 层」与「企业策略层」，本项目是本地单用户、零托管（D1/D3），多出的层只会增加「为什么这个值没生效」的排查成本。Aider 的 home → repo → cwd 三级与本场景贴合。

### 5.2 安全红线（这是本决策最重要的部分）

**`<cwd>/.lucky/settings.json` 是可能被 commit 进代码库的文件。** 因此：

| | 键 | 理由 |
|---|---|---|
| ✅ 允许 | `model`（**模型名引用**，非密钥） | 团队统一模型选择是合理诉求 |
| ✅ 允许 | `mode`、`outputFormat`、`plain` | 无安全含义 |
| ✅ 允许 | `maxTurns`、`maxBudget` | 调参，且受 §2.5 白名单约束 |
| ✅ 允许 | `workspace`（工作空间名引用） | 项目绑定工作空间合理 |
| ❌ **禁止** | `apiKey` / 任何密钥材料 | API Key 只在用户级 `settings.json`（AES-GCM 加密，D8），**绝不进项目文件** |
| ❌ **禁止** | `permissionRules` / 权限级别 | 权限提升必须走用户级；否则 clone 一个仓库即可提权（D2） |
| ❌ **禁止** | `mcp` 服务端点 / `mcp-auth` | MCP 授权在 `~/.lucky_agent/config/mcp-auth.json`，不被项目文件影响 |
| ❌ **禁止** | `workspaceRoot` / `frameworkRoot` | 路径根由用户级配置决定，项目文件不得改写 |

**校验方式**：加载项目本地配置时做**键白名单过滤**，出现禁止键 → **拒绝加载该文件**并打印明确错误（不静默忽略，否则用户会以为生效了）。这类「静默降级」正是本项目反复踩的坑。

### 5.3 其他约定

| 项 | 约定 |
|----|------|
| 读取时机 | 启动时一次；提供 `/reload` 手动重载（对齐 `LUCKY.md`「改完保存即生效」的既有语义，v1 §4.9 的关键约束） |
| 查找起点 | `--cwd <path>` 可覆盖；默认取进程 CWD |
| `.gitignore` | 建议项目侧忽略 `.lucky/settings.local.json`（若后续加 local 变体）；按项目约定**锚定写法** |
| 与框架根冲突 | 若 CWD 恰在 `<frameworkRoot>` 内（如从 `~/.lucky_agent/workspace` 启动），**跳过**项目本地层并从用户级起，避免自己读自己 |

---

## 6. 修订后的实施清单

以 v1 §5 为基线，**本版变化用 ▲ 标注**。

### P0 —— 让 CLI「能用」（不变，六项）

| # | 任务 | 状态 |
|---|------|------|
| 1 | 时序修复：先订阅后提交（v1 §4.4） | 不变 |
| 2 | ASK 回传**真实** `op`（v1 §4.8） | 不变 |
| 3 | 事件 14 类全覆盖 + `fromJsonValue` + `default` 不静默（v1 §4.5） | 不变 |
| 4 | 会话落盘 + `-c` / `-r`（v1 §4.6） | 不变 |
| 5 | 补 `agent-skill` / `agent-mcp` 依赖（v1 §3.3 P0-5） | 不变（**仅这两个，不含 workflow**） |
| 6 | UTF-8（v1 §4.12） | 不变 |

### P1 —— 让 CLI「好用」（▲ 有变化）

| # | 任务 | 变化 |
|---|------|------|
| 1 | `CliChannel implements AgentChannel`（四方法） | 不变 |
| 2 | JLine REPL（历史/补全/多行/Ctrl+C 语义） | ▲ 明确：**引 JLine + Picocli**（§1.4） |
| 3 | Slash 命令（本机元操作 + 内核转发） | ▲ 去掉 `/run`、`/workflows`；新增 `/workspace`、`/reload`（§3、§4、§5） |
| 4 | 状态栏 | ▲ 新增要求：**打印所选工作空间与权限档**（§4.1） |
| 5 | headless `-p` + `--output-format` | 不变 |
| 6 | 退出码契约 | ▲ 新增触发源：工作空间多候选未指定 → `2`（§4.2） |
| 7 | 配置三级 + `LUCKY_*` | ▲ 明确含**键白名单过滤**（§5.2） |
| 8 | `-w` 多工作空间 | ▲ **新增**（决策 4） |
| 9 | **统一预算/回合读取点（3a）** + 删死字段 + 修 langgraph 双来源 | ▲ **新增**（§2.4） |
| 10 | maven-enforcer 依赖红线 | ▲ **新增，已采纳**（§3.2） |
| 11 | `!` shell 前缀、`@` 文件引用 | 不变 |

### P2 —— 让 CLI「顺手」（▲ 有变化）

| # | 任务 | 变化 |
|---|------|------|
| 1 | per-run 覆盖暴露 `--max-turns` / `--max-budget`（3b） | ▲ 依赖 P1-9 完成 |
| 2 | `/compact` 手动压缩 | 不变（需内核暴露入口，已接受小改内核） |
| 3 | `/undo` 双回滚 | 不变 |
| 4 | `/tokens` 成本聚合 | 不变 |
| 5 | 自定义命令目录 `commands/*.md` | 不变 |
| 6 | 两轴安全模型展示（sandbox × approval） | 不变 |

### 已关闭

| 项 | 结论 |
|----|------|
| v1 §7-1「CLI 是否接 Workflow」 | **不接**（决策 1，§3） |
| v1 §7-2「选型」 | **JLine + Picocli**（决策 2，§1） |
| v1 §7-3「预算覆盖是否改内核」 | **不改签名**，改 4 处 + 删 1 处死字段（决策 3，§2.3） |
| v1 §7-4「`-w` 多工作空间」 | **做**（决策 4，§4） |
| v1 §7-5「`--user` 多用户」 | **不做**（本地单用户，D3） |
| v1 §7-6「项目本地配置」 | **做**（决策 5，§5） |
| v1 §7-7「`/compact` 是否改内核」 | **接受小改**（P2-2） |

### 6.1 开工顺序（4 个批次，每批可独立验证与回滚）

| 批次 | 包含 | 为什么这个顺序 | 完成判据 |
|:----:|------|----------------|----------|
| **1** | **P0 六项**（时序 / ASK 真实性 / 事件全覆盖 / 会话落盘 / 补 skill+mcp 依赖 / UTF-8） | 互不依赖、全在 `agent-cli` 内、**用户感知最强**；补依赖后可立刻跑通一次真实多轮对话 | 终端能实时看到模型输出，且 `-c` 能续上一轮 |
| **2** | **P1-9 统一预算读取点（3a）** + **P1-10 enforcer** | 纯重构（行为不变）+ 构建约束，**与 CLI 功能解耦**，可独立验证回滚；先做掉，批次 3 就只剩「加参数」 | 全量 `clean test` 228 用例零回归；enforcer 生效后误加依赖即构建失败 |
| **3** | **P1-1~8、11**（`AgentChannel` 契约 / JLine REPL / slash 命令 / 状态栏 / headless / 退出码 / 三级配置 / `-w` / 前缀） | 依赖批次 1 的渲染层与批次 2 的配置层；本批引入 JLine，**需先做 §1.5 的 5 项防护** | 契约测试（双通道事件序列等价）+ headless `stream-json` 可被 `jq` 逐行解析 |
| **4** | **P2 六项** | 依赖批次 3 | 各自验收项 |

**批次 3 的前置动作（不可跳过）**：JLine 版本锁定 + Windows provider 实测（§7-1）；定 `Terminal.writer()` 唯一输出通道并加门禁（§1.5 风险 1）；无 TTY 降级 `--plain`（风险 2）。

---

## 7. 实施期需现场确认的技术细节（**非决策项**）

> 以下 5 项**不需要拍板**，是实现时必须用真实环境验证的技术细节。已按此执行，若有出入在批次内解决。

| # | 事项 | 执行口径 | 阻塞 |
|---|------|----------|------|
| 1 | JLine + Picocli 版本锁定（含 Windows provider 选择） | 现场定版并**实测 conpty**；优先官方 managed provider；记录到 `agent-cli/pom.xml` 的注释里 | P1-2 |
| 2 | JLine 历史文件位置 | `<frameworkRoot>/agent/cli-history`（纳入 D4 目录规范） | P1-2 |
| 3 | `<cwd>/.lucky/settings.json` 的禁止键清单 | 先按 §5.2 八条执行；实施中若发现新泄漏面再追加 | P1-7 |
| 4 | `/compact` 内核入口 | 新增 `Mono<Void> compact(SessionRef)`（**已接受小改内核**） | P2-2 |
| 5 | `maven-enforcer` 配置位置 | parent 配会让 `agent-web` 自违规 → **只在 `agent-cli` 模块配 `bannedDependencies`** | P1-10 |

---

## 附录 A：本版新增证据索引

| 结论 | 证据位置 |
|------|----------|
| 死字段：会话级 `RunBudget` 零调用者 | `ConversationStateManager.java:133`（字段）、`:147`（赋值）、`:224`（getter）；全仓 grep `session(...).budget()` 无命中 |
| reactor 自建预算 | `Orchestrator.java:131` |
| 四级预算根创建 | `RuntimeSessionFactory.java:36`（`BudgetScope(GLOBAL, runMaxBudget, 0L, runMaxTurns)`） |
| langgraph 双来源 | `LangGraphOrchestrator.java:101`（持有 `RuntimeSessionFactory sessions`）、`:510`（直读 `properties.runMaxTurns()`） |
| `RunBudget` 不可变 | `RunBudget.java`（`maxTurns` / `maxBudgetTokens` 均 `final`） |
| `ConversationCtx` 已是 extra 载体 | `ConversationCtx.java`（`@Builder.Default Map<String,Object> extra = new HashMap<>()`） |
| 三条主环均已合并 extra 进 ctx | `Orchestrator.java:637`、`ThinAgentLoop.java:157`、`LangGraphOrchestrator.java:559`（`extra.putAll(base.extra())`） |
| `RuntimeSessionFactory.open` 已接收 ctx | `RuntimeSessionFactory.java`（`open(SessionRef, ConversationCtx, AgentEventPublisher, AgentRuntime)`） |
| thin 传 ctx | `ThinAgentLoop.java:63` |
| extra 先例键 | `"confirm"`（`ConversationManager:603/759`）、`"modelId"`（`:914`、`LoopMemoryManager:138`、`ReactEngine:569`、`LlmJudgeVerifier:209`）、`"personaId"`（`ReactEngine:459`）、`"suppressStop"`（`ReactEngine:629`） |
| 工作空间静默选择 | `CliRunner.java:103-117` |
| 内置工作空间位于框架根内 | `Workspace.java`（`builtin` 字段注释：`<user.home>/.lucky_agent/workspace`） |
| `WorkspaceConfig` 可用方法 | `WorkspaceConfig.java`：`permissionLevelOf` / `physicalPathOf` / `getWorkspace` / `listWorkspaces`（**无按名/路径查找**，需 CLI 侧自匹配） |
| 父 pom 无 enforcer | `web/app/pom.xml` 的 `<build>` 仅含 `maven-compiler-plugin`、`maven-surefire-plugin` |
| CLI 模块当前依赖数 | `agent-cli/pom.xml`：9 个 `<dependency>`；全项目**未引入** jline / picocli / spring-shell |

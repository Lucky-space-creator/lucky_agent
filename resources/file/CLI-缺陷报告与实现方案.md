# Lucky Agent CLI 缺陷报告与实现方案

> 文档定位：CLI（`web/app/agent-cli`）**真机验收**的缺陷台账 + 可执行修复方案 + 可行性论证。
> 全部结论均标注证据来源（源码 `file:line` 或实测记录）；无法确证的部分显式标为「待验证」。
>
> 审计日期：2026-10-04　｜　被测产物：`agent-cli-1.0.0.jar`（fat jar）
> 本轮审计**未修改任何源码**。

---

## 〇 审计方法与可复现基线

### 0.1 测试手段

| 手段 | 说明 |
|---|---|
| **真实终端测试** | `pywinpty` 起 **ConPTY 真伪终端** + `pyte` 做 **VT100 屏幕仿真**，抓取「用户实际看到的屏幕」，而非管道重定向 |
| 非 TTY 对照 | `subprocess` 管道执行，验证 headless 分流契约 |
| 字节级取证 | 直接读原始输出流、`.bat` 文件字节、会话落盘 JSON |
| 单元测试基线 | `mvn -pl agent-cli test` → **106/106 通过** |

### 0.2 环境基线

| 项 | 值 |
|---|---|
| JDK（PATH） | `java` → **17.0.1** |
| JDK（项目要求） | `D:\jdk21\bin\java` → **21.0.11** |
| 控制台代码页 | **936（GBK）**（未执行 `chcp 65001`） |
| 工作空间 | 3 个：内置「默认工作空间」(MODIFY)、`test`(MODIFY)、`SpringBlade`(FULL) |
| 历史会话 | 29 个 |
| 关键依赖 | fat jar 内 `BOOT-INF/lib/` 共 93 项，其中 jline 仅 **`jline-3.30.17.jar`**；**无 jna / jansi 库** |

### 0.3 复现命令

```powershell
# 构建
$env:JAVA_HOME="D:\jdk21"; $env:PATH="D:\jdk21\bin;$env:PATH"
cd e:\JavaPro\lucky_agent\web\app; mvn -DskipTests -pl agent-cli -am package

# 基线复现（直启）
D:\jdk21\bin\java.exe -Dfile.encoding=UTF-8 -jar agent-cli\target\agent-cli-1.0.0.jar --version
D:\jdk21\bin\java.exe -Dfile.encoding=UTF-8 -jar agent-cli\target\agent-cli-1.0.0.jar -w test -p "hi"

# 启动脚本复现（损坏）
e:\JavaPro\lucky_agent\lucky-cli.bat --version
```

---

## 一 功能现状清单

### 1.1 实测可用

| 功能 | 实测证据 |
|---|---|
| `--help` / `--version` | rc=0；picocli 用法表完整；`lucky-agent-cli 1.0.0` |
| 非法参数处理 | `--nosuchflag` → `Unknown option` + usage，**rc=2** |
| 输出格式三级解析 | `--output-format yaml` → stderr 告警「未知的输出格式「yaml」，已回落为 text。」并继续（rc=0） |
| **headless 双通道契约** | `-p` 非 TTY：stdout 仅正文、stderr 仅进度+日志（实测 stdout 4B / stderr 9741B）✅ **分流契约达成**。注意：该轮的 rc=1 是模型调用失败（D15）所致，非分流问题 |
| JSON 输出结构 | 合法单 JSON：`sessionId / status / summary / error / tokenUsed / turnTokens / durationMs / exitCode` |
| REPL 首屏与行编辑 | 横幅、工作空间/会话信息、`? ` 提示符、历史文件落盘均正常 |
| slash 命令 | `/help` `/safety` `/workspace`(`/ws`) `/tokens` `/history` `/sessions` `/doctor` `/exit` 输出正确 |
| 工作空间歧义（TTY） | 列出候选 + 询问（设计正确，但见 D9） |
| 工作空间歧义（非 TTY） | 明确报「非交互环境无法询问，请用 -w 指定后重试」rc=2（不猜，符合设计） |
| 非 TTY 进 REPL 守卫 | 明确报错 rc=2，不卡死 |
| 工作空间不存在 | 明确报「未找到匹配的工作空间」rc=2 |
| 会话持久化 | `agent/sessions/*.jsonl` + `*.meta.json` 读写正常 |
| **中文输出编码** | 程序自身输出（经 JLine）中文**完全正确** |

### 1.2 实测不可用

| 功能 | 实测结果 | 缺陷号 |
|---|---|---|
| `lucky-cli.bat` | 完全无法解析 | D1 |
| `lucky.bat`（Web 启动器） | 同上（同一缺陷） | D1 |
| 启动脚本定位 JDK 21 | 用 PATH 上 JDK 17 启动即崩 | D2 |
| `!<命令>` 直执行 | 100% 失败（双重门禁） | D3 / D4 |
| Ctrl+C 取消本轮 | 直接杀进程 | D5 |
| 中文输入 | 提交内核的文本被毁成 `?` | D6 |
| 日志中文 | 乱码 | D7 |
| `--list-sessions`（无 `-w`） | rc=2 失败 | D8 |

### 1.3 本轮**未覆盖**面（审计边界，诚实声明）

以下能力本轮**未做真机验证**，其状态在本报告中**不作任何结论**：

| 未覆盖项 | 说明 |
|---|---|
| `-c` / `-r` 与 `-p` 的组合（非 TTY） | 仅验证了「无 `-p` 时二者报需要交互终端」 |
| `--auto-approve` / `--auto-pick` | headless 与 TTY 两种形态下的实际代答行为均未触发验证 |
| `@<路径>` 文件引用 | 未测（需构造文件并经执行臂读取） |
| `/new` `/undo` `/compact` `/reload` `/resume` | 仅 `/resume` 的失败路径间接出现过 |
| 自定义命令（`<cwd>/commands/*.md`、`<frameworkRoot>/commands/*.md`） | 未测 |
| `--show-thinking` / `--plain` | 未测 |
| 记忆召回、Skill 渐进披露、MCP 工具在 CLI 下的实际注入 | 未测（仅从日志见到 MCP 探针活动） |
| 退出码矩阵的完整性 | 仅覆盖常见路径，未穷举 `CliExitCode` 全部取值 |

> 因此本报告的完成度评估是**下界性质**：未覆盖面若存在问题，只会使完成度更低，不会更高。

---

## 二 缺陷详表

### 2.0 索引速查

| 编号 | 级别 | 一句话 | 证据类型 | 修复批次 |
|---|---|---|---|---|
| D1 | P0 | `.bat` 行尾 LF → `cmd.exe` 无法解析，两启动器全废 | 文件字节 + 实测输出 | 批次 1 |
| D2 | P0 | 启动脚本不定位 JDK 21 → 用 JDK 17 启动即崩 | 实测 + 源码 | 批次 1 |
| D3 | P0 | CLI 无 `application.yml` → 沙箱未启用 + 核心参数与 Web 不一致 | 源码 + 实测 | 批次 2 |
| D4 | P0 | MODIFY 档 EXEC 全拒，报错不可诊断 | 源码链 + 实测 | 批次 5 |
| D5 | P0 | Ctrl+C 直接杀进程，与文档契约相反 | 真终端实测 ×2 + 源码 | 批次 3 |
| D6 | P1 | 中文输入被毁成 `?`，永久污染落盘标题 | 同屏对比 + 落盘字节 | 批次 3 |
| D7 | P1 | 日志中文乱码，与自身注释断言相反 | 实测 ×12 行 + 逐字节验证 | 批次 4 |
| D8 | P1 | `--list-sessions` 无 `-w` 失败（只读命令被迫绑工作空间） | 实测 + 源码 | 批次 5 |
| D9 | P2 | 工作空间歧义选错不重问，直接退出 | 真终端实测 + 源码 | 批次 5 |
| D10 | P2 | 退出耗时抖动 + 退出期堆栈污染终端 | 实测 ×2 | — |
| D11 | P2 | `--model` / `--max-turns` 无效值静默忽略 | 实测 | 批次 5 |
| D12 | P2 | 列表标题截断无省略号 | 实测 | 批次 5 |
| D13 | P3 | `· 开始PLAN阶段` 进度重复 | 实测 stderr | 批次 5 |
| D14 | P3 | 测试包名三套并存 | 源码 | 批次 5 |
| D15 | P1（越界） | 思考模式下第二轮模型调用被 API 拒绝 | 实测 ×2 + 栈定位 | 批次 6（独立立项） |

> 级别定义：**P0** = 阻断主功能/交付入口；**P1** = 主功能受损或数据损坏；**P2** = 体验/一致性缺陷；**P3** = 整洁度。「越界」= 缺陷不在 `agent-cli` 内，但影响 CLI 可用性。

### D1（P0）启动脚本行尾为 LF，`cmd.exe` 无法解析

**现象**（实测 `lucky-cli.bat --version`）：

```
'目"         headless 单轮' is not recognized as an internal or external command
'数' is not recognized as an internal or external command
== 构建 CLI（首次）==                       ← jar 存在，却判定为首次
'o' is not recognized as an internal or external command
java 17.0.1 2021-10-19 LTS                ← 不是 lucky-agent-cli 1.0.0
EXIT=0                                     ← 失败被掩盖
```

**证据**

| 文件 | 字节数 | CRLF | LF-only | BOM |
|---|---|---|---|---|
| `lucky-cli.bat` | 1523 | **0** | **40** | false |
| `lucky.bat` | 910 | **0** | **34** | false |

**溯源**：[lucky-cli.bat:18](file:///e:/JavaPro/lucky_agent/lucky-cli.bat#L18)（`chcp`）、[:24-29](file:///e:/JavaPro/lucky_agent/lucky-cli.bat#L24-L29)（首建判断，因子被拆散而恒真）、[:39](file:///e:/JavaPro/lucky_agent/lucky-cli.bat#L39)（`java` 调用参数被污染）、[:40](file:///e:/JavaPro/lucky_agent/lucky-cli.bat#L40)（`exit /b %ERRORLEVEL%` 在错乱解析下返回 0）。[lucky.bat:11](file:///e:/JavaPro/lucky_agent/lucky.bat#L11)、[:32](file:///e:/JavaPro/lucky_agent/lucky.bat#L32) 同构。

**根因**：`cmd.exe` 批处理解析要求 **CRLF**。LF 行尾使 `rem` 与其后文本被拆散、`set "JAR=..."` 未生效。

**影响**：Windows 上**唯一的一键启动入口不可用**，且同时波及 Web 启动器；失败被 `exit /b 0` 掩盖，用户看到的是 JDK 版本横幅而非报错。

---

### D2（P0）启动脚本不定位 JDK 21

**现象**

```
java -version                → java version "17.0.1"
D:\jdk21\bin\java -version   → openjdk version "21.0.11"

java -jar agent-cli-1.0.0.jar --version
→ UnsupportedClassVersionError: com/lucky/agent/cli/CliApplication
  has been compiled by a more recent version (class file version 65.0),
  this version ... recognizes up to 61.0
```

**溯源**：[lucky-cli.bat:39](file:///e:/JavaPro/lucky_agent/lucky-cli.bat#L39) 与 [lucky.bat:32](file:///e:/JavaPro/lucky_agent/lucky.bat#L32) 均为裸 `java`；两脚本内**无 `JAVA_HOME`/`jdk21` 探测**（全仓库仅 `resources/CLI方案-v1.md:270` 出现过 `D:/jdk21/bin/java.exe` 的硬编码示例）。

**性质**：`AGENTS.md` 待做清单已记载「构建脚本需确保用 JDK 21 启动服务」——该条**未落地到脚本**，属已知意图未实现。

---

### D3（P0）CLI 缺 `application.yml` → 执行臂沙箱未启用 + 核心参数与 Web 不一致

**现象**（FULL 权限工作空间 `SpringBlade`）：

```
? !echo cli-exec-ok
$ echo cli-exec-ok
执行失败：命令执行沙箱未启用，需用户显式授权
```

**溯源**

1. `agent-cli/src/main/resources/` 下**只有 `logback-spring.xml`**（全量 glob 确认），**无 `application.yml`**。
2. 沙箱开关契约：[ExecutorProperties.java:10-18](file:///e:/JavaPro/lucky_agent/web/app/agent-executor/src/main/java/com/lucky/agent/executor/config/ExecutorProperties.java#L10-L18) 声明「命令执行沙箱（默认关闭）」；[:22-26](file:///e:/JavaPro/lucky_agent/web/app/agent-executor/src/main/java/com/lucky/agent/executor/config/ExecutorProperties.java#L22-L26) 仅兜底 `transport`。
3. 注入点：[ExecutorConfig.java:64-66](file:///e:/JavaPro/lucky_agent/web/app/agent-executor/src/main/java/com/lucky/agent/executor/config/ExecutorConfig.java#L64-L66) `new CmdSandbox(properties.sandboxEnabled(), ...)`。
4. 打开它的配置**只存在于 Web 侧**：[application.yml:25-29](file:///e:/JavaPro/lucky_agent/web/app/agent-web/src/main/resources/application.yml#L25-L29) `executor.sandbox-enabled: true`。

**影响面被低估——不止沙箱一项**。CLI 无 yml 意味着 `agent-web/application.yml` 中的**全部业务键**在 CLI 侧缺席，仅靠代码默认值。已核出**确定的行为分叉**：

| 键 | Web（yml） | 代码默认 | CLI 实际 |
|---|---|---|---|
| `executor.sandbox-enabled` | `true` | `false` | **false → 命令全禁** |
| `core.orchestrator-max-iterations` | `5` | [`CoreProperties:59-61`](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/config/CoreProperties.java#L59-L61) → `3` | **3（安全阀更早触发）** |

（`planMaxSteps/actMaxSteps/runMaxTurns/verificationTimeoutSec/retryBackoffMs/earlyStopConfidenceThreshold/orchestratorMaxRetries/subagentMaxConcurrency/subagentTaskTimeoutSec` 经比对默认值与 yml 一致，暂不影响。）

**性质**：**Web/CLI 配置漂移**。同一内核、同一执行臂，但通道间能力与策略不一致 —— 与决策 D11/D20「通道不得另起一套业务逻辑」在**结果**上背离；`/help`（[SlashCommandRegistry.java:214](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/command/SlashCommandRegistry.java#L214)）却宣传 `!<命令>`。

---

### D4（P0）MODIFY 权限档下 EXEC 一律 DENY，且报错不可诊断

**现象**（默认工作空间 `test`，权限档「修改文件」）：

```
? !cd     → $ cd     → 执行失败：权限拒绝
? !whoami → $ whoami → 执行失败：权限拒绝
? !echo cli-bang-ok  → 执行失败：权限拒绝
```

**溯源链（逐跳）**

1. [PermissionEvaluator.java:173-177](file:///e:/JavaPro/lucky_agent/web/app/agent-permission/src/main/java/com/lucky/agent/permission/support/rules/PermissionEvaluator.java#L173-L177)
   ```java
   case MODIFY -> {
       if (op.opType() == FileOp.OpType.EXEC) {
           return PermissionDecision.DENY;   // 修改档一律拒绝命令执行
       }
   }
   ```
   调用上下文：[:87-91](file:///e:/JavaPro/lucky_agent/web/app/agent-permission/src/main/java/com/lucky/agent/permission/support/rules/PermissionEvaluator.java#L87-L91)（`evaluateByLevel`）
2. [LocalTransport.java:81-84](file:///e:/JavaPro/lucky_agent/web/app/agent-executor/src/main/java/com/lucky/agent/executor/support/arm/LocalTransport.java#L81-L84) 折叠为无上下文文案 `"权限拒绝"`
3. [ShellPrefix.java:102](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/input/ShellPrefix.java#L102) 原样打印

**性质判定**：「命令执行需 FULL 档」本身**是可辩护的安全设计**（不在本报告主张推翻）。**缺陷在于**：
- 报错不含原因与补救路径，用户无从知道要升权限档；
- `/help` 宣传 `!<命令>` 时未标注前提；
- 叠加 D3 后，`!` **在任何配置下都不可能成功**（MODIFY 被权限拒、FULL 被沙箱拒）。

---

### D5（P0）Ctrl+C 直接杀进程 —— 与自身文档契约完全相反

**现象**（真终端，两次独立实验）

| 场景 | 结果 |
|---|---|
| 空闲提示符处按 **1 次** Ctrl+C | `alive = False` —— **进程立即退出** |
| 一轮执行中按 **1 次** Ctrl+C | `alive = False` —— **进程立即退出，屏幕无任何取消提示** |

**文档承诺（三处互相印证）**

- 启动横幅 [ReplLoop.java:223](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/repl/ReplLoop.java#L223)：`Ctrl+C 取消本轮，提示符处连按两次 Ctrl+C 退出`
- `/help` [SlashCommandRegistry.java:218](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/command/SlashCommandRegistry.java#L218)：`运行中按 Ctrl+C 可优雅取消本轮；提示符处连按两次 Ctrl+C 退出。`
- 设计注释 [LineEditor.java:27](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/term/LineEditor.java#L27)（提示符处 Ctrl+C 应「清空当前行、重新提示」）、[:38](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/term/LineEditor.java#L38)（连续第二次才退出）、[:136](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/term/LineEditor.java#L136)（应打印 `^C （再按一次 Ctrl+C 退出，或输入 /exit）`）

**实现意图**：[CliTurnExecutor.java:145-190](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/CliTurnExecutor.java#L145-L190) 的 `CancelWatcher` 守护线程轮询 `System.in` 的 `0x03`，注释明确写「而不是让 JVM 直接终止进程」。

**实测**：上述提示**从未出现**，JVM 被直接终止。→ 说明终端**未进入 raw 模式**，`0x03` 仍触发 OS 级 SIGINT。

---

### D6（P1）中文输入被内核解成 `?`，并永久污染落盘数据

**决定性证据（同一轮内同屏对比）**

```
? 请只回复OK三个字符                       ← 屏幕回显：完全正确
· 收到你的请求：??????OK???????             ← 内核实际收到：U+FFFD
· The user message is garbled: "??????OK???????" — this is an encoding-broken Chinese message.
                                          ← 连模型都判定输入已损坏
```

**落盘取证**（`C:\Users\LENOVO\.lucky_agent\agent\sessions\*.meta.json`）

| 会话 | `title` 落盘值 | 判定 |
|---|---|---|
| `38f53221…` / `8ead6cea…` | 含 U+FFFD | **损坏** |
| `aef4af12…` / `da86cb66…` | 含 U+FFFD | **损坏** |
| `33d2cf97…` / `f0108c5a…`（**非 TTY** `-p` 写入） | `只回复两个字：收到` | ✅ 正常 |
| 09-24 及更早历史标题 | 正常 | ✅ |

字节为**合法 UTF-8** 但已含替换字符 → 损坏发生在**写入之前的内存中**，且**非 TTY 路径不受影响**。

---

### D7（P1）日志中文乱码 —— 与自身注释断言相反

**现象**（直启 + `LOGGING_LEVEL_ROOT=INFO`：21 条日志中 **12 条乱码**）

```
18:08:41.722 INFO c.l.a.c.c.WorkspaceDirs - 妗嗘灦蹇呭鐩綍鎵弿瀹屾垚锛歳oot=C:\Users\LENOVO\.lucky_agent
                                            ↑ 应为「框架必备目录扫描完成，root=」
```

同屏内**程序自身输出中文正确、日志中文乱码**。

**溯源**：[logback-spring.xml:11-12](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/resources/logback-spring.xml#L11-L12) 断言「…故中文日志**不会**出现 GBK 乱码」——**实测证伪**；[:17-24](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/resources/logback-spring.xml#L17-L24) appender 配置。对照 [CliApplication.java:34-35](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/CliApplication.java#L34-L35)（换流）与 [OutputSink.java:60-95](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/channel/OutputSink.java#L60-L95)（UTF-8 流）。

**逐字节验证（关键，勿凭印象）**：`框架` 的 UTF-8 字节为 `E6 A1 86 E6 9E B6`，按 **GBK 双字节切分**得 `(E6A1)=妗`、`(86E6)=嗘`、`(9EB6)=灦` → 拼出实测观察到的 **`妗嗘灦`**。即观测值**精确等于**「UTF-8 字节被按 GBK 解码」的结果，而非近似。这排除了「logback 按 GBK 编码写出」的替代假设，把根因锁定为**写出字节的编码**与**接收端的代码页**不一致。

---

### D8（P1）`--list-sessions` 不带 `-w` 直接失败

```
> lucky-cli --list-sessions
存在多个可用工作空间，请明确指定一个（CLI 不代为猜测）：
  1. test …
  2. SpringBlade …
  非交互环境无法询问，请用 -w 指定后重试
rc=2
```

**溯源**：[CliRunner.java:136-144](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/CliRunner.java#L136-L144) 的 `pickWorkspace(...)` 失败即 `return CliExitCode.USAGE`；`listSessions` 分支在 [:147-150](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/CliRunner.java#L147-L150) —— **之后**。

**已核实可安全前移**：`printSessions`（[CliRunner.java:342-353](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/CliRunner.java#L342-L353)）只依赖 `sessions` 与 `out`，**不依赖 workspace**。且会话本就不按工作空间隔离（加 `-w test` 时列出的仍是全部 29 个会话）。

---

### D9（P2）工作空间歧义选错不重问，直接退出

[CliRunner.java:272-282](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/CliRunner.java#L272-L282)：非数字与越界**都** `return Optional.empty()` → 直接 rc=2。

```
请选择序号（回车放弃）: abc
请输入数字序号。          ← 然后进程退出，无第二次机会
```

提示语「回车**放弃**」被复用于「输错」路径，语义不当；「请选择序号」隐含可重试。

---

### D10（P2）`/exit` 退出耗时抖动 + 退出期堆栈污染终端

同一路径两次实测：一次 **0.5s** 干净退出（exitstatus=0）；一次 **>5.5s 仍存活**，且退出过程中刷出 MCP 连接失败完整 Java 堆栈（`MCP 请求超时：playwright#initialize`）。疑与 `ctx.close()` 等待在飞的 MCP 健康探针有关。

> 诚实标注：MCP 探针失败本身可能被本次审计环境放大，本项**只主张**「退出耗时抖动 + 堆栈污染终端」两个现象，不主张 MCP 故障为产品缺陷。

---

### D11（P2）无效的 `--model` / `--max-turns` 静默忽略

```
--model no-such-model --list-sessions  → rc=0，无任何告警
--max-turns abc        --list-sessions → rc=0，无任何告警
```

对照：`--output-format yaml` 会告警、`-w` 不存在会报错 —— 同属用户输入校验，口径不一致。

---

### D12（P2）列表标题截断无省略号

`--list-sessions` / `/sessions` 中长标题硬截断且无 `…`：
`20. 09-22 16:18  我是通义千问（Qwen），由阿里巴巴集团通义实验`

---

### D13（P3）进度消息重复

headless stderr 中 `· 开始PLAN阶段` **连续出现两次**。

### D14（P3）测试包名不统一

`agent-cli/src/test/java/` 下 13 个测试类位于 `cli.*` 包，1 个（`LineEditorHistoryTest`）位于 `com.lucky.agent.cli.term`；主源码为 `com.lucky.agent.cli.*`。三套命名并存。

---

### D15（P1，超出 CLI 范围）模型闭环在思考模式下可复现失败

**现象**

```
rc=1；stdout=「收到」；stderr 尾部：
❌ [ACT] 模型调用失败：{"error":{"message":"The `reasoning_content` in the thinking mode
   must be passed back to the API. ..."}} 回退=错误:ASK
[1 失败] 模型调用失败：…
```

**复现性**：同一提示词 `-p "只回复两个字：收到"` **连续 2 次失败**；换 `-p "hi"` 则**成功**（rc=0，产出完整中文回答）。→ 非必然失败，与调用轮次/思考内容相关。

**栈定位**

| 帧 | 位置 |
|---|---|
| 抛出 | [ReactEngine.java:225](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/util/engine/ReactEngine.java#L225) |
| 调用 | `ReactEngine.streamTurn:748` → `syncTurn:789` → `OpenAiCompatibleModel.chat:143` |
| 底层 | `dev.langchain4j.model.openai.OpenAiChatModel.doChat` → HTTP 400 |

**已有的对策（未被覆盖）**：[OpenAiCompatibleModel.java:74-80](file:///e:/JavaPro/lucky_agent/web/app/agent-model/src/main/java/com/lucky/agent/model/support/endpoint/OpenAiCompatibleModel.java#L74-L80) 已实现 `returnThinking(true)` + `sendThinking(true,"reasoning_content")`（同步与流式各一份，[:96-98](file:///e:/JavaPro/lucky_agent/web/app/agent-model/src/main/java/com/lucky/agent/model/support/endpoint/OpenAiCompatibleModel.java#L96-L98)），其 javadoc 精确描述了本错误。**说明修复意图存在但未覆盖自建会话历史的重建路径**（疑似消息在 `ConversationStateManager` / `ReactEngine` 组装 `ChatRequest` 时经 `AiMessage.from(text)` 重建而丢掉 `thinking()`；相关锚点：`ReactEngine.java:310/325`、`ConversationStateManager.java:104-111`）。

> **范围声明**：该缺陷位于 `agent-core` / `agent-model`，**Web 通道同内核同受影响**，不属 CLI 独有。列入本报告是因为它使 CLI 的 Agent 闭环同样不可靠；修复应单独立项。

---

## 三 实现方案

### 批次 1 — 启动可用性（阻断级，必须先做）

| 编号 | 改动 | 落点 |
|---|---|---|
| F1.1 | `lucky-cli.bat` / `lucky.bat` 转为 **CRLF** 保存 | 两脚本 |
| F1.2 | 新增 `.gitattributes`：`*.bat text eol=crlf`、`*.sh text eol=lf`、`*.cmd text eol=crlf` | 新文件（仓库根） |
| F1.3 | 新增共用辅助脚本 `_jdk.bat`：解析顺序 `%LUCKY_JDK_HOME%` → `%JAVA_HOME%` → 常见安装位置（含 `D:\jdk21`）；**校验主版本 ≥ 21**，不满足则打印可行动错误并 `exit /b 1` | 新文件 + 两脚本调用 |
| F1.4 | 移除/修正掩盖失败的 `exit /b %ERRORLEVEL%` 链路，确保构建失败与启动失败都返回非 0 | 两脚本 |
| F1.5 | `chcp 65001` 保留，但改为「记录原 CP → 切换 → 退出时恢复」，避免污染用户会话 | `lucky-cli.bat` |

**验证**：`lucky-cli.bat --version` 输出 `lucky-agent-cli 1.0.0`（非 JDK 横幅）；`lucky-cli.bat --help` 正常；`%JAVA_HOME%` 指向 17 时给出明确 JDK 21 报错并 rc≠0。

---

### 批次 2 — 配置一致性（根因级，消除整类缺陷）

| 编号 | 改动 | 落点 |
|---|---|---|
| F2.1 | 新增 `agent-common/src/main/resources/lucky-defaults.yml`，承载 **Web/CLI 共用**业务键（`executor` / `core` / `model` / `memory` / `skill` / `mcp`） | 新文件 |
| F2.2 | `agent-web` 与 `agent-cli` 各自 `application.yml` 加 `spring.config.import: classpath:lucky-defaults.yml`；web 侧只保留 `server.port` / `spring.*` / `logging.*` / `lucky.web.frontend-dist` | 2 文件 |
| F2.3 | 新增 `agent-cli/src/main/resources/application.yml`（CLI 专属：`spring.main.banner-mode` / `logging.level.root` 等，可与 `CliApplication.setDefaultProperties` 去重） | 新文件 |
| F2.4 | 对齐 `core.orchestrator-max-iterations`：**需拍板**取值（建议统一 5，写入公共 yml），并在 `CoreProperties` 默认值旁注明「默认值仅兜底，事实源在 `lucky-defaults.yml`」 | 1 行 + 注释 |
| F2.5 | **回归护栏**：新增 `ConfigParityTest` —— 用两个 `ApplicationContextRunner`（或 `SpringApplication` 探测）分别以 web/cli 的配置装配，断言关键键值相等 | 新测试 |

**验证**：CLI 下 `!echo` 不再报「沙箱未启用」（转为权限档判定，即 D4 文案）；`/doctor` 的 `maxTurns` 等行与 Web 一致。

> **可行性前置**：`spring.config.import: classpath:` 在多 jar classpath 下的解析需一次 PoC 确认（见 §四-风险 R1）。

---

### 批次 3 — 终端原生路径（D5 + D6 同源）

**共同根因（已确证的部分）**

| 事实 | 证据 |
|---|---|
| fat jar 内 **无 jna / jansi 库**，仅 `jline-3.30.17.jar` | `BOOT-INF/lib` 全量列举 |
| `jline-3.30.17.jar` 是 **aggregate**，内含全部 provider 实现（`jna/…`、`jansi/…`、`jni/…`、`ffm/…`、`exec/…`、`DumbTerminalProvider`） | 聚合 jar 类清单 |
| 代码**显式关闭** jna 与 jansi | [TerminalSession.java:67-74](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/term/TerminalSession.java#L67-L74) `.jna(false).jansi(false)` |
| 代码**强制**终端编码为 UTF-8 | 同上 `.encoding(StandardCharsets.UTF_8)`，注释曰「否则 JLine 可能按系统代码页（GBK/936）解释输入，中文输入即乱码」 |
| `stty` 不在 PATH（排出 exec provider 生效） | `Get-Command stty` 空 |
| `net.java.dev.jna:jna` 本地 m2 已有 5.13.0/5.14.0/5.17.0 | m2 目录列举 |

**推理（不依赖「落到哪个 provider」这一未知量）**

论点只用到两条**实测**事实：

1. 输入的中文**在进入内核之前**已被毁（屏幕回显正确、内核收到 `?`）——注意屏幕回显**可能**来自控制台自身的输入回显，因此**不能**据此断定损坏发生在 JLine 内部；本报告只主张「发生在进入内核之前」。
2. `0x03` 仍能触发 SIGINT（进程被杀）⇒ 终端**未进入 raw 模式**。

由 1 可推：若当前走的是 Windows 原生 Unicode 控制台路径（`ReadConsoleW` 之类），JVM 收到的将是**正确的 Unicode 字符**，中文不可能坏 ⇒ **当前生效的终端必然不是原生 Unicode 路径**。
由 2 可推：原生路径会设置 raw 模式以屏蔽 SIGINT ⇒ 与 1 的结论**相互印证**。

两条症状因此可由**同一个根因**（非原生回退路径）解释；并且天然解释了「非 TTY 路径（`-p`）不受影响」——那条路径完全不经过 JLine。

> **不闭环的部分（诚实标注）**：损坏究竟发生在「JLine 读取解码」还是「JLine 之后的某次字符串/字节转换」，本报告**未确证**。F3.1 的 A/B 实验正是为了区分这两种情形（判据见 F3.2）。

| 编号 | 改动 |
|---|---|
| F3.1 | **先做 A/B 实验（不改产品代码）**：临时以 `.jna(true)` + 在 `agent-cli/pom.xml` 加 `org.jline:jline-terminal-jna` 与 `net.java.dev.jna:jna`，在 **GBK 代码页**真终端下重测 ①中文输入 ②Ctrl+C |
| F3.2 | 依实验结论落地：<br>① **通过** → 正式启用 JNA provider（保留 `.jansi(false)`），并重新标定 `.encoding(...)`（JNA 路径走 Unicode API，encoding 影响写出编码，需重测）；<br>② **不通过** → 回退到「输入解码」专项排查（在 JLine 之前/之后逐层打印字节，定位到具体一次解码） |
| F3.3 | **兜底不可少**：启动时探测控制台输出 CP，若 ≠ 65001 则**显式告警**（不再静默损坏），并给出「用 `lucky-cli.bat` 启动 / `chcp 65001`」指引 |
| F3.4 | Ctrl+C 语义按实测结果收敛：修好则保留「两次退出 + 取消本轮」；若受技术约束无法保留，则**同步改文档**（`ReplLoop:223`、`SlashCommandRegistry:218`），确保文案与行为一致 |

**验证（自动化真终端脚本，ConPTY + pyte）**：

1. 中文输入后，断言落盘 `meta.json.title` 无 U+FFFD；
2. 空闲处按一次 Ctrl+C → 断言**按 F3.4 收敛后的语义**成立：<br>　若保留「两次退出」→ 进程**仍存活**且出现 `^C （再按一次…）`；<br>　若决定改为「一次即退出」→ 进程退出、且**文档已同步**（`ReplLoop:223` / `SlashCommandRegistry:218` 不再承诺两次）；
3. 执行中按一次 Ctrl+C → 断言**按 F3.4 收敛后的语义**成立（取消本轮则进程存活且出现取消提示；否则文档同步）。

> 断言与 F3.4 的语义选择**强耦合**：先定语义，再写断言，避免出现「测试与产品各说各话」。

---

### 批次 4 — 日志编码（D7）

**根因判定的关键**：程序自身输出（JLine）正确、logback 输出乱码，二者**唯一差异是写出路径**：JLine 走 Windows Unicode 控制台 API，logback 走字节流。`<charset>UTF-8</charset>` 写出的是 UTF-8 **字节**，被 CP936 控制台按 GBK 解释 → 得到 `妗嗘灦…`（正是「UTF-8 字节按 GBK 解码」的典型形态）。

| 编号 | 改动 | 说明 |
|---|---|---|
| F4.1 | **首选**：若批次 3 引入 JNA，则在 `CliApplication` 极早期调用 `Kernel32.SetConsoleOutputCP(65001)` / `SetConsoleCP(65001)`（仅当 stdout 为控制台且 CP≠65001），并注册 shutdown 钩子恢复原 CP | 与 `chcp 65001` 等效，但由进程自我保证，不再依赖启动脚本 |
| F4.2 | **备选**：自定义 logback `Appender`，把事件写入与程序输出**同一条** writer（即 `OutputSink` / JLine `terminal.writer()`） | 彻底绕开字节流与代码页；代价是 appender 需拿到终端实例 |
| F4.3 | 修正 [logback-spring.xml:11-12](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/resources/logback-spring.xml#L11-L12) 的失实注释 | 文档一致性 |

**验证**：GBK 环境下 `LOGGING_LEVEL_ROOT=INFO` 启动，用 pyte 屏幕断言日志行中文正确（如出现「框架必备目录扫描完成」）。

---

### 批次 5 — 参数与交互细节

| 编号 | 改动 | 落点 |
|---|---|---|
| F5.1 | `options.listSessions` 判定**前移**到 `pickWorkspace` 之前 | [CliRunner.java:136-150](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/CliRunner.java#L136-L150) |
| F5.2 | 歧义选择改为**循环重问**（带上限，如 3 次；回车才放弃） | [CliRunner.java:267-282](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/CliRunner.java#L267-L282) |
| F5.3 | EXEC 被 MODIFY 档拒绝时改为**可行动文案** | `LocalTransport:83` 需携带原因；`ShellPrefix:102` 侧组装提示（含「命令执行需工作空间权限档=全部权限，可用 `/workspace` 切换或 `-w` 指定」） |
| F5.4 | `/help` 中 `!<命令>` 一行补前提说明 | [SlashCommandRegistry.java:214](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/command/SlashCommandRegistry.java#L214) |
| F5.5 | `--model` / `--max-turns` / `--max-budget` 无效值**告警**（复用 `--output-format` 范式） | `CliConfigResolver` / `warnUnknownOutputFormat` 同区 |
| F5.6 | 列表标题超长加 `…` | `SessionRepository.format` |
| F5.7 | 定位并去重 `· 开始PLAN阶段` 重复进度 | `EventRenderer` / 进度上报点 |
| F5.8 | 测试包名统一（建议全部收敛到 `com.lucky.agent.cli.*`） | 14 个测试文件 |

---

### 批次 6 — 模型闭环（D15，单独立项）

拟排查路径（按优先级）：

1. `ConversationStateManager`（[:104-111](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/util/runtime/ConversationStateManager.java#L104-L111)）向 `AiMessage` 回填时是否保留了 `thinking()`；
2. `ReactEngine` 组装 `ChatRequest`（`:215`，以及 `:310` / `:325` 的 `AiMessage.from(...)` 重建点）是否丢失 `thinking()`；
3. 失败发生在 **PLAN** 阶段（非首轮），确认「前置调用产出的 assistant 消息」是否带思考链回传。

**验证**：新增 `ThinkingRoundTripTest`（mock 端点断言第二次请求体含 `reasoning_content`），并以真实端点复测 `-p "只回复两个字：收到"`。

---

## 四 可行性分析与方案合理性论证

### 4.1 总体判断

| 维度 | 结论 |
|---|---|
| 技术可行性 | **全部批次均可行**，无外部阻塞；唯一需新引入的依赖是 JNA（本地 m2 已具备，可离线构建） |
| 依赖变化 | 批次 1/2/4/5 **零新依赖**；批次 3 需 `jline-terminal-jna` + `jna`（约 1.5–2 MB） |
| 治本程度 | 批次 2/3 **治本**（消除缺陷类别）；批次 5 为体验修复；批次 1 为交付阻断 |
| 风险集中点 | 批次 3（依赖取舍 + 需 A/B 验证）；批次 2（`spring.config.import` 需 PoC） |
| 可测性 | 强。批次 1/2/5 可单测；批次 3/4 可用本次的「ConPTY + pyte」真终端脚本自动化断言 |

### 4.2 逐方案合理性

**D1（CRLF）— 合理性：高。** 纯机械修复，零行为风险。**但**必须同时加 `.gitattributes`，否则下次 checkout 在 `core.autocrlf` 非默认配置下会回到 LF，缺陷复现。→ 已纳入 F1.2。

**D2（JDK 探测）— 合理性：高，但需修正首版设计。** 首版设想「探测 `D:\jdk21`」不可移植（换机即失效）。修正为 **`LUCKY_JDK_HOME` → `JAVA_HOME` → 常见位置** 三级，并把逻辑抽到 `_jdk.bat` 供两个脚本共用，避免两处重复实现再次漂移。→ 已纳入 F1.3。

**D3（配置一致性）— 合理性：高（推荐 Option B），但需 PoC。**
- 被否方案 A：仅给 `agent-cli` 补一份 `application.yml` 复制粘贴业务键 → **能立刻修好，但正是本次缺陷的成因模式（同一事实源两处维护）**，长期必然再漂移。**仅作应急**。
- 被否方案 C：把默认值全部下沉到 `@ConfigurationProperties` 代码默认 → 消除漂移，但牺牲可调性（用户无法在 yml 覆盖），且与项目「配置默认值表」的既有约定冲突。
- **推荐 Option B**（抽 `lucky-defaults.yml` 单一事实源 + 两侧 import + `ConfigParityTest` 护栏）：与决策 D9「维护配置默认值表」、D26「唯一事实源」一致，且护栏能防复发。

**D4（MODIFY 拒 EXEC）— 合理性：中，且必须先拍板。** 本报告**不主张**改权限语义（那是安全决策）。仅修文案则 `!` 仍不可用（除非用户切 FULL）——**这可能不是用户期望的结果**。故列为「需拍板」项 ①。

**D5/D6（终端原生路径）— 合理性：高（前提是 A/B 通过），但这是本方案中最需要验证的一环。**
- 唯一可行的方向是**启用 Windows 原生控制台 provider**：Ctrl+C 的 raw 模式与 Unicode 输入都必须走原生 API。纯 Java 无解——JDK 21 下 `java.lang.foreign` 仍是**预览** API（JDK 22 才转正），而 JLine 的 `FfmTerminalProvider` 面向更高 JDK；`jni` provider 属遗留且需随包原生库；`exec` provider 依赖 `stty`（本机 PATH 中不存在）。
- 备选「强制/校验 CP=65001」**能缓解 D6/D7，但无法修 D5**（raw 模式仍需原生）。因此备选只能作为**降级**，不能替代。
- **附带发现（构建期约束）**：`agent-cli/pom.xml` 的 `bannedDependencies`（[:127-155](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/pom.xml#L127-L155)）只禁 `agent-workflow` / `spring-boot-starter-web` / `spring-boot-starter-tomcat`，**未禁 JNA 类**，故加依赖不会触发构建拦截。但该处注释同时写明「**若确实需要，请先修改决策文档，不要在模块内私放行**」——因此引入 JNA 属**需补决策记录**的变更（见 §4.3 ②）。
- **依赖成本已精确核实（勿按「加两个依赖」估算）**：
  1. `jline-3.30.17.pom` 中 `org.fusesource.jansi:jansi` 与 `net.java.dev.jna:jna` 均声明为 **`<optional>true</optional>`** —— 这正是它们**未被传递**、因而不在 fat jar 中的原因，也说明「启用 JNA」本就在上游的设计意图内；
  2. JLine 的 provider 实现类（`org/jline/terminal/impl/jna/JnaTerminalProvider.class`、`jna/win/JnaWinSysTerminal.class`）**已随 `jline-3.30.17.jar` 打包**（该类是 aggregate uber-jar，已实际列举确认）⇒ **无需再引入 `jline-terminal-jna`**；
  3. 对 `JnaWinSysTerminal.class` 常量池做字节级检索，其只引用 `com/sun/jna/LastErrorException`、`com/sun/jna/Pointer`、`com/sun/jna/ptr/IntByReference` —— 全部位于**核心 `jna` 包**，**不含 `com/sun/jna/platform`** ⇒ **无需 `jna-platform`**。
  ⇒ 结论：**只需新增 1 个依赖 `net.java.dev.jna:jna`（约 1.5 MB，含 `jnidispatch` 原生库）**，并去掉 `.jna(false)` 即可。
- 若 A/B 实验显示 JNA 开启后仍损坏，则说明根因不在 provider 选择，须回退到「逐层字节打印」定位（见 F3.2 ②）——**方案已内置分叉判据**，不会卡死。

**D7（日志编码）— 合理性：高。** F4.1（统一 CP）与 F3 同源、成本低；F4.2（自定义 appender）更彻底但耦合终端实例，作备选。

**D8/F5.1 — 合理性：高。** 已核实 `printSessions` 无 workspace 依赖，前移安全且语义更正确（会话不按工作空间隔离）。

**F5.2 — 合理性：高。** 但需定「重问上限」（建议 3 次后回落到报错退出，避免脚本/管道场景死循环——注意非 TTY 路径本就传 `editor=null`，不受影响）。

**F5.5 — 合理性：高。** 属补齐既有告警范式，无风险。

### 4.3 需要拍板的分歧点

| # | 分歧 | 选项 | 影响 |
|---|---|---|---|
| ① | **MODIFY 档是否应允许命令执行？** | **✅ 已定案（2026-10-04）：保持拒绝**（仅改文案，`!` 需工作空间权限档 = 全部权限） | 权限语义不动；D4 收敛为 F5.3 文案修复 + F5.4 `/help` 前提说明。<br>**残留预期管理**：CLI 的 `!` 对「修改文件」档工作空间（含内置默认工作空间）仍不可用，属**设计选择**而非缺陷 |
| ② | **是否引入 JNA 依赖？** | **✅ 已定案（2026-10-04）：引入** | 落地点：`agent-cli/pom.xml` 新增 `net.java.dev.jna:jna`（唯一新增依赖，≈1.5 MB）；`TerminalSession` 去掉 `.jna(false)`；**需同步补决策记录 D35**（依据 `agent-cli/pom.xml` 构建期约定）。收益：D5/D6 可修，D7 获得修复手段 |
| ③ | `core.orchestrator-max-iterations` 事实源取值 | **✅ 已定案（2026-10-04）：统一为 5** | 公共 yml 固定 `5`，`CoreProperties` 兜底默认值同步改 5；Web/CLI/三主循环口径一致（语义与差异见 §4.6.2） |
| ④ | 批次 2 采用 Option B（抽公共 yml）还是应急 Option A | **✅ 已定案（2026-10-04）：走 Option B** | 业务键收敛到 `agent-common` 单一事实源，结构上消除漂移（对比见 §4.7）。PoC 并入 F2.5 一次性完成 |

**定案后的执行范围**（对应「根据方案修改代码」）：

| 批次 | 状态 | 内容 |
|---|---|---|
| 批次 1 | **本轮执行** | F1.1–F1.5（脚本 CRLF / JDK 探测 / 失败不掩盖 / chcp 恢复） |
| 批次 2 | **本轮执行** | F2.1–F2.5（Option B + 统一 5 + import 生效测试） |
| 批次 3 | **本轮执行** | F3.1+F3.2（引入 JNA + 开启 provider + 真终端复测） |
| 批次 5 局部 | **本轮执行** | F5.1（D8 前移）、F5.3+F5.4（D4 文案与 `/help` 前提，对应定案 ①） |
| 批次 4 | **待批次 3 复测结果决定** | 若 JNA 未能一并解决 D7，则按 F4.1（SetConsoleOutputCP）补做 |
| 批次 5 余项 + 批次 6 | **不在本轮** | F5.2/F5.5–F5.8、D15 模型闭环（独立立项） |

### 4.4 建议执行顺序与依赖关系

```
批次1（脚本）──┐
              ├─→ 批次2（配置一致性）─→ 批次5（细节）
批次3 实验 ───┴─→ 批次4（日志编码，依赖 3 的 CP 方案）
                     └─→ 批次3 正式落地
批次6（模型闭环）独立立项，可与上述并行
```

**顺序理由**：批次 1 无依赖且解除交付阻断（必须先做，否则后面所有验证都要靠手工 `java -jar` 绕行）；批次 2 决定「CLI 到底拿到什么配置」，是批次 5 中 D4/F5.3 文案能否验证的前提；批次 3 的实验结论决定批次 4 取 F4.1 还是 F4.2，故 3→4 有硬依赖，不可并行。

### 4.5 最小可交付路径（若资源受限，按此收缩）

| 优先级 | 包含 | 交付后可达状态 |
|---|---|---|
| **必做** | 批次 1 + 批次 2（Option B） | 启动器可用、CLI 与 Web 配置一致、`!` 在 FULL 档可用（D1/D2/D3 关闭） |
| **应做** | + 批次 3 实验与落地 | 中文交互可用、Ctrl+C 语义自洽（D5/D6 关闭） |
| **可延后** | 批次 4/5、D10/D12/D13/D14 | 整洁度与体验 |
| **独立立项** | 批次 6（D15） | 影响 Web 与 CLI 双方，不宜塞进 CLI 修复批次 |

> 即：**批次 1+2+3 即可让 CLI 从「不可跑」变为「可正常跑」**；批次 4/5 属提升项。

### 4.6 附：两个关键概念说明（用于 ②③ 拍板）

#### 4.6.1 JNA 是什么，以及它为什么和 D5/D6 绑在一起

**JNA（Java Native Access）** 是一个让 Java **直接调用本机（C）函数**的库。它不需要写 JNI 胶水代码与 C 编译产物——你声明一个 Java 接口映射到某个动态库函数，JNA 用反射 + 自带的原生分发库（`jnidispatch`）在运行时完成调用。它与 JNI 的关系：JNI 是「手写 C 桥」，JNA 是「免写 C 桥」。

**为什么本缺陷绕不开它**：Windows 控制台有两套 API：

| 路径 | API | 编码行为 | 谁能用 |
|---|---|---|---|
| 字节路径 | `WriteFile` / `ReadFile`（即 `FileDescriptor.out/in`、`System.out`） | **经过控制台代码页转换**（CP936 时，UTF-8 字节会被按 GBK 解释 → 乱码） | 纯 Java 天然可用 |
| Unicode 路径 | `WriteConsoleW` / `ReadConsoleW` + `SetConsoleMode` | **直接传 UTF-16，不经过代码页** | **必须调用本机函数**（纯 Java 无 API） |

于是：

- **D7（日志乱码）** 出在字节路径（logback 走字节流）；
- **D6（中文输入被毁）与 D5（Ctrl+C 杀进程）** 出在「JLine 没能用上 Unicode 路径 / 没能设置 raw 模式」，而 raw 模式本身也只有 `SetConsoleMode` 这个本机函数才能设置。

⇒ **要让 JLine 走 Unicode 路径 + 拿到 raw 模式，就必须有一个能调本机函数的桥**，这正是 JNA 的角色。JLine 把这件事做成了标准 provider（`JnaTerminalProvider`），默认未启用 —— 而本项目**主动关掉了它**（[TerminalSession.java:69-70](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/term/TerminalSession.java#L69-L70)）且未引入 JNA 库。

**成本（已核实，非估算）**：`net.java.dev.jna:jna` 一个依赖、约 1.5 MB；在 jline 自己的 POM 里它本就是 `optional=true` 的官方可选件。**不是**「引入一个框架」，也**不需要** `jline-terminal-jna` 或 `jna-platform`（依据见 §4.2 D5/D6）。

**替代方案的边界**：若坚持不引入 JNA，则只能「保证控制台代码页 = 65001」，这能救 D6/D7，但 **D5 修不了**（raw 模式没别的路），只能改文档——即接受「Ctrl+C 一次即退出、没有『取消本轮』」。

#### 4.6.2 `core.orchestrator-max-iterations`：3 与 5 的差别

**它是什么**：编排主回环的**轮数上限**（安全阀 N）。语义在 [Orchestrator.java:140](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/Orchestrator.java#L140) 的循环与 [Orchestrator.java:476-498](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/Orchestrator.java#L476-L498) 的 `checkSafetyValve` 中定义：

- **1 轮 = 一次完整的「分析（PLAN）→ 调度子任务 / 直连执行（ACT）→ 客观验证 → 判定是否达成目标」**；
- 判定条件在**循环头**：`iter > maxIter` 即触发；
- 触发后行为：发布进度 `【安全阀】已达最大迭代次数（N），强制结束并总结当前进度。`，并返回 `summary = "任务执行已达最大迭代次数（N），未能完全达成目标。已完成的子任务与结果如下：\n" + 累计结果`，`reason = max_iterations`。

**所以 3 与 5 的差别是**：

| 项 | 3 | 5 |
|---|---|---|
| 实际可执行轮次 | 1、2、3 轮 | 1…5 轮 |
| 触发点 | 第 4 轮**开始前**被拦 | 第 6 轮**开始前**被拦 |
| 相对成本 | 低 | **多 2 轮的 PLAN + ACT + 验证调用**（token 与时延都增加） |
| 复杂任务完成度 | 更容易被「强制结束 + 未达成」截断 | 更可能跑完 |
| 与另两个安全阀的关系 | 循环头按 `max_iterations` → `max_turns` → `max_budget` **顺序判定，先命中者生效**（[:481-490](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/Orchestrator.java#L481-L490)）；`run-max-turns=30`、`act-max-steps=30` 通常远晚于它，故**多数真实任务上它才是实际生效的瓶颈** | 同左 |

**注意它不只影响 reactor**：同一值还喂给 thin 运行时的 `LoopController`（[AgentRuntimeConfiguration.java:249](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/runtime/AgentRuntimeConfiguration.java#L249)），且 `LangGraphOrchestrator`（[:149](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/LangGraphOrchestrator.java#L149)）同样读取 —— 与决策 D33「三主循环共用一套运行时参数」一致。**改这一个值会同时改变三种编排器的行为。**

**当前问题**：Web 用 5、CLI（无 yml）落到代码默认 3 ⇒ **同一个任务在 CLI 上可能比 Web 早 2 轮被强制结束**，用户会看到「未能完全达成目标」而 Web 却能跑完 —— 属 D3 的下游后果，且**用户可见**。

### 4.7 批次 2 两方案深入对比（④ 拍板依据）

**共同目标**：让 CLI 与 Web 拿到同一份业务配置，且**不再复发漂移**。

| 维度 | **Option A**：给 agent-cli 补一份 `application.yml`（复制业务键） | **Option B**：抽 `agent-common/lucky-defaults.yml` + 两侧 `spring.config.import` |
|---|---|---|
| 做法 | 把 `agent-web/application.yml` 的 `executor/core/model/memory/skill/mcp` 段**复制**到 agent-cli | 抽出公共段到 `agent-common`，两侧各写一行 `spring.config.import: classpath:lucky-defaults.yml`；各自 yml 只留通道专属键（web: `server.port`/`frontend-dist`；cli: `logging`） |
| 首次成本 | **最低**（纯复制，无需 PoC） | 中（需建 `agent-common/src/main/resources`——当前**不存在**，需新建；并做 1 次 import 生效 PoC） |
| 正确性 | 当下正确 | 当下正确 |
| **漂移风险** | **高——未消除**。本次缺陷的成因模式（同一事实源两处维护）**原样保留**；下次谁改了 Web 忘了 CLI，缺陷重现 | **低**。业务键只有一份，结构上无法漂移 |
| 护栏 | 需另加 `ConfigParityTest` 才勉强防住（但测试只能事后发现，不阻止分叉） | 建议仍加 `ConfigParityTest`，但它的角色从「唯一防线」降为「回归兜底」 |
| 爆炸半径 | 仅 agent-cli（1 个新文件） | agent-common + agent-web + agent-cli（3 处），但 web 侧是**纯搬移**，语义不变 |
| 回滚成本 | 删文件 | 撤 3 处改动（回滚面略大，但都是配置非代码） |
| 与既有决策一致性 | 与 D9「维护配置默认值表」、D26「唯一事实源」**不一致** | **一致** |
| 需要 PoC 的点 | 无 | `spring.config.import: classpath:` 能否从依赖 jar（`agent-common.jar` 位于 `BOOT-INF/lib/`）解析到文件；以及 import 的优先级（被导入方**低于**导入方 → 正是我们要的「公共默认 + 通道覆盖」） |
| 已知技术约束 | 无 | ① 必须命名为 `lucky-defaults.yml` 而非 `application.yml`，否则会被 Spring 自动发现、产生意外覆盖；② 被 `spring.config.import` 导入的文件**不能再次 import**（无链式），本方案不涉及；③ 全部后端当前**只有 1 个** Spring 配置文件（已全量检索：仅 `agent-web/src/main/resources/application.yml`，无任何 `application-*.yml`），搬移无遗漏风险 |

**结论与推荐**

- **推荐 Option B**。理由：批次 2 的目的**不是「修好今天」而是「让它不会再坏」**；Option A 在结构上保留了缺陷的成因模式，属于把同一个雷再埋一次。
- **但 Option B 有一个前置动作必须先做**：PoC 验证 `spring.config.import` 的解析与优先级。若 PoC 失败（例如 fat jar 内 `classpath:` 定位异常），**再退回 Option A**（此时是被动选择而非偏好），并**必须**同时上 `ConfigParityTest`。
- **可作为 Option A 的改进版（Option A′）**：不复制整段，而是让 agent-cli 的 yml 只写「与 Web 不同的键」，其余**依赖代码默认值**，并把「Web 与 CLI 的差异清单」写进文档。成本比 A 低，但**无法解决 `orchestrator-max-iterations` 这类「两边都该是同一个值」的键**，故只适合处理刻意差异项。
- **需注意的环境因素**：本机运行时观察到 `dev` profile 被激活，而仓库内**没有任何 profile 配置文件**（已全量检索）⇒ 推测来自外部环境变量。因此 `ConfigParityTest` 必须**隔离/固定环境变量**，否则两边配置对比会被环境污染。

---

## 五 二次审查记录

> 本节记录报告成稿后的**逐条复核**（结论、发现的问题与修正）。发现的问题已在正文中就地修正，此处留痕以便追溯。

详见「附录 C：二次审查发现与修正」。

---

## 附录 A：缺陷复现步骤（逐条可独立执行）

| 缺陷 | 复现步骤 | 期望（修复后） | 实测（当前） |
|---|---|---|---|
| D1 | `e:\JavaPro\lucky_agent\lucky-cli.bat --version` | `lucky-agent-cli 1.0.0` | `java 17.0.1 ...` + 多条 `'xxx' is not recognized` + rc=0 |
| D2 | 确保 PATH 上的 `java` 为 17，执行 `lucky-cli.bat -p hi` | 明确报「需要 JDK 21」 | `UnsupportedClassVersionError` |
| D3 | `java -jar agent-cli-1.0.0.jar -w SpringBlade` → 输入 `!echo ok` | 执行并回显 `ok` | `执行失败：命令执行沙箱未启用，需用户显式授权` |
| D4 | `java -jar agent-cli-1.0.0.jar -w test` → 输入 `!whoami` | 执行或给出可行动提示 | `执行失败：权限拒绝` |
| D5 | 同上进入 REPL，在 `? ` 提示符处按一次 `Ctrl+C` | 清空当前行并重新提示 | **进程直接退出** |
| D6 | 同上，输入 `请只回复OK三个字符` 回车 | 内核收到原文 | 屏幕回显正确，但内核收到 `??????OK???????`；`agent/sessions/*.meta.json` 该会话 `title` 含 U+FFFD |
| D7 | 设 `LOGGING_LEVEL_ROOT=INFO` 后直启，看启动日志 | 中文正确 | `框架必备目录扫描完成` → `妗嗘灦蹇呭鐩綍鎵弿瀹屾垚` |
| D8 | `java -jar agent-cli-1.0.0.jar --list-sessions`（不传 `-w`） | 列出全部会话 | 报工作空间歧义，rc=2 |
| D9 | 同上，在歧义提示处输入 `abc` | 重新询问 | 打印「请输入数字序号。」后退出 rc=2 |
| D11 | `java -jar agent-cli-1.0.0.jar -w test --model no-such-model --list-sessions` | 告警「模型不存在」 | rc=0，无告警 |
| D15 | `java -jar agent-cli-1.0.0.jar -w test -p "只回复两个字：收到"` | rc=0，输出「收到」 | rc=1，`reasoning_content ... must be passed back` |

> 真终端类缺陷（D5/D6/D7/D9）需在**真实控制台**中操作（管道重定向无法复现）；本次使用 `pywinpty`（ConPTY）+ `pyte`（VT 屏幕）实现自动化断言。

---

## 附录 B：审计产物与清理

| 项 | 数量 | 状态 |
|---|---|---|
| 测试脚本（`.py`） | 14 | **已删除**（含 `cli_probe.py` / `ui_probe.py` / `func_tests.py` / `ctrlc_test.py` / `decide_*.py` / `focus.py` 等） |
| 转录与结果（`.txt`） | 25 | **已删除** |
| 编译缓存（`.pyc`） | 1 | **已删除** |
| 承载目录 | `.cli-audit/`（仓库内临时目录） | **已整目录删除**，`Test-Path` 复核为 `False` |
| 本会话产生的 `.ps1` | 0 | — |
| 源码改动 | **0** | 本轮审计未修改任何源码 |

`git status` 中的 `CliRunner.java` / `LineEditor.java` / `WorkspaceResolver.java` / `WorkspaceResolverTest.java` 修改与 `src/test/java/com/` 新增，**均为本会话之前已存在的改动**，非本次审计产生。

> 副作用提示：审计过程中在真实框架根 `C:\Users\LENOVO\.lucky_agent\agent\sessions\` 留下了若干测试会话（其中 4 个 `title` 含 U+FFFD，可据此识别）。它们属运行数据、不在仓库内，未做清理；如需清理请另行确认。

---

## 附录 C：二次审查发现与修正

对成稿报告做了逐条复核，共产出 **8 项修正**（均已就地改入正文）：

| # | 复核对象 | 发现的问题 | 修正 |
|---|---|---|---|
| R1 | 批次 3 的因果推理 | 原文写「stdin 的中文会在 **JLine 层**被毁」——**属过度断言**。屏幕回显**可能**来自控制台自身的输入回显，因此「回显正确」**不能**推出「JLine 读到的是正确文本」 | 改为只主张「损坏发生在**进入内核之前**」，并显式标注「具体层级未确证」；同时把结论改造为不依赖该未知量的形式（「必然不是原生 Unicode 路径」由两条实测事实**独立**推出，并互相印证）。见 §三 批次 3 |
| R2 | §1.1 「headless 双通道契约 ✅」 | 该轮 rc=1，若只写「✅ 契约达成」易被误读为「headless 可用」 | 补注「rc=1 系模型调用失败（D15），非分流问题」 |
| R3 | 全篇 | **缺审计边界声明**——未测项若被当作「已通过」，会高估完成度 | 新增 §1.3「本轮未覆盖面」8 项，并声明完成度评估为**下界性质** |
| R4 | 批次 3 验证项 | 断言写死「进程仍存活且出现 `^C （再按一次…）`」，与 F3.4「可能改文档」的选项**冲突**（会出现测试与产品各说各话） | 改为「按 F3.4 收敛后的语义断言」，并给出两种情形的各自判据 |
| R5 | §4.2 / §4.3 ② | 漏掉一条**构建期约束**：`agent-cli/pom.xml` 的 `bannedDependencies` 注释明确要求「若确实需要，请先修改决策文档，不要在模块内私放行」 | 补入「引入 JNA 需同时补一条决策记录（AGENTS.md D-x）」；同时**已核实** JNA 不在禁用清单内，故不会被构建拦截 |
| R6 | §4.2 FFM 措辞 | 原文「JDK 21 上 FFM 不可用」不够精确 | 改为「JDK 21 下 `java.lang.foreign` 仍是**预览** API（JDK 22 转正），JLine 的 `FfmTerminalProvider` 面向更高 JDK」 |
| R7 | §二 结构 | 15 条缺陷无速查入口，读者需逐节翻阅 | 新增 §2.0「索引速查」表（编号/级别/一句话/证据类型/修复批次）+ 级别定义 |
| R8 | §四 收尾 | 「方案合理性」只给判断，未给**收缩路径** | 新增 §4.5「最小可交付路径」，给出「必做 / 应做 / 可延后 / 独立立项」四档，并明确批次 1+2+3 即可让 CLI 从「不可跑」到「可正常跑」 |

### C.1 复核通过、未发现问题的部分（留痕）

| 复核项 | 方法 | 结论 |
|---|---|---|
| D1 行尾根因 | 双脚本字节统计（CRLF=0 / LF-only=40 与 34） | 成立 |
| D3 配置分叉清单 | 逐键比对 `agent-web/application.yml` 与 `CoreProperties` 默认值 | 成立；并**新增**一处此前未列出的分叉（`orchestrator-max-iterations` 5 vs 3） |
| D7 根因 | **逐字节验证**：`框架` 的 UTF-8 字节按 GBK 双字节切分 = `妗嗘灦`，与观测值**精确相等** | 成立，且排除了「logback 按 GBK 写出」的替代假设 |
| D8 前移安全性 | 通读 `printSessions`（`CliRunner:342-353`），确认不依赖 workspace | 成立，前移安全 |
| D5 与 raw 模式的关联 | 由「`0x03` 触发 SIGINT 且进程被杀」反推终端未进 raw 模式 | 成立（属反证推理，已注明依据） |
| 假阳性剔除 | 对 `/help` 一度疑似截断、`/safety` 一度疑似文字残缺，用 pyte 逐帧复核 | **确认为抓取时序产物，已排除，未计入缺陷** |
| D6 归因方向 | 非 TTY 对照实验（`-p` 中文标题落盘正确）排除「产品整体编码损坏」 | 成立，缺陷局限于交互式输入路径 |

### C.2 遗留不确定性（明确列出，不做掩饰）

1. **D6/D5 的具体失效层级未知**——只知道「非原生 Unicode 路径」这一必要条件，具体是 JLine 读取解码还是其后某次转换，须由 F3.1 的 A/B 实验判定。
2. **D5 的单次 Ctrl+C 杀死进程，是否在「非 ConPTY 的真实 cmd 窗口」下同样发生**——本轮全部真终端测试均在 ConPTY 中完成，未在纯 `cmd.exe`/Windows Terminal 双击场景下复测。建议 F3.1 时一并覆盖。
3. **D7 在 `lucky-cli.bat`（`chcp 65001`）路径下的实际表现未测得**——该脚本本身已损坏（D1），无法执行到日志输出阶段。故 D7 的**影响范围**（是否只在直启时发生）待批次 1 完成后方能确认。
4. **D15 的失败触发条件未收敛**——已知「同提示词 2/2 失败、换 `hi` 成功」，但未确定是「第二轮调用必然失败」还是「仅在特定思考内容下失败」；批次 6 首步应先做触发条件收敛实验。

---

## 附录 D：修复落地与实机复测（2026-10-04 第二轮）

### D.0 方法修正（重要）：测试环境必须清掉 `TERM`

第一轮审计的 D5/D6 证据**被环境污染**：审计 shell 自带 `TERM=dumb`，而 JLine 在 `TERM=dumb` 时会选择
`DumbTerminal`（type=`dumb-color`），于是：

- 无原生输入 → 输入按字节 + UTF-8 解码，CP936 控制台下中文被毁；
- 无 raw 模式 → `Ctrl+C` 触发 SIGINT，进程被杀。

这两条都是**哑终端降级行为**，而非产品在正常控制台下的缺陷。用最小探针在
**真实控制台窗口**与 **ConPTY** 两种环境各测一次，结果完全一致：

| 环境变量 | JLine 选中的实现 | type |
|---|---|---|
| `TERM=dumb` | `org.jline.terminal.impl.DumbTerminal` | `dumb-color` |
| `TERM` 未设置 | `org.jline.terminal.impl.jni.win.NativeWinSysTerminal` | `windows-vtp` |

→ **后续所有真机复测均在剔除 `TERM` 的环境下进行**（复测脚本已固化该处理）。

### D.1 结论修正（3 处）

| # | 原结论 | 修正后 | 依据 |
|---|---|---|---|
| 修-1 | 必须引入 `net.java.dev.jna:jna` 才能走原生控制台（D35） | **不需要**。jline 聚合 jar 已内置 **JNI provider** 与 Windows 原生库，`jna(true)` 与 `jna(false)` 得到**完全相同**的原生终端 | 探针实测（真实控制台 + ConPTY 双环境）。`jna` 依赖已**回滚**，`bannedDependencies` 无需豁免；D35 已改写为「不引入 JNA + 记录本次误判」 |
| 修-2 | D5/D6 是「非原生回退路径」造成的产品缺陷 | **归因修正**：两者是 `TERM=dumb` 下的**哑终端降级行为**；产品的真实缺陷是 `isDumb()` **只精确匹配 `dumb`**，把 `dumb-color` 误判为可交互终端 —— 于是 CLI 打印「终端：交互式（ANSI）」、进入 REPL，却把用户输入弄坏、并在 Ctrl+C 时自杀。**修正后：哑终端一律拒绝进入 REPL**（让「不能交互」显式化） | [TerminalSession.java:96-102](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/term/TerminalSession.java#L96-L102) + 探针实测 |
| 修-3 | D7 乱码的处置方案是「统一控制台代码页（需 JNA）」或「日志改走 JLine writer」 | **更精确**：乱码 = 「UTF-8 字节被按接收端代码页解释」。`chcp 65001` 即可解决，而 `lucky-cli.bat` 在本轮已修好（批次 1）⇒ **文档化启动路径下 D7 已消失**；直接 `java -jar` 在 936 代码页下仍乱码，属**已知限制**并已写进 `logback-spring.xml` 注释（原注释的失实断言一并更正） | 对照实测：CP936 下 49 行日志 **18 行乱码**；`chcp 65001` 下 **0 行乱码** |

### D.2 逐缺陷复测结论

| 缺陷 | 状态 | 实测证据 |
|---|---|---|
| D1 `.bat` 行尾 | ✅ **已修复** | `lucky-cli.bat --version` → `lucky-agent-cli 1.0.0`，无 `'xxx' is not recognized`，rc=0（修复前：JDK 版本横幅 + 多条解析错误） |
| D2 JDK 定位 | ✅ **已修复** | 输出 `[JDK] D:\jdk21`；把 `LUCKY_JDK_HOME` 指向 JDK 17 时**被版本闸门拒绝**并回退到 D:\jdk21 |
| D3 配置漂移 | ✅ **已修复** | `!echo` 的错误从「命令执行沙箱未启用」变为真实执行错误 ⇒ 沙箱已在 CLI 生效；`CliConfigImportTest` 断言 `sandbox-enabled=true`、`orchestrator-max-iterations=5` |
| D4 EXEC 拒绝文案 | ✅ **已修复** | 实测输出：`执行失败：权限拒绝：当前工作空间权限档为「修改文件」，命令执行需要「全部权限」。请把该工作空间的权限级别改为「全部权限」，或指定一个已设为「全部权限」的工作空间。` |
| D5 Ctrl+C | ⚠️ **部分修复** | **空闲提示符处已正常**：按一次后进程**存活**并打印 `^C （再按一次 Ctrl+C 退出，或输入 /exit）`（设计行为恢复）。**执行中按 Ctrl+C 仍直接杀进程**（两次独立复现）—— 见 D.3，属**残留缺陷** |
| D6 中文输入 | ✅ **已修复** | 会话落盘标题 = `'请只回复OK三个字符'`（**无 U+FFFD**） |
| D7 日志乱码 | ✅ 文档化路径已修复 | CP936 直启 18/49 乱码 → `chcp 65001` 后 **0/49 乱码** |
| D8 `--list-sessions` | ✅ **已修复** | 不带 `-w`：rc=0，首行 `历史会话（共 37 个，1 为最近）` |
| D9 / D10 / D11 / D12 / D13 / D14 | ⏸ 未在本轮范围 | 见 §4.3「定案后的执行范围」 |
| D15 模型闭环 | ⏸ 独立立项 | 未动 |

单元测试：`mvn -pl agent-cli,agent-web -am test` → **15/15 模块 BUILD SUCCESS**，agent-cli **108/108**（新增 2）。

### D.3 残留缺陷（本轮发现，未修，需决策）

**D16（P1，新发现）执行臂命令沙箱不走 shell ⇒ 绝大多数命令无法执行**

D3 修复后（沙箱真正生效），`!echo` 暴露出下一层问题：

```
? !echo cli-exec-ok
$ echo cli-exec-ok
执行失败：命令启动失败：Cannot run program "echo" (in directory "E:\JavaPro\Saas\SpringBlade"):
CreateProcess error=2, 系统找不到指定的文件。
```

**根因**：`CmdSandbox` 直接 `new ProcessBuilder(命令)` 并把命令行按空白切分，**不经过 `cmd /c`**。
而 `echo` / `dir` / `type` / `del` / `copy` / `cd` **都是 cmd 内建命令**，不是可执行文件；
管道 `|`、重定向 `>`、`&&` 等 shell 语义同样不成立。
⇒ 即使工作空间是「全部权限」，用户最可能敲的那些命令**依然跑不起来**。
（对照：工作流模块的 `LocalSandboxAdapter` 是走 `cmd /c` 的，只有执行臂这条路径不走。）

**D17（P1，残留）执行中 Ctrl+C 仍杀进程**

- 空闲处已正常（raw 模式生效，JLine 接管）。
- 但一轮执行期间主线程阻塞在提交上、JLine 的 `readLine` 已返回并**恢复了 cooked 模式**，
  此时 `Ctrl+C` 触发 OS 级 SIGINT，`CliTurnExecutor` 的 `CancelWatcher`（轮询 `System.in`）来不及接管 ⇒ 进程退出。
- 与文档承诺（`ReplLoop:223`、`SlashCommandRegistry:218`「运行中按 Ctrl+C 可优雅取消本轮」）仍不一致。
- 可选修法（**需拍板**）：① 用 `sun.misc.Signal` 注册 INT 处理器转为优雅取消（引入内部 API）；② 整轮持有 raw 模式并改从 JLine 的输入流取取消字节（改动终端生命周期）；③ 若都不做，则**同步改文档**为「运行中按 Ctrl+C 会退出，取消本轮请用 Ctrl+C 之外的机制」。
- 两次独立复现，证据见复测脚本输出。

### D.4 本轮代码改动清单

| 文件 | 改动 |
|---|---|
[`_jdk.bat`](file:///e:/JavaPro/lucky_agent/_jdk.bat) | **新增**：JDK 21+ 定位（`LUCKY_JDK_HOME` > `JAVA_HOME` > 常见位置 > PATH），带主版本闸门与可行动报错 |
| [`lucky-cli.bat`](file:///e:/JavaPro/lucky_agent/lucky-cli.bat) | CRLF 化；接 JDK 定位；记原代码页并在退出时恢复；构建/启动失败不再被 `exit /b 0` 掩盖；用 `%JAVA_EXE%` 启动 |
| [`lucky.bat`](file:///e:/JavaPro/lucky_agent/lucky.bat) | 同上（并补 `chcp` 与失败传播） |
| [`.gitattributes`](file:///e:/JavaPro/lucky_agent/.gitattributes) | **新增**：`*.bat/cmd → CRLF`、`*.sh → LF`（防止缺陷随 checkout 复发） |
| [`lucky-defaults.yml`](file:///e:/JavaPro/lucky_agent/web/app/agent-common/src/main/resources/lucky-defaults.yml) | **新增**：跨通道共用业务配置的**唯一事实源**（model/executor/core/skill/mcp/memory） |
| [`application.yml`（agent-web）](file:///e:/JavaPro/lucky_agent/web/app/agent-web/src/main/resources/application.yml) | 只留通道专属键 + `spring.config.import`；`core.orchestrator-max-iterations` 随共享文件统一为 5 |
| [`application.yml`（agent-cli）](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/resources/application.yml) | **新增**：导入共享配置 + CLI 专属项 |
| [`CoreProperties.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/config/CoreProperties.java#L59-L65) | 兜底默认值 3 → 5，与共享配置一致 |
| [`TerminalSession.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/term/TerminalSession.java#L81-L102) | `isDumb` 改为按 `dumb` 前缀判定（真实缺陷修复点） |
| [`CliRunner.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/CliRunner.java#L135-L155) | `--list-sessions` 前移到工作空间解析之前 |
| [`PermissionEvaluator.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-permission/src/main/java/com/lucky/agent/permission/support/rules/PermissionEvaluator.java#L185-L201) | 新增 `deniedByLevel`（判定收敛，供执行臂归因） |
| [`LocalTransport.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-executor/src/main/java/com/lucky/agent/executor/support/arm/LocalTransport.java#L129-L151) | 拒绝原因从「权限拒绝」改为**可行动文案**（并与规则基线拒绝区分） |
| [`SlashCommandRegistry.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/command/SlashCommandRegistry.java#L214-L217) | `/help` 为 `!<命令>` 补「需全部权限档」前提 |
| [`logback-spring.xml`](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/resources/logback-spring.xml#L11-L18) | 更正失实的「不会乱码」断言，写明真实前提与已知限制 |
| [`CliConfigImportTest.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/test/java/com/lucky/agent/cli/config/CliConfigImportTest.java) | **新增**：导入生效 + CLI 侧不得重复声明共享键（防漂移护栏） |
| [`AGENTS.md`](file:///e:/JavaPro/lucky_agent/AGENTS.md) | **D35 改写**为「不引入 JNA + 误判留痕 + 复测注意事项」 |
| `agent-cli/pom.xml` | 曾加 JNA，**已回滚**；留注释说明为何不加 |

> 复测脚本（`TerminalProbe.java` / `verify_*.py` 等）为一次性工具，位于 `.cli-verify/`，**已于本轮结束删除**。

---

## 附录 E：第三轮——「全部修复」落地与复测（2026-10-04 晚）

### E.1 本轮修复项

| 编号 | 缺陷 | 修法 | 落点 |
|---|---|---|---|
| **D16** | `CmdSandbox` 不走 shell ⇒ `echo`/`cd`/`dir` 等 cmd 内建命令与 `\|`/`>`/`&&` 全部不可用 | 统一改为 Windows `cmd.exe /c`、POSIX `/bin/sh -c`，整条命令原样交给 shell | [`CmdSandbox.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-executor/src/main/java/com/lucky/agent/executor/support/arm/CmdSandbox.java) |
| **D18a** | 同方法内：先 `readAllBytes()` 再 `waitFor(timeout)` ⇒ 命令不结束时读操作永久阻塞，**超时形同虚设**（且管道写满后双向死锁） | 改为「虚拟线程并发排空输出 + 带超时等待」，超时后强杀并回收 | 同上 |
| **D18b** | 同方法内：`new String(bytes)` 用平台默认字符集 ⇒ `-Dfile.encoding=UTF-8` 启动时把 GBK 命令输出解成乱码 | 先严格试 UTF-8（能识别「不是 UTF-8」），失败退 `stdout.encoding`（JVM 探测到的控制台代码页），最后退默认 | 同上 |
| **D17** | 执行中 Ctrl+C 直接杀进程，与「运行中按 Ctrl+C 可优雅取消本轮」的承诺相反 | 本轮内用 `sun.misc.Signal` **临时接管 SIGINT** 并翻译为优雅取消（与 `CancelWatcher` 同一动作），本轮结束立即恢复原处理器；连续第二次 Ctrl+C 以惯例码 130 强退，避免内核不响应取消时被困住。接管失败（如 `-Xrs`）静默退回原行为 | [`CliTurnExecutor.InterruptTrap`](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/CliTurnExecutor.java#L219-L294) |
| **F5.2**（D9） | 工作空间歧义输错一次即退出，与「回车放弃」提示语义不符 | 改为**循环重问（上限 3 次）**；同时接受「名称 / ID 前缀」直接指定；回车仍是放弃 | [`CliRunner#pickWorkspace`](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/CliRunner.java#L271-L300) |
| **F5.5**（D11） | `--max-turns abc` / `--max-budget` 非法值静默忽略，与 `--output-format` 的告警口径不一致 | 入口处统一校验并走 `errSink` 告警（告警但继续，回退默认值）；**刻意不校验 `--model`**（模型 id 是否存在只有模型模块知道，命令层不装配运行链路组件，见 javadoc） | [`CliConfigResolver#invalidRunExtra`](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/config/CliConfigResolver.java#L211-L255)、[`CliRunner#warnInvalidRunExtra`](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/CliRunner.java#L403-L417) |
| **F5.7**（D13） | `· 开始PLAN阶段` 连续出现两次，像消息重复 | **复核后确认不是重复**：第二次是**真实的重规划**（首次计划非法时 `replanner.replan` 会再跑一次引擎）。改为在重规划前显式发一条 `【重规划】首次计划不可用（原因），正在重新规划一次…`，让两次调用可区分 | [`Orchestrator#analyze`](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/Orchestrator.java#L518-L526) |

### E.2 复测结果（真机，且已剔除 `TERM`）

| 项 | 结果 | 证据 |
|---|---|---|
| D16 `!echo` | ✅ | `$ echo cli-exec-ok` → **`cli-exec-ok`**（修复前：`CreateProcess error=2`） |
| D16 `!cd` | ✅ | `$ cd` → **`E:\JavaPro\Saas\SpringBlade`**（cmd 内建命令，修复前不可执行） |
| D16 管道 | ✅ | `$ echo a\|findstr a` → **`a`** |
| D17 空闲 Ctrl+C | ✅ | 进程存活 + `^C （再按一次 Ctrl+C 退出，或输入 /exit）` |
| D17 执行中 Ctrl+C | ✅ | **进程存活**；屏幕出现 `🏁 cancelled`、状态栏、`本轮失败：已取消`，随后回到提示符 `›`（修复前：进程直接消失） |
| 单元测试 | ✅ | `mvn -pl agent-cli,agent-web -am test` → **15/15 模块 BUILD SUCCESS**；agent-core 98、agent-cli 108、agent-web 10、agent-workflow 38、agent-model 38 |

### E.3 复核中推翻的两项原结论（不修，属原报告误报）

| 原缺陷 | 复核结论 |
|---|---|
| **D12**「列表标题截断无省略号」 | **误报**。`CliSessionService#oneLineTitle` 早已在截断时补 `…`（[:124-133](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/session/CliSessionService.java#L124-L133)）。我此前看到的那条「无省略号」标题，是**会话自身落盘标题**（由 Web 侧生成）就长那样，不是列表渲染截断所致。无需改动 |
| **D13**「进度消息重复」 | **误报（语义层面）**。两次 `开始PLAN阶段` 对应两次**真实**的引擎 PLAN 调用（第二次是重规划）。已改为显式标注重规划，见 E.1 |

### E.4 D15（思考模式第二轮被拒）—— 本轮**未能复现，故未修**

- **首轮**（2026-10-04 上午）：`-p "只回复两个字：收到"` **2/2 复现** `The reasoning_content in the thinking mode must be passed back to the API`，rc=1。
- **本轮**：同一命令 **2 次尝试、共 16 次模型调用全部成功**（`请求成功`），`reasoning_content` 报错 **0 次**。
- 期间观察到端点池变化：首轮只见 `deepseek-v4-flash`，本轮出现 `deepseek-v4-flash` 与 `qwen3.8-max-0902` 两个端点轮转 ⇒ 该缺陷**间歇性、与具体端点/思考模式/请求是否携带 tools 的组合相关**。

**结论：不盲修。** 无法复现的缺陷改核心代码，等于把「已验证的稳定态」换成「未验证的猜测态」。已定位的**机制候选点**（供专项排查直接用）：

1. `ConversationManager#rollbackToNode`（[:271-279](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/ConversationManager.java#L271-L279)）用 `AiMessage.from(content)` 重建历史，**丢掉 thinking**；而同文件的 `ConversationStateManager#restoreFromPersistence`（[:102-112](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/util/runtime/ConversationStateManager.java#L102-L112)）**刻意保留**了 thinking，并在注释里写明「带工具重放时缺 thinking 会被模型侧拒（HTTP 400 must be passed back）」。→ **同一语义的两条重建路径不一致**，`rollbackToNode` 这条是明确的缺口（受影响的入口：Web 的撤销、CLI 的 `/undo`）。
2. `ReactEngine` 落库路径（[:321-326](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/util/engine/ReactEngine.java#L321-L326)）**已正确保留** thinking（`lastAi.toBuilder()`）。
3. 命令层 `ReactiveCompactStrategy:56` 会插入**合成** assistant 消息（摘要），天然无 thinking —— 若压缩后请求携带 tools，需评估其影响。

**建议**：单独立项，首步做「触发条件收敛实验」（固定单一端点 + 强制 tools + 观察 `messages=N tools=N` 序列），再按上表第 1 点补齐重建路径。**不要**在没有可复现用例的情况下改这条链路。

### E.5 环境说明（影响可复现性，需知悉）

本轮后半段，本机 TRAE 沙箱开始拦截对 `C:\Users\LENOVO\.lucky_agent` 的写入（`TRAE Sandbox Error: hit restricted`，涉及 `model-usage.json` / `agent/sessions/*` / `memory/**/mem.jsonl`），导致 headless 运行以 `JSONL 写入失败` 收尾。因此：

- D16/D17 的真机复测**在沙箱拦截出现之前完成**，结论有效（证据见 E.2）。
- D15 的两次「未复现」运行**发生在沙箱拦截期间**，其失败原因是 `JSONL 写入失败`，与 `reasoning_content` 无关；但由于模型调用日志本身完整（16 次全部 `请求成功`），「未复现该 API 报错」这一结论仍然成立。
- 若后续要复测 D15，需在**沙箱外**启动，并确保 `.lucky_agent` 可写。

### E.6 本轮新观察到的次要问题（未修，留档）

| 编号 | 现象 | 说明 |
|---|---|---|
| D19（P3） | 用户主动取消后提示为 `本轮失败：已取消` | 措辞把「用户取消」归入「失败」。语义上取消是用户预期内的正常结束，建议改为 `已取消本轮`。不影响功能，未改以免扩大改动面 |
| D20（P3） | `lucky-cli.bat --version` 会先打印一行 `[JDK] D:\jdk21` | 这是 JDK 定位的排障回显，对普通使用属噪声；如需静默可用环境变量 `LUCKY_QUIET_JDK=1` 抑制（当前未实现） |

---

## 附录 F：第四轮——启动器入库 / 回归测试 / D15 复现收敛（2026-10-04 晚）

### F.1 启动器入库与跨平台补齐（对应「③」）

| 项 | 内容 |
|---|---|
| `.gitignore` | **移除** `/lucky`、`/lucky.bat`、`/lucky.sh` 三条忽略规则 —— 它们是「本地一键启动」这一交付形态的入口，不入库等于用户拿不到；且其缺陷（CRLF 行尾、不校验 JDK）正是因为不入库、无人 review 才存活至今。保留「若要忽略必须前导 `/` 锚定」的警告（裸写 `lucky` 会匹配 `com/lucky/agent`，曾导致整棵源码树对 git 不可见） |
| **新增** [`_jdk.sh`](file:///e:/JavaPro/lucky_agent/_jdk.sh) | macOS/Linux 版 JDK 21+ 定位：`$LUCKY_JDK_HOME` > `$JAVA_HOME` > 常见安装位置（apt / Homebrew / Temurin）> PATH（含 `readlink -f` 解析软链），带主版本闸门 |
| [`lucky-cli.sh`](file:///e:/JavaPro/lucky_agent/lucky-cli.sh) / [`lucky.sh`](file:///e:/JavaPro/lucky_agent/lucky.sh) | 接入 JDK 定位与可行动报错；改用 `$JAVA_EXE` 启动。**补齐了 D2 在 macOS/Linux 上的同类缺陷**（原先两者都是裸 `java`） |
| [`lucky`](file:///e:/JavaPro/lucky_agent/lucky) | 原先是 `lucky.sh` 的**逐字节副本** → 改为**转发壳**。两份相同内容必然漂移，改一个忘一个会产生「`./lucky` 与 `./lucky.sh` 行为不一致」这种极难排查的问题 |

**实测（Git Bash，`bash -n` 语法检查 + 行为验证）**：

| 用例 | 结果 |
|---|---|
| 4 个 `.sh` 语法检查 | 全部 OK |
| `LUCKY_JDK_HOME=/d/jdk21`（JDK 21） | ✅ 解析成功，`$JAVA_EXE -version` → `openjdk version "21.0.11"` |
| `LUCKY_JDK_HOME` 指向 **JDK 17** | ✅ **被版本闸门正确拒绝**（与 Windows 侧一致） |
| `JAVA_HOME=/d/jdk21` | ✅ 解析成功 |
| 无任何环境变量（仅 PATH 兜底） | ✅ 不误接受，明确报 cannot find |
| `lucky` 转发壳 | ✅ 可执行、内容为转发实现 |

### F.2 回归测试补齐（对应「②」）

| 新增测试 | 覆盖 | 结果 |
|---|---|---|
| [`CmdSandboxTest`](file:///e:/JavaPro/lucky_agent/web/app/agent-executor/src/test/java/com/lucky/agent/executor/support/arm/CmdSandboxTest.java)（7 例） | **D16** shell 内建命令（`echo`）与**管道**可执行；**D18a** 超时真的生效（长睡眠命令 1s 超时须在 15s 内返回）；**D21** 超时后整棵进程树被终止（工作目录可删除 ⇒ 无孤儿孙进程）；非零退出码保留；未启用/缺参数拒绝 | ✅ 7/7 |
| [`InterruptTrapTest`](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/test/java/com/lucky/agent/cli/InterruptTrapTest.java)（3 例） | **D17** 安装后 `Signal.raise(INT)` 触发优雅取消回调且进程不退出；**关闭后还原先前处理器**（否则会吞掉用户后续 Ctrl+C）；`disabled()` 无副作用 | ✅ 3/3 |

> **为此把 `InterruptTrap` 从 `CliTurnExecutor` 的嵌套类提取为 package-private 顶层类** —— 嵌套类无法直接测；提取后可用 `Signal.raise` 在进程内合成 SIGINT，把「接管生效 / 关闭还原」钉死，不必启动整个 REPL。
>
> **刻意不测的路径**：连续第二次 Ctrl+C 会 `Runtime.halt(130)`，在测试进程里触发等于杀掉 surefire 本身，故该路径只能由真机人工验证（见附录 E.2）。
>
> **已知副作用（编译告警）**：`sun.misc.Signal` / `SignalHandler` 属内部专用 API，编译期会产生 `sun.misc.* 是内部专用 API` 警告（非错误）。这是使用该 API 的固有代价，已在代码注释中说明取舍理由。

**全量结果**：15/15 模块 BUILD SUCCESS；agent-executor **3 → 10**、agent-cli **108 → 111**、agent-core 98、agent-web 10。

### F.3 顺带修复：D21 超时后留下孤儿孙进程

写 `CmdSandboxTest` 时，`@TempDir` 的清理失败暴露了一个真实缺陷：JUnit 报
`Failed to delete temp directory ... 另一个程序正在使用此文件，进程无法访问`。

**根因**：`CmdSandbox` 通过 shell 间接启动命令（`cmd /c` / `sh -c`），超时时只 `destroyForcibly()` 了**直接子进程（shell）**，真正干活的**孙进程**（如 `ping`）变成孤儿继续运行并占住工作目录。

**修法**：新增 `killTree` —— 先取后代快照，再逐个强杀（根进程被杀后 `descendants()` 可能已为空）。已由 `timeoutKillsWholeProcessTree` 用例锁定。

### F.4 D15 复现收敛（关键进展）

**上一轮结论是「未能复现，故未修」。本轮通过固定端点把触发条件收敛出来了：**

```
# 复现配方（稳定 3/3）
java -Dfile.encoding=UTF-8 -jar agent-cli-1.0.0.jar \
     -w test --model deepseek-v4-flash -p "只回复两个字：收到"
```

| 条件 | 结果 |
|---|---|
| **不带 `--model`**（端点池 `BALANCED` 轮转 deepseek / qwen） | 16 次调用全部成功，报错 **0** 次 |
| **`--model deepseek-v4-flash` 固定** | **3/3 复现**：rc=1，各 5 次模型调用，`reasoning_content` · 报错各 **13 行** |

⇒ **该缺陷与「思考模式」强相关**，只在推理模型（`deepseek-v4-flash`）上出现；端点池里掺入 `qwen3.8-max-0902` 时被稀释，这才是上一轮「没复现」的真正原因。

**失败点**：`phase=PLAN` 的**首次**调用即失败（`流式调用失败，降级同步` → 同步也失败，两次同一 400）⇒ 请求体本身有问题，与传输无关。

**已定位的可疑点（写读不对称）**：

| 位置 | 事实 |
|---|---|
| [`SessionRepository.java:68-108`](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/repository/SessionRepository.java#L68-L108) | 提供**带 thinking** 的 6 参重载；但 4 参重载（`:106`）会把 thinking 传 `null` |
| [`ConversationManager.java:668-669`](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/ConversationManager.java#L668-L669) | **主落库路径正确**：`appendMessage(..., lastAssistantThinking(state))`，注释明确说明「带工具重放缺 thinking 即 400」 |
| [`ConversationManager.java:563`](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/ConversationManager.java#L563)（取消通知）、[:610](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/ConversationManager.java#L610)（意图解析的确认问句） | 以 **assistant 角色**落库，但**不传 thinking**（走 4 参重载） |
| [`ConversationStateManager.java:102-112`](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/util/runtime/ConversationStateManager.java#L102-L112) | 回放时**按角色+thinking 重建**：有 thinking 走 `AiMessage.builder()...thinking(...)`，无 thinking 走 `AiMessage.from(content)` ⇒ 上表那些「无 thinking 的 assistant 记录」回放后就是**缺 reasoning_content 的 assistant 消息** |

**下一步（明确到可执行）**：在 PLAN 之前打印 `state.messages()` 的角色序列与各 assistant 消息的 `thinking` 是否为空（或在 `ReactEngine` 组装 `ChatRequest` 处加一条 DEBUG），确认是哪一条 assistant 消息进入了 PLAN 请求；据此决定是「落库补 thinking」还是「这类通知类文本不该以 assistant 角色入库」。**修复可用上面的复现配方在 2 分钟内验证。**

> 本轮**未改**这条链路：它位于 `agent-core` 的会话持久化，Web 通道同样受影响；在未确证具体注入点之前改动消息持久化，风险大于收益。现已具备稳定复现，改动可立即验证。

### F.5 第四轮改动清单

| 文件 | 改动 |
|---|---|
[`.gitignore`](file:///e:/JavaPro/lucky_agent/.gitignore) | 启动器改为入库（移除 3 条忽略规则 + 改写注释保留锚定警告） |
| [`_jdk.sh`](file:///e:/JavaPro/lucky_agent/_jdk.sh) | **新增**：macOS/Linux JDK 21+ 定位 |
| [`lucky-cli.sh`](file:///e:/JavaPro/lucky_agent/lucky-cli.sh) / [`lucky.sh`](file:///e:/JavaPro/lucky_agent/lucky.sh) / [`lucky`](file:///e:/JavaPro/lucky_agent/lucky) | 接 JDK 定位；`lucky` 改转发壳 |
| [`CmdSandbox.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-executor/src/main/java/com/lucky/agent/executor/support/arm/CmdSandbox.java) | 新增 `killTree`（D21：超时终止整棵进程树） |
| [`InterruptTrap.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/InterruptTrap.java) | **新增**（自 `CliTurnExecutor` 提取，为可测性） |
| [`CliTurnExecutor.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/java/com/lucky/agent/cli/CliTurnExecutor.java) | 移除嵌套类，改用提取后的 `InterruptTrap` |
| [`CmdSandboxTest.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-executor/src/test/java/com/lucky/agent/executor/support/arm/CmdSandboxTest.java) / [`InterruptTrapTest.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/test/java/com/lucky/agent/cli/InterruptTrapTest.java) | **新增**：D16/D18a/D21 与 D17 的回归锁定 |

---

## 附录 G：第五轮——D15 / D14 / D10 收口（2026-10-05）

> 本轮目标：把报告里**剩下的三个缺陷**一次做完（D15 修复、D14 收口、D10 修复），然后全量测试 + 真机复测。
> 所有结论均可溯源：源码位置、命令、实测输出或 jstack 证据。**未验证的一律标「未验证」。**

### G.0 方法与两个必须知道的环境事实

| 项 | 说明 |
|---|---|
| 构建/测试 | JDK 21（`D:\jdk21`）；`mvn -pl agent-cli,agent-web -am clean package`（15/15 模块） |
| 真机复测 | 与附录 E 同法：**必须剔除 `TERM`**（本机 shell 自带 `TERM=dumb`） |
| **沙箱限制（影响本轮全部 rc）** | 本会话的执行沙箱**禁止写 `C:\Users\LENOVO\.lucky_agent`**（框架根）。因此 CLI 真机跑会出现 `JSONL 写入失败` → rc=1。这**不是产品缺陷**，是被测环境产物；G.3 的"干净基线对照"用 `-Duser.home` 指向可写目录规避了该干扰 |
| **增量构建陷阱（本轮踩坑留痕）** | `mvn -pl agent-cli -am package` 在「只有上游模块（agent-core/model）变化」时，`maven-jar-plugin` 会**跳过 agent-cli 重打包**，fat jar 内嵌的 `agent-core-1.0.0.jar` 实测停留在 9/28 → 我加的诊断日志"打了却没出现"，白跑一轮。**结论：改动任何上游模块后必须 `clean package`**（本轮后续已全部改为 clean） |

### G.1 D15 修复：真机复现 → 定位 → 修复 → 复现 3/3 转 0/3

**① 复现**（配方同 F.4）：`-w test --model deepseek-v4-flash -p "只回复两个字：收到"` —— 复现成功，报错 `The reasoning_content in the thinking mode must be passed back to the API.`

**② 定位（决定性证据，推翻 F.4 的怀疑方向）**：在 `ReactEngine` 组装请求处加临时诊断（定位后已删除），实测输出：

```
[D15诊断] phase=PLAN 构建请求 state消息数=1 组装消息数=2 构成=system user
[D15诊断] phase=PLAN 构建请求 state消息数=2 组装消息数=3 构成=system user assistant[think=0,tools=0,textLen=2]
→ 流式调用失败，降级同步：... reasoning_content ... ；同步 messages=3 tools=15 同一 400
```

⇒ **真凶不是** F.4 怀疑的「落库丢 thinking」路径（`:563`/`:610`），而是：

1. 引擎首轮 PLAN 调用**成功**，但 deepseek 对极短回答（"收到"，2 字）返回了**空 `reasoning_content`**（实测 `thinkingLength=0`）；
2. 该 assistant 消息（`think=0`）按正常流程进入会话历史（[ReactEngine.java:324-326](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/util/engine/ReactEngine.java#L324-L326) 保留 thinking，可惜它本来就是空）；
3. 计划不可解析 → **重规划**：请求 = `[system, user, assistant(无思考)]` + **tools=15** → 厂商拒绝；且连锁污染后续 ACT 轮（同一历史反复被拒）。

同时更正 F.4 的"日志行被截断到 119 字符"疑点：那是前一轮用抽象终端（120 列屏）/并发 stderr 交错造成的**读取假象**——本轮用文件重定向抓原始字节，日志行完整无截断。

**③ 修复**：新增 [`ReasoningPassbackSupport`](file:///e:/JavaPro/lucky_agent/web/app/agent-model/src/main/java/com/lucky/agent/model/support/endpoint/ReasoningPassbackSupport.java)（agent-model 端点适配层——协议的所有者），在**发送前对请求副本**做归一化（不动会话内存态与落盘）：

| 情形 | 处理 |
|---|---|
| 无思考的**纯文本** assistant | **剔除**（不编造思考；无工具配对，剔除不影响请求合法性） |
| 无思考但**带 tool_calls** 的 assistant | **补最小占位思考**（保住 tool_call/tool_result 配对） |
| 非思考模式 / 请求不带 tools | **不改动**（实测该契约只对携带 tools 的请求生效） |

思考模式判定（这是本修复的关键，也是它必须放在适配器的原因）：端点**请求了推理档位**（`InferenceDepth≠OFF`）**或历史响应已实测产出思考链**（流式经 `onPartialThinking`、同步按 thinking 长度）；两个开关取或。同步与流式**双路径**都已接入（[OpenAiCompatibleModel.java](file:///e:/JavaPro/lucky_agent/web/app/agent-model/src/main/java/com/lucky/agent/model/support/endpoint/OpenAiCompatibleModel.java)）。

> **为什么不复用 agent-core 的 `ThinkingConsistency`**：它把"请求里已有带思考的 assistant"当作思考模式判据——本缺陷恰恰是**唯一一条 assistant 没有思考**，于是被判为"非思考模式"直接放行（漏网）。同时该 400 契约是 OpenAI 兼容协议专属，放适配器不会波及 Anthropic 路径。

**④ 验证**：

| 项 | 结果 |
|---|---|
| 单元测试（wire 级，本地桩服务断言请求体） | [`ThinkingPassbackTest`](file:///e:/JavaPro/lucky_agent/web/app/agent-model/src/test/java/com/lucky/agent/model/support/endpoint/ThinkingPassbackTest.java) 扩至 **5 例 5 通过**（新增：剔除 / 补占位 / 非思考模式零影响） |
| 真机复测（修复后，同配方） | **2 连跑：`reasoning_content` 报错 0**（修复前 3/3 复现、每次 13 行），归一化日志出现 1-2 次，编排全流程跑通、stdout 输出"收到" |
| 残余 rc=1 | 仅沙箱拦写框架根（见 G.0），与模型链路无关 |

### G.2 D14 收口：测试包名三套并存 → 全部统一

| 项 | 内容 |
|---|---|
| 改动 | 13 个测试文件自 `src/test/java/cli/**` 迁至 `src/test/java/com/lucky/agent/cli/**`，包名 `cli.*` → `com.lucky.agent.cli.*`（`LineEditorHistoryTest` 包名此前已是正确值，未动） |
| 资源引用核查 | 全测试树唯一一处 `getResourceAsStream("application.yml")` 是 **classpath 绝对路径**，不受包移动影响；无 `import cli.` 的跨包引用 |
| 验证 | `clean` 后 agent-cli 测试数 **111**（与迁移前一致）；顺带发现并纠正一个计数假象：未 clean 时新旧两套 class 并存会让测试数虚高到 214 |

### G.3 D10 修复：退出期堆栈污染（已消除）+ 退出耗时抖动（根因实证并封顶）

**① 堆栈污染（修复 + 量化）**：

| 位置 | 改动 |
|---|---|
| [`ConnectionManager.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-mcp/src/main/java/com/lucky/agent/mcp/support/connect/ConnectionManager.java) `connect` 失败 | `log.warn("MCP 连接失败：{}（{}）", id, e.getMessage())` —— **只记一行摘要**，完整堆栈降级 DEBUG |
| [`McpHealthProbe.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-mcp/src/main/java/com/lucky/agent/mcp/support/connect/McpHealthProbe.java) 探活异常 | 同上（MCP 服务器本机未安装/未启动时每轮都失败，堆栈刷终端无信息量） |

实测对比（同命令一次运行的 stderr）：修复前 **8 个异常头 + 84 个 `at com.lucky` 帧** → 修复后 **MCP 相关堆栈 0**（残余帧全部来自沙箱拦写的失败栈，G.0）。

**② 退出耗时抖动——jstack 实证根因**：

```
"main" ... TIMED_WAITING (parking)
  at java.util.concurrent.CountDownLatch.await
  at org.springframework.context.support.DefaultLifecycleProcessor$LifecycleGroup.stop
  → 等待结束时刻 == 在飞 MCP 探活结束时刻（initialize 最长 mcp.request-timeout-ms=10s）
```

三连测（`--list-sessions`，同环境）从进程启动到退出总时长：**18.2s / 11.1s / 31.3s** —— 差异全部对应"退出时是否有在飞探活"及其剩余时长。即：**退出被在飞的 MCP 健康探活拖住**（探活启动即首次执行，短命令极易撞上）。

**③ 修法**（均为通道专属，写在 [`agent-cli/application.yml`](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/resources/application.yml)）：

1. `spring.task.scheduling.shutdown.await-termination: false` —— 关闭时**不再等待**调度任务（中断在飞探活；探活失败本就是可降级事件）。日志实证：修复后出现 `MCP 连接失败：playwright（MCP 请求被中断：playwright#initialize）`。
2. `spring.lifecycle.timeout-per-shutdown-phase: 2s` —— 给所有生命周期收尾一个**硬上限**（默认 30s）。日志实证：`Shutdown phase 1073741823 ends with 1 bean still running after timeout of 2000ms: [taskScheduler]`，随后正常退出。
3. [`StdioTransport.close()`](file:///e:/JavaPro/lucky_agent/web/app/agent-mcp/src/main/java/com/lucky/agent/mcp/support/transport/StdioTransport.java) 的子进程宽限从 `requestTimeoutMs`（10s）收紧到 **1.5s** 后强杀——关闭是收尾动作，不该等请求级超时。
4. [`ConnectionManager.closeAll()`](file:///e:/JavaPro/lucky_agent/web/app/agent-mcp/src/main/java/com/lucky/agent/mcp/support/connect/ConnectionManager.java)（`@PreDestroy`）——退出时确定性关闭全部会话，回收子进程与传输（原先退出靠进程终止，子进程/reader 线程被遗弃）。

**④ 干净基线对照**（`-Duser.home` 指向可写目录以规避沙箱拦写；同代码同命令）：总时长稳定 **6.07 / 6.09 / 6.11 秒**（启动 ~4.7s + 退出 ~1.4s），无 Shutdown 超时告警。

**⑤ 诚实边界**：原报告实测的是 REPL `/exit`（ConPTY）；本轮的量化在 headless（`--list-sessions` / `-p`）完成，走的是**同一条 `ctx.close()` 关闭路径**；REPL 额外的 `terminal.close()` 非阻塞点。沙箱拦写带来的额外秒级开销无法归因到产品，故"退出耗时"的最终数字以**干净基线对照**为准。

### G.4 回归与验收（本轮全量）

| 项 | 结果 |
|---|---|
| 全量构建+测试 | `mvn -pl agent-cli,agent-web -am clean package` → **15/15 模块 BUILD SUCCESS** |
| 测试数（干净构建后） | agent-common 11、agent-cache 3、agent-permission 9、agent-executor 10、agent-memory 11、**agent-model 41（+3）**、agent-core 98、agent-skill 13、agent-mcp 18、agent-workflow 38、agent-web 10、**agent-cli 111** —— 合计 **373，Failures/Errors = 0** |
| D15 真机 | 复现配方 2 连跑：`reasoning_content` 报错 **0**（修复前 3/3） |
| D10 真机 | MCP 相关堆栈 **0**；退出有界（中断在飞探活 + 2s 硬上限）；干净基线 6.1s |
| D14 | 包名全统一；agent-cli 测试数 111 不变 |

### G.5 本轮改动清单

| 文件 | 改动 |
|---|---|
| [`ReasoningPassbackSupport.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-model/src/main/java/com/lucky/agent/model/support/endpoint/ReasoningPassbackSupport.java) | **新增**（D15）：思考模式 + 带 tools 请求的 `reasoning_content` 回传保证（剔除无思考纯文本 assistant / 补占位） |
| [`OpenAiCompatibleModel.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-model/src/main/java/com/lucky/agent/model/support/endpoint/OpenAiCompatibleModel.java) | 接入上述保证（同步 + 流式）；新增思考模式判定（推理档位或已实测思考） |
| [`ThinkingPassbackTest.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-model/src/test/java/com/lucky/agent/model/support/endpoint/ThinkingPassbackTest.java) | 新增 3 个 D15 回归用例 |
| `agent-cli/src/test/java/cli/** → com/lucky/agent/cli/**`（13 个文件） | **迁移+改包名**（D14） |
| [`agent-cli/application.yml`](file:///e:/JavaPro/lucky_agent/web/app/agent-cli/src/main/resources/application.yml) | 新增退出收口两项配置（D10，附 jstack 证据注释） |
| [`ConnectionManager.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-mcp/src/main/java/com/lucky/agent/mcp/support/connect/ConnectionManager.java) | 连接失败改一行摘要（D10）；新增 `@PreDestroy closeAll()` 退出收口 |
| [`McpHealthProbe.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-mcp/src/main/java/com/lucky/agent/mcp/support/connect/McpHealthProbe.java) | 探活异常改一行摘要（D10） |
| [`StdioTransport.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-mcp/src/main/java/com/lucky/agent/mcp/support/transport/StdioTransport.java) / [`McpTransportFactory.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-mcp/src/main/java/com/lucky/agent/mcp/support/connect/McpTransportFactory.java) | 关闭宽限 10s→1.5s（D10）；同步移除已无用的 `McpProperties` 构造参数 |
| [`ReactEngine.java`](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/util/engine/ReactEngine.java) | 仅用于定位的临时诊断日志已**删除**（净改动为零） |
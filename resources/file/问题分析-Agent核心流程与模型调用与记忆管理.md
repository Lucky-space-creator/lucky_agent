# 问题分析：Agent 核心流程 / 模型调用 / 记忆管理

> 范围：`web/app` 后端（agent-core / agent-model / agent-memory）。
> 所有问题均标注代码位置（文件:行号）与复现路径，可逐条核对。
> 严重级别：P0 = 功能失效/数据错误；P1 = 行为与设计契约不符或明显副作用；P2 = 边角缺陷/隐患。

---

## 一、Agent 核心流程（编排链路）

### P0-1 意图解析轮的指令从未发给模型，`parseIntent` 实际失效

**位置**
- [ConversationManager.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/ConversationManager.java#L260-L271)（L260-L271：先 appendMessage 再 parseIntent）
- [ConversationManager.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/ConversationManager.java#L371-L405)（L371-L405：parseIntent 本体，解析指令放在 ctx.goal）
- [ReactEngine.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/engine/ReactEngine.java#L148-L151)（L148-L151：只有 history 为空才把 goal 加成 UserMessage）

**现状**：`execute()` 先把用户消息追加进会话状态（L261），再调 `parseIntent`。`parseIntent` 把「请解析用户最新输入…输出 JSON」的指令放进 `ctx.goal` 调 `engine.run`。但 ReactEngine 组装消息时用的是 `state.messages()` 作为历史，**只有当 history 为空时才把 goal 补成用户消息**。此处 history 必然非空（刚追加了用户消息），所以解析指令被静默丢弃。

**后果/复现**：任意会话发送 `1`，模型实际收到的是「完整历史 + ASK 阶段指令（仅就高风险点向用户确认）」，而不是解析指令 → 几乎不会输出约定 JSON → `parseIntent` 走 catch 返回 null → 回退直接执行。**「引用历史且涉及文件变动先确认」的门实际上从不触发**，与预期行为不符。可在日志中观察 `意图解析 JSON 解析失败，回退直接执行` 或 `意图解析无返回` 复现。

### P0-2 意图解析轮的正文增量与 stop 事件直接推给了用户（SSE 污染）

**位置**
- [ConversationManager.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/ConversationManager.java#L458-L471)（buildCtx 未设置 `suppressStop`）
- [ReactEngine.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/engine/ReactEngine.java#L259-L261)（非 suppress 时引擎发布 stop）
- [ReactEngine.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/engine/ReactEngine.java#L564)（流式 contentDelta 实时发布）

**现状**：`parseIntent` 走的是完整 `ReactEngine.execute`：流式正文增量、`thought`、`token` 事件全部推 SSE，且因为 ctx 未带 `suppressStop=true`，引擎在解析轮结束时**直接发布 stop 事件**。

**后果/复现**：用户每发一条消息，前端会先收到一段「回答」（实为解析轮输出，内容是模型在 ASK 指令下对用户消息的回应）和一个 stop 收尾，随后编排主流程又推一轮内容。前端表现为一条消息提前收尾、正文错乱或出现两段回答。这正是项目记忆里「编排器内部引擎轮必须 suppressStop」约束被新代码绕过的回归。

### P0-3 判定轮（LlmJudgeVerifier）把巨型合成用户消息永久留在会话上下文，且 JSON 结论流进用户正文

**位置**
- [LlmJudgeVerifier.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/verify/LlmJudgeVerifier.java#L107-L110)（L107-L108：judgeGoal append 进 state 后从不移除）

**现状**：每轮达成度判定都把含「原始目标 + 全部执行日志 + 客观证据」的 judgeGoal 作为 **UserMessage** 追加到会话状态，之后没有任何移除逻辑。该消息会一直留在内存态历史里，参与后续所有轮次的模型调用。

**后果/复现**：复杂任务跑 2~3 轮编排后，会话历史里堆了多条巨型「回顾如下原始目标…」合成用户消息：① 上下文加速膨胀、加速触发压缩；② 用户事后看到的持久化记录里没有这些消息，但模型实际能看到，**模型所见 ≠ 用户所见**；③ 判定轮以 `Phase.ACT` + 完整工具集运行，提示词还写着「若未完全完成…继续执行补齐」，judge 期间可能再次调用工具改文件，「验证」不是纯判定。

另外：judge 输出（含结尾的 `{"done":...}` JSON）随 contentDelta 流式推到用户可见正文，而最终落库的是 `stripJson` 后的文本 → **流式展示内容与刷新后持久化内容不一致**。

### P1-1 意图解析轮打穿并发守卫（running 标志被内层引擎复位）

**位置**
- [ConversationManager.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/ConversationManager.java#L237-L241)（外层置 running=true）
- [ReactEngine.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/engine/ReactEngine.java#L268-L270)（内层 finally 置 running=false）

**现状**：`ConversationManager.execute` 先 `state.running(true)` 作为并发闸；但 `parseIntent` 内层 `engine.run` 的 finally 把 `running` 置回 false，此后编排主流程（`orchestrator.run`，可能跑几分钟）全程 running=false。

**后果/复现**：编排执行期间用户再发一条消息，`state.running()` 检查通过 → 同一会话两个编排回环并发跑，共享 `state.messages()` 交叉写。复现：发一条耗时任务后立即再发一条，观察日志出现两个交错编排循环。

### P1-2 进程重启后会话上下文整体丢失（内存态无回灌）

**位置**
- [ConversationStateManager.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/runtime/ConversationStateManager.java#L30-L40)（sessions 纯内存 Map，无 restore 入口）
- [ReactEngine.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/engine/ReactEngine.java#L148-L151)（history 只取 state.messages()，空则只有当前 goal）
- [SessionRepository.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/repository/SessionRepository.java#L85-L102)（loadMessages 仅供前端 history 展示，不回灌引擎状态）

**现状**：消息持久化在 `<frameworkRoot>/.agent/sessions/*.jsonl`，但引擎上下文只读内存态 `SessionState`。重启后 `computeIfAbsent` 建出空状态。

**后果/复现**：重启后端 → 打开旧会话（前端能看到完整历史）→ 问「我刚才让你做什么」→ 模型上下文里只有系统提示 + 当前这句，回答「不知道」。**用户看到的历史与模型看到的历史不一致**。

### P1-3 ReactEngine 的上下文压缩结果不回写会话状态

**位置**
- [ReactEngine.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/engine/ReactEngine.java#L155)（maybeCompact 只替换局部变量）
- [ReactEngine.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/engine/ReactEngine.java#L276-L293)（compact 产物未写回 state）
- 对照 [LoopMemoryManager.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/LoopMemoryManager.java#L113)（这里有 `state.replaceMessages(compacted)`）

**现状**：`maybeCompact` 压缩的是本地 `messages` 副本；引擎后续把新 AI 消息 append 到**未压缩**的 `state.messages()`。本轮模型看到的是压缩版，状态里存的是未压缩版。

**后果/复现**：长会话触发一次压缩后，下一轮重新从 state 读全量历史 → 又超限 → 每轮都重复压缩（成本），且状态与模型所见上下文永久分叉。

### P2-1 子代理工具调用参数未展平，与 ToolGateway 契约不一致

**位置**
- [SubAgentExecutor.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/subagent/SubAgentExecutor.java#L101-L110)（parseArgs 无 unwrap）
- 对照 [ReactEngine.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/engine/ReactEngine.java#L462-L483)（unwrapArgs）
- 契约来源 [ToolGateway.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/gateway/ToolGateway.java#L184-L194)（参数统一包在 `args` 对象下，工具实现从顶层取字段）

**现状**：工具 schema 声明参数为单一 `args` 对象，ReactEngine 分发前会 `unwrapArgs` 展平；SubAgentExecutor 的 `parseArgs` 直接 `readValue` 后原样分发。模型按 schema 传 `{"args":{"path":"a.txt","content":"..."}}` 时，工具从顶层取 `path` 得到 null。

**后果/复现**：开启 `core.subagent-enabled=true`，让 PLAN 产出 subagents，子代理一旦调用 `file.write`/`file.read` 等必报参数缺失类错误。**若子代理已开启，这是「频繁文件操作异常」的头号嫌疑**。

### P2-2 子代理上下文缺权限级别、超时配置未接线、步数耗尽返回「成功空摘要」

**位置**
- [SubAgentExecutor.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/subagent/SubAgentExecutor.java#L64-L67)（ConversationCtx 未设 permissionLevel）
- [SubAgentExecutor.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/subagent/SubAgentExecutor.java#L74-L94)（maxTurns 耗尽 finalText=null → `SubAgentResult(success=true, summary="")`）
- [CoreProperties.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/config/CoreProperties.java#L31)（`subagentTaskTimeoutSec` 定义了但 execute 里无超时控制）

**后果**：① 子代理工具调用的权限裁决收到 null level，行为取决于下游对 null 的默认处理（不确定因素）；② 子代理跑满步数没产出时，主链路把它当「成功 + 空摘要」聚合进上下文；③ 配置的子代理超时不生效。

### P2-3 循环守卫（stuck）既可被措辞绕过也可能误杀

**位置**：[Orchestrator.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/Orchestrator.java#L339-L353)

**现状**：判定 = 连续两轮「未达成结论」整段文本归一化后完全相等。模型每次表述略有差异即绕过（死循环仍要靠 maxIter=3 兜底，多烧 2 轮 token）；反过来，同一目标需要多轮推进且每轮总结措辞相近时会被误判 stuck 提前结束（此前 OrchestratorTest 回归已踩过一次）。

### P2-4 token 统计虚高

**位置**：[ReactEngine.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/engine/ReactEngine.java#L188-L195)

**现状**：每一步 `inputTokens = tokenMeter.count(messages)`（全量历史），`tokenUsed` 逐步累加 → 会话级 token ≈ Σ(每轮全量历史)，随轮次平方级虚高。监控面板与 RunBudget 的 `maxBudget` 都用这个值 → **token 预算安全阀会远早于真实用量触发**。

### P2-5 LangGraphOrchestrator 死代码 / 双实现风险

**位置**：[LangGraphOrchestrator.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/engine/graph/LangGraphOrchestrator.java)、[Orchestrator.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/Orchestrator.java#L69-L71)（`@ConditionalOnProperty orchestrator-mode=reactor, matchIfMissing=true`）

**现状**：默认 reactor；若有人配置 `core.orchestrator-mode=langgraph` 则切到另一套实现，该实现未跟进最近的修复（意图解析、stuck 守卫等都不在其内）。AGENTS.md 待确认项 P0-2 仍悬而未决。

---

## 二、模型调用

### P1-4 API Key 明文落盘，违反 D8

**位置**
- [ModelConfigStore.java](file:///e:/JavaPro/lucky_agent/web/app/agent-model/src/main/java/com/lucky/agent/model/config/ModelConfigStore.java#L16-L19)（类注释明确「明文存储（零托管、不加密）」）
- [ModelConfigStore.java](file:///e:/JavaPro/lucky_agent/web/app/agent-model/src/main/java/com/lucky/agent/model/config/ModelConfigStore.java#L61-L71)（save 明文写 settings.json）

**现状**：D8 决策为「第一阶段即加密：PBKDF2 或 OS 密钥链派生密钥，AES-GCM 加密落盘；API Key 禁止明文写盘」，实现与决策直接冲突（注释里把「不加密」写成了设计）。**复现**：打开 `C:\Users\LENOVO\.lucky_agent\settings.json` 可见明文 apiKey。

### P1-5 记忆专用端点（role=memory）永远不生效

**位置**
- [LlmMemorySummaryModel.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/models/LlmMemorySummaryModel.java#L37-L38)（resolve(memoryModelId)）
- [ModelRouterImpl.java](file:///e:/JavaPro/lucky_agent/web/app/agent-model/src/main/java/com/lucky/agent/model/service/impl/ModelRouterImpl.java#L90-L99)（configOf 过滤掉 role=memory 后回退主端点）

**现状**：路由层为了「防止记忆端点被误选为对话模型」，在 `configOf` 里把 memory 角色过滤掉并回退主端点；但记忆总结走的也是同一个 `resolve(modelId)` → **记忆端点配置永远被过滤，实际始终用主端点**。日志 `记忆管理 Agent=true`（L49-L50 只判断 modelId 非空）会误导排查。

**复现**：配置一个 role=memory 的廉价模型端点，发一条消息触发记忆总结，观察日志中记忆总结的 model 名仍是主端点模型。

### P1-6 流式超时回退：原请求未取消 + 回退文本可能完全不展示

**位置**
- [ReactEngine.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/engine/ReactEngine.java#L597-L606)（180s 超时 → syncTurn 重调）
- [ReactEngine.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/engine/ReactEngine.java#L640-L645)（startsWith 判断）

**现状**：① 超时后原流式 HTTP 请求没有取消机制，同步回退又完整调一次 → 同一轮模型被调两次（双倍成本）；② 回退时若重新生成的文本不是已展示内容的前缀（流式已出了部分内容后超时，重试结果大概率不同），`alreadyShown` 非空且 startsWith 不成立 → **同步结果一个字都不展示**，但最终落库用的是同步版文本 → 用户界面看到的与刷新后的持久化内容不一致。

### P2-6 内部轮次全部计入监控指标且走用户可见事件流

**位置**：[ReactEngine.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/engine/ReactEngine.java#L187-L195)

意图解析轮、PLAN 轮、judge 轮、子任务重试轮每次都 `reportModelCall` 并发布 `token` 事件 → 监控面板的「模型调用次数 / token」包含大量内部轮，且一次用户消息会触发多次 token 事件（maybeCompact 处 L285 还会先发一次）。指标语义需在文档或前端注明，否则误判用量。

---

## 三、记忆管理

### P0-4 每轮对话结束固定串行 3 次 LLM 记忆调用，阻塞 SSE 收尾

**位置**
- [ConversationManager.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/ConversationManager.java#L331-L342)（finally 中同步执行，位于 `publisher.complete` 之前）
- [MarkdownMemoryWriter.java](file:///e:/JavaPro/lucky_agent/web/app/agent-memory/src/main/java/com/lucky/agent/memory/support/md/MarkdownMemoryWriter.java#L73-L93)（会话总结→项目合并→整体合并，3 次 summarize 串行）

**现状**：每发一条消息（包括「你好」），收尾阶段固定执行 3 次记忆 LLM 调用：会话总结（输入 = 全量 transcript）、项目合并（输入 = 旧项目记忆 + 增量）、整体合并（输入 = 旧整体记忆 + 项目记忆全文）。同步阻塞在 SSE complete 之前。

**后果/复现**：① 每条消息额外 3 次模型调用的成本与延迟；② 用户看到回答结束后 SSE 迟迟不关闭（前端「完成」状态延迟数秒~数十秒）；③ transcript 无截断（[ConversationManager.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/ConversationManager.java#L346-L356)），长会话收尾时记忆调用本身可能超上下文。复现：开日志发一条消息，观察三次「记忆总结完成」。

### P1-7 双套记忆体系并行写入、召回互斥、数据不一致

**位置**
- JSONL 轨写入：[ConversationManager.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/ConversationManager.java#L264)（用户原话 0.8）、[L315](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/ConversationManager.java#L315)（助手结论 0.7）、[LoopMemoryManager.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/LoopMemoryManager.java#L77-L83)（轮日志 0.6）
- md 轨写入：[MarkdownMemoryWriter.java](file:///e:/JavaPro/lucky_agent/web/app/agent-memory/src/main/java/com/lucky/agent/memory/support/md/MarkdownMemoryWriter.java#L73-L93)
- 召回互斥：[ReactEngine.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/support/engine/ReactEngine.java#L331-L343)（md 非空则完全跳过 JSONL 召回）

**现状**：每条消息同时写两套记忆（全局 JSONL + 分层 md），但召回时 md 轨只要非空就**完全不看** JSONL 轨。JSONL 轨持续膨胀却基本不被读取；一旦 md 开关关闭或文件为空，又只剩 JSONL 轨。两套数据内容、分组维度、置信度规则都不一致。

### P1-8 JSONL 记忆按用户全局混存，不按项目分组（跨项目串扰）

**位置**
- [UserMemoryStore.java](file:///e:/JavaPro/lucky_agent/web/app/agent-memory/src/main/java/com/lucky/agent/memory/repository/UserMemoryStore.java#L96-L98)（`memory/<userId>/mem.jsonl` 单文件，无 workspace 维度）
- [MemoryRetrieverImpl.java](file:///e:/JavaPro/lucky_agent/web/app/agent-memory/src/main/java/com/lucky/agent/memory/service/impl/MemoryRetrieverImpl.java#L37-L53)（recall 全局 topK=5，无项目过滤）

**后果/复现**：在项目 A 的会话里说过「我们用 pnpm」，切到项目 B 的会话时该偏好仍可能经 JSONL 轨召回注入系统提示（当 md 轨为空时）。与 AGENTS.md 待做项「记忆存储按项目分组」一致：**md 轨已按 workspaceId 分组，JSONL 轨没有**。

### P1-9 记忆无界增长：去重/衰减/清理（做梦机制）未接线

**位置**
- [UserMemoryStore.java](file:///e:/JavaPro/lucky_agent/web/app/agent-memory/src/main/java/com/lucky/agent/memory/repository/UserMemoryStore.java#L51-L67)（append 只过噪声过滤，无去重、无容量上限）
- [MemoryConfig.java](file:///e:/JavaPro/lucky_agent/web/app/agent-memory/src/main/java/com/lucky/agent/memory/config/MemoryConfig.java#L30-L42)（Cleaner / DecayManager 有 Bean 定义，但全工程无定时任务或调用方触发）
- [MemoryRetrieverImpl.java](file:///e:/JavaPro/lucky_agent/web/app/agent-memory/src/main/java/com/lucky/agent/memory/service/impl/MemoryRetrieverImpl.java#L55-L83)（召回排序只看相似度+置信度，无时间因子，与「矛盾点时间近优先」不符；md 轨合并提示词里有时间近优先，JSONL 轨没有）

**后果**：`mem.jsonl` 与 `memory/md/**` 只增不减；同一事实每次会话重复写入；旧矛盾信息不会被新信息覆盖（JSONL 轨）。长期使用后召回质量下降、文件膨胀。

### P2-7 用户消息与助手结论原文进记忆文件

**位置**：[ConversationManager.java](file:///e:/JavaPro/lucky_agent/web/app/agent-core/src/main/java/com/lucky/agent/core/service/ConversationManager.java#L264-L315)

用户每条输入、助手每轮总结都原文写入 `memory/<userId>/mem.jsonl`（明文）。符合「零托管本机存储」定位，但意味着任何能读该目录的进程都能拿到完整对话史；且这些原文又会经召回注入任意后续会话的系统提示（见 P1-8）。属设计知情项，建议至少在文档与设置中明示。

---

## 附：已确认无恙的点（避免重复排查）

| 点 | 位置 | 结论 |
|---|---|---|
| JSONL 坏行容错 | [JsonlUtil.java](file:///e:/JavaPro/lucky_agent/web/app/agent-common/src/main/java/com/lucky/agent/common/util/JsonlUtil.java) L63-L85 | `readAll` 逐行 try/catch 跳过坏行并告警，此前的「坏行炸整会话」已修复 |
| 平台轨只读保护 | PlatformMemoryStore L44-L58 | append 抛异常、clear 忽略，不会被用户数据污染 |
| 主端点漂移防护 | EndpointAccessCenter L54-L78, L141-L173 | 多 main/memory 角色归一化 + 保序更新，未见漂移路径 |
| ASK 挂起持久化 | AskSuspender（D7） | pending-ask 本地持久化机制存在 |
| md 轨项目分组 | MarkdownMemoryWriter L157-L171 | `memory/md/<workspaceId>/` 已按项目分组 |

## 优先级建议（修复顺序）

1. **P0-1/P0-2/P0-3**（意图解析轮三连：指令丢失、SSE 污染、judge 污染上下文）——直接影响每轮对话正确性，建议合并重构：内部轮（解析/判定）统一走「不进会话状态、不推用户事件流、不发布 stop」的轻量通道。
2. **P0-4**（记忆三连调阻塞收尾）——改为异步 + 增量触发（如仅会话结束或 N 条消息后）。
3. **P1-2/P1-3**（重启丢上下文、压缩不回写）——会话状态与持久化的一致性。
4. **P1-5**（记忆端点路由失效）——路由层给 memory 角色开专用解析方法。
5. **P1-4**（Key 明文）——按 D8 补 AES-GCM。
6. P2 批次（子代理参数展平、token 统计、stuck 判定）随下次迭代带上。

# agent-web 模块实现文档

> 对应需求文档：§4.10 Web 文件操作服务、§七 整体架构蓝图前端层、§5.1 账号与用户体系、§5.2 隐私合规

## 一、模块定位

`agent-web` 是 Web 前端接口层，通过 `AgentChannel` 消费内核的 `Flux<AgentEvent>`，提供对话、工作区、文件浏览器、思考过程、账号/模型/权限配置入口。它只作交互外壳，内核能力在 `agent-core` 等后端模块。

**责任边界**：
- HTTP / WebSocket(SSE) 接口、参数校验、转发内核。
- 账号为**本机本地身份**（存 `<frameworkRoot>/.config/`），不依赖服务端用户库。
- 不直接连文件系统，文件操作经 permission → executor 转发。
- 不持久化任何用户数据到服务端。

---

## 二、实现框架（分层）

```
agent-web
├── controller/
│   ├── ChatController.java       # 对话接口（SSE 流式）
│   ├── WorkspaceController.java  # 工作区管理
│   ├── FileController.java        # 文件操作入口（转发）
│   ├── AccountController.java     # 本机账号/个人中心
│   ├── ModelController.java       # 模型接入配置
│   └── PermissionController.java  # 权限级别配置
├── stream/
│   ├── AgentChannel.java         # 引擎 ↔ 通道契约
│   ├── WebChannel.java           # Web SSE 适配
│   └── ReactiveSsePublisher.java # Flux<AgentEvent> → SSE
├── account/
│   └── LocalAccountService.java  # 本机本地身份（<frameworkRoot>/.config/）
├── privacy/
│   └── PrivacyController.java     # 清记忆/隐私声明
└── config/ WebConfig.java
```

**目录落盘（§3.0）**：账号/配置存 `<frameworkRoot>/.config/`；隐私清除清 `<frameworkRoot>/.memory/`。

---

## 三、实现思路（核心设计）

### 3.1 对话与流式（§七）

- `ChatController` 经 `WebChannel` 消费 `Flux<AgentEvent>`，以 SSE 推送推理过程、任务列表、进度与最终答案，前端流式感知。
- 长任务不阻塞：Project Reactor + SSE，内核线程不被长推理占死（§2.2）。
- 前端静态资源由后端统一托管，默认监听 `127.0.0.1:8080`，一键启动后自动打开浏览器。

### 3.2 工作区与文件浏览器

- 前端文件操作 → `FileController` → `agent-permission` 转发 → 执行臂本机执行 → 回传元数据（框架服务端不落文件内容）。

### 3.3 本机账号（§5.1）

- `LocalAccountService`：账号为本机本地身份（存 `<frameworkRoot>/.config/`），用于隔离多用户本机配置/记忆；登录/会话本机完成；无注册、无中心库。
- 个人中心：工作空间与权限管理、模型接入配置、记忆清除（清本机）。

### 3.4 隐私合规（§5.2）

- `PrivacyController` 提供"清除我的记忆"（清 `<frameworkRoot>/.memory/` + 内存）。
- 明确"平台不存储用户内容"，第三方模型风险由用户 Key 责任方承担（前端明示）。

---

## 四、关键配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| web.sse.timeoutSec | 300 | SSE 超时 |
| web.host | 127.0.0.1 | 本地 Web 默认绑定 |
| web.port | 8080 | 本地 Web 端口 |
| web.account.dir | .config | 本机账号 |
| web.privacy.clearMemory | true | 清记忆入口 |

---

## 五、与其他模块关系

- `agent-core`：对话请求入内核。
- `agent-permission`：文件/权限转发。
- `agent-workspace`：工作区管理入口。
- `agent-model`：模型配置入口。
- `agent-memory`：清记忆入口。

---

## 六、具体设计

> 核心思路：web 是纯外壳——对话/工作区/文件/账号/隐私清除入口 + SSE 流式。所有能力委托后端模块，自身不写业务。不持久化任何用户数据到服务端。本模块只做职责、流程与边界层面的设计，不涉及具体代码实现。

### 6.1 核心职责
- **对话入口（AgentChannel + Flux 流式）**：接收用户对话请求，委托 core 引擎运行，经 `AgentChannel` 消费 `Flux<AgentEvent>` 持续推送推理步骤、任务列表与进度；长推理在异步任务中执行，不占用 Web 容器业务线程。
- **工作区/文件入口**：仅做转发（文件操作转 permission，由执行臂落本机），响应体只含路径/大小/状态，不碰文件内容。
- **本机账号**：维护本机身份（`<frameworkRoot>/.config/account.json`），无密码库、无中心注册。
- **隐私清除**：提供"清记忆/清缓存"入口，调用对应后端模块清空本机数据。
- **本地 Web 托管**：托管 Vue 构建产物，默认仅本机访问；局域网访问需显式配置。

### 6.2 本机账号（`<frameworkRoot>/.config/account.json`）
- 仅 `{userId, displayName}`，无密码库、无中心注册；会话本机维护。

### 6.3 设计模式应用
- **门面模式**：各入口对前端屏蔽后端模块复杂度。
- **异步/观察者**：SSE 用异步 + 流式推送，长推理不占 Web 容器线程。
- **委托**：账号/隐私/文件全部委托对应后端模块，web 无业务逻辑。

### 6.4 验收点（具体）

- [ ] 发起长对话，Web 容器业务线程在异步返回后立即释放，SSE 持续推送（压测验证不阻塞）。
- [ ] 文件入口转发文件操作，响应体无文件正文，仅路径/大小/状态。
- [ ] 账号入口不连任何外部用户库；`account.json` 存于 `<frameworkRoot>/.config/`（框架隐藏目录）。
- [ ] 隐私清除后 `<frameworkRoot>/.memory/` 目录为空且内存记忆清空，服务端无残留。
- [ ] 前端可见 token/调用数（若接透明面板）数据来自 common 埋点。
- [ ] WebChannel 只消费 `Flux<AgentEvent>`，不持有业务逻辑；CLI 后续可复用同一 `AgentChannel` 契约。
- [ ] 复杂任务、子代理进度以 `task_plan`/`task_progress` 事件实时展示。
- [ ] 后端托管前端静态资源，默认 `127.0.0.1:8080` 可访问；未显式开启时局域网不可达。

---

## 七、对话可视化链路设计（问题 1：LLM 调用链路可视化）

> 目标：对话窗口不仅展示最终回复，还能把 **推理过程（Thought）、工具调用（Tool）、Skill 调用、MCP 调用、挂起提问（ASK）、异常** 以结构化事件流实时呈现，让用户"看得懂 Agent 在干什么"。

### 7.1 可视化靠 SSE 结构化事件，而非纯文本
core 引擎每产生一个"可观测节点"就通过 SSE 推一条 `AgentEvent`，前端按 `type` 渲染成不同 UI 组件（气泡/卡片/折叠树）。事件结构（示意，非代码）：

```json
{ "type":"thought",        "phase":"PLAN", "content":"我需要先查看目录结构…" }
{ "type":"action",         "tool":"file.read", "args":{ "path":"src/" }, "callId":"c1" }
{ "type":"tool_result",    "callId":"c1", "source":"tool",  "ok":true,  "summary":"12 个文件" }
{ "type":"skill_invoke",   "skillId":"pdf",   "match":"语义命中 top1" }
{ "type":"mcp_invoke",     "serverId":"github", "tool":"repo.search", "source":"mcp" }
{ "type":"ask",            "question":"是否允许执行 rm -rf？", "risk":"HIGH" }
{ "type":"error",          "stage":"tool", "callId":"c1", "msg":"权限不足", "fallback":"转 ASK" }
{ "type":"task_plan",      "tasks":[ { "taskId":"t1", "title":"扫描目录结构" }, { "taskId":"t2", "title":"生成代码" } ] }
{ "type":"task_progress",  "taskId":"t1", "status":"running", "done":1, "total":2 }
{ "type":"token",          "used":1280, "total":9300, "model":"qwen-max" }   // 实时消费
```

### 7.2 四类调用如何区分展示
- **工具调用（Tool）**：`type=action/tool_result` + `source=tool`，由 `ToolGateway` 统一打点；前端标"内置工具"。
- **Skill 调用**：`type=skill_invoke`，由 skill 模块在命中并适配为 Tool 前先发事件，前端标"技能"并显示匹配来源（语义 topK）。
- **MCP 调用**：`type=mcp_invoke` + `source=mcp`，由 mcp 模块在已授权 Server 上发起前打点，前端标"外部服务"并显示 server 名。
- 三者都最终走 `ToolGateway` 执行，因此"调用结果"统一用 `tool_result` 回写 `callId`，前端以 `callId` 把"发起—结果"串成一条时间线。

### 7.3 风险/消费信息随事件透出
- 每次 `action` 若被 `DangerousOpDetector` 判定高风险，事件带 `risk` 字段，前端高亮并联动 ASK。
- 每个 `token` 事件携带增量用量，前端实时绘制"本次会话 token 消费进度条"（呼应问题 5 的实时监控）。

### 7.4 任务列表与进度展示

- 复杂任务由 core 的 `TaskDecomposer` 拆成小任务列表，经 `task_plan` 推送初始清单。
- 执行过程中每项状态变化经 `task_progress` 推送，前端渲染待执行/执行中/已完成/失败/挂起，并显示完成比例。
- 任务与子代理、工具调用通过 `taskId`/`callId` 关联，点击任务可展开该步骤的 Thought/Action/Observation 链路。
- 仅在复杂任务（含多个可执行步骤）时展示任务列表，简单对话保持普通对话样式。

### 7.5 验收点
- [ ] 一次含"读文件→命中 skill→调 mcp"的对话，前端依次出现 thought / action(tool) / skill_invoke / mcp_invoke / tool_result 节点，且 `callId` 串联正确。
- [ ] 高风险操作事件 `*risk=HIGH*` 在前端高亮并弹出确认（ASK）。
- [ ] 关闭 SSE 后重连，能从断点继续接收后续事件（至少不丢已发生节点）。
- [ ] 复杂任务对话中出现任务列表，状态与完成比例随执行实时更新，与后端状态一致。

# Lucky Agent

**本地优先的单 Agent / 多 Agent 智能体编排框架**

Lucky Agent 是一个运行在**用户本机**的 Agent 框架：它只负责 Agent 的**编排、推理与执行链路**（支持单 Agent 与多 Agent / 隔离子代理任务），不托管模型、不存储用户信息、不对用户数据做任何服务端管理。

- 用户数据始终留在本机工作空间，平台不接触、不存储、不回传。
- 突出 **Java 的稳定与工程严谨**，融合 **Agent 的创作性**

---

## ✨ 特性

- **本机优先（零托管）**：会话、记忆、文件、配置全部存放于本机 `~/.lucky_agent`，天然不接触用户内容。
- **REACT 主回环编排**：分析 → 拆分步骤/子代理 → 执行 → 客观验证 → 达成度判定 → 记忆管理 → 再分析，全程安全阀（最大迭代/回合/token）兜底。
- **多 Agent 子代理（按需启用）**：极高复杂任务可在 PLAN 阶段声明 `subagents`，框架启动**隔离子代理**（独立会话上下文、工具白名单、受限预算、只回摘要），结果回灌主链路继续推进进度；默认单 Agent，开关 `core.subagent-enabled` 开启。
- **客观验证链**：步骤可声明 `verify`（文件存在/内容断言、构建/测试/命令退出码），以客观信号为准，不依赖模型自述。
- **硬性权限边界**：执行臂强制权限级别（只读/修改/全部）+ `deny > ask > allow` 规则链；高危操作先 ASK 确认，重启可恢复。
- **记忆系统（双轨）**：用户轨读写（本机 JSONL）+ 平台轨只读；召回按 ASCII 词 + 中文 bigram 相关性注入提示词。
- **五级上下文压缩**：上下文接近阈值自动触发 `FiveLevelCompactionPipeline`——工具结果裁剪（Microcompact）→ 滑动窗口（Snip）→ 摘要下沉（Reactive），全程由 `PreservedSegment` 保全系统提示/最近 N 轮/关键结果，连续失败由熔断器（TokenCircuitBreaker）兜底；压缩前的执行结论再沉淀为长期记忆，支撑主回环多轮自纠。
- **工具与 Skill**：文件/Shell/Skill/MCP 统一网关；Skill 按需注入（渐进披露），按扩展名自动判语言。
- **提示词分层**：基座（`LUCKY.md`，可改即生效）+ 人格 + 阶段指令 + 权限约束 + 记忆召回，静态/动态分界。

---

## 📁 目录结构

```
lucky_agent/
├── lucky / lucky.bat / lucky.sh   # 一键启动脚本（Windows / bash）
├── LICENSE
└── web/
    ├── app/                       # 后端（Maven 多模块，Java 17 + Spring Boot 3）
    │   ├── agent-web/             # 入口：REST + SSE 事件流，托管前端
    │   ├── agent-core/            # 编排中枢：REACT 引擎、Orchestrator、子代理、压缩、验证
    │   ├── agent-model/           # 模型端点接入（OpenAI/Anthropic 兼容）、提示词、基座外置
    │   ├── agent-common/          # 公共 DTO/常量/契约
    │   ├── agent-workspace/       # 工作空间配置（唯一 owner）
    │   ├── agent-permission/      # 权限规则/审计/危险操作识别
    │   ├── agent-executor/        # 文件服务/执行臂接入
    │   ├── agent-memory/          # 记忆读写与召回
    │   ├── agent-skill/           # Skill 注册与注入
    │   ├── agent-persona/         # 人格/角色
    │   ├── agent-mcp/             # MCP 工具接入
    │   ├── agent-cache/           # 缓存
    │   └── agent-cli/             # CLI 外壳（后续）
    └── fronted/                   # 前端（Vue 3 + TypeScript + Vite + Pinia + Vue Router）
```

运行期数据位于 `~/.lucky_agent/`（框架必备目录）：

| 目录 | 作用 |
|------|------|
| `LUCKY.md` | **基座提示词**，改完保存即时生效 |
| `settings.json` | 全局设置（模型端点 + 推理深度） |
| `config/` | 账号 / 工作空间注册表 / 加密密钥 / Skill 状态 |
| `memory/` | 用户轨记忆（`{userId}/mem.jsonl`） |
| `skills/` | 全局（平台级）Skill 库 |
| `agent/sessions/` | 会话历史（JSONL + 元数据） |
| `logs/audit.jsonl` | 操作审计日志 |
| `rollback/` `trash/` | 回滚快照 / 回收站 |
| `workspace/` | 内置默认工作空间（用户产物） |

---

## 🧱 技术栈

| 层 | 选型 |
|----|------|
| 语言 / 框架 | Java 17+ · Spring Boot 3.x · Maven 多模块 |
| AI 编排核心 | LangChain4j（Agent/工具/记忆/RAG）+ LangGraph4j（工作流/多 Agent 图，可选模式） |
| 辅助接入 | Spring AI（模型/嵌入/向量/可观测性桥接） |
| 前端 | Vue 3 · TypeScript · Vite · Pinia · Vue Router |

---

## 🚀 快速开始

### 前置依赖
- **Java 21+**
- **Maven 3.9+**（一键脚本首启时自动构建后端）
- **Node.js 18+ / npm**（一键脚本首启时自动构建前端）
- 一个 OpenAI / Anthropic 兼容的模型端点与 API Key（自备）

### Windows
```bat
lucky.bat
```
### macOS / Linux
```bash
./lucky.sh
```

首次运行会自动：`mvn package` 构建后端 → `npm ci && npm run build` 构建前端 → 启动服务。

浏览器访问 **http://127.0.0.1:8080**（默认监听本机；可用 `LUCKY_HOST` / `LUCKY_PORT` 覆盖端口）。

### 首次配置
1. 启动后在界面「设置」中添加模型端点（`endpointUrl` + `apiKey` + `modelName`），并选择推理深度。
2. 基座提示词可直接编辑 `~/.lucky_agent/LUCKY.md`，保存即生效。
3. 如需隔离子代理，将后端 `application.yml` 的 `core.subagent-enabled` 设为 `true`。

---

## 🔧 开发

### 后端
```bash
cd web/app
mvn compile                 # 编译
mvn test                    # 运行单测
mvn -pl agent-web -am package -DskipTests   # 打包
```
核心配置：`web/app/agent-web/src/main/resources/application.yml`
- `core.orchestrator-mode`: `reactor`（默认）/ `langgraph`
- `core.subagent-enabled`: 是否启用隔离子代理
- `model.context-threshold`: 上下文压缩阈值

### 前端
```bash
cd web/fronted
npm ci
npm run dev      # 开发服务器（代理后端 /api）
npm run build    # 生产构建（产物 dist 不入库）
```

### 运行测试
```bash
cd web/app && mvn -o test
```

---

## 🧭 使用说明

- **对话/Agent 执行**：直接在聊天框描述目标；简单问题即时回答，复杂任务自动规划。
- **拆解方式（B 节点决策）**：普通拆分→`steps`（同一 Agent 分步执行）；高复杂度独立子问题→`subagents`（隔离子代理，需开启开关）。
- **文件**：文件页可浏览目录、语法高亮预览、行内编辑与保存。
- **高危操作**：删除/执行等会触发确认，可在设置调整权限级别。

---

## 🖼️ 界面预览

![首页](docs/screenshots/home.png)

![文件树](docs/screenshots/file-tree.png)

![文件编辑](docs/screenshots/file-editor.png)

![Skill 界面](docs/screenshots/skill.png)

![实时监控](docs/screenshots/monitor.png)

---

## ⚙️ 配置速查

| 配置 | 位置 | 说明 |
|------|------|------|
| 模型端点 / 推理深度 | `~/.lucky_agent/settings.json` | 主/备端点 + 全局推理深度 |
| 基座提示词 | `~/.lucky_agent/LUCKY.md` | system prompt 第一层，保存即生效 |
| 人格 | `~/.lucky_agent/config/persona.json` | 角色 / 语气 / 行为参数 |
| 权限规则 | `~/.lucky_agent/config/` | deny > ask > allow 规则链 |

> API Key 存于用户本机 `settings.json`，明文本机存储（零托管）；请勿提交到版本库。

---

## 🗺️ Agent 主链路

```text
输入 → LLM 分析(B)
        ├─ 拆成步骤？ ─否→ 单 Agent 直接执行 → 客观验证
        ├─ 拆成步骤？ ─是→ 逐步骤执行（失败重试/超限终止）
        └─ 启动隔离子代理？─是→ TaskScheduler 隔离执行 → 摘要回灌
→ 客观验证(D) → 达成度判定(I)
        ├─ 达成 → 总结输出
        └─ 未达成(未超上限) → 记忆管理(K: 压缩+沉淀) → 回 B 再分析
安全阀(N): 最大迭代/回合/token → 强制结束并总结
```

---

## 🧠 上下文压缩与记忆

上下文压缩（短期记忆）与长期记忆沉淀协同支撑主回环的多轮自纠，避免「未达成 → 再分析」时上下文只增不减。

### 五级上下文压缩（`FiveLevelCompactionPipeline`）
当会话消息占用接近上下文阈值（`model.context-threshold`，默认 0.9）自动触发，按序执行：

| 级别 | 策略 | 作用 |
|------|------|------|
| ① 裁剪 | `MicrocompactStrategy` | 截断过大的工具结果文本（`…（工具结果已裁剪）`） |
| ② 滑动窗口 | `SnipStrategy` | 丢弃最旧的「过程类」消息，保留 system 与最近 N 轮 |
| ③ 摘要下沉 | `ReactiveCompactStrategy` | 把旧工具结果/中间思考摘要为单行，保留关键前缀 |
| 保全 | `PreservedSegment` | 全程确保系统提示/最近 N 轮/关键结果不下沉，压缩后校验补回 |
| 熔断 | `TokenCircuitBreaker` | 压缩连续失败即熔断，兜底返回原消息，不无限重试 |

压缩前后触发 `PRE_COMPACT` / `POST_COMPACT` Hook（deny-wins 可阻断），并向界面推送压缩进度。

### 长期记忆沉淀
主回环每轮结束（K 节点，未达成回到 B 前）：
- **短期**：按阈值触发上述上下文压缩，回写会话状态；
- **长期**：把本轮执行/验证结论写入用户记忆轨（`~/.lucky_agent/memory/{userId}/mem.jsonl`，`source=round`，低置信），供后续轮次 `recall` 召回注入提示词。

---

## 📌 当前边界

- 当前以 **Web 端 Agent** 为主交互；CLI Agent 后续阶段接入（复用同一内核/配置/记忆）。
- `langgraph` 编排模式与 `reactor` 语义等价，为平行实现，默认 `reactor`。
- 记忆的衰减/去重/清洗等管线已定义、默认未全量接线；Key 本地明文存储。
- 客观验证默认开启（`core.verification-enabled`）。

---

## 📄 License

本项目基于 [LICENSE](./LICENSE)。

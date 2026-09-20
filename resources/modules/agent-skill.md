# agent-skill 模块实现文档

> 对应需求文档：§4.2 Skill 管理模块、§6 工具按需召回 Top-K

## 一、模块定位

`agent-skill` 负责 Skill 的注册、语义匹配、依赖解析、热插拔加载与执行沙箱。每个 Skill 映射为 LangChain4j 的 Tool / 子 Agent，运行时按语义 Top-K 注入（省 token）。

**责任边界**：
- Skill 生命周期与召回。
- 不负责推理（交给 core）、不负责权限裁决。

---

## 二、实现框架（分层）

```
agent-skill
├── api/
│   ├── SkillRegistry.java     # 注册中心契约
│   ├── SkillMatcher.java      # 语义匹配器契约
│   └── dto/ SkillDef, SkillMatch
├── registry/
│   ├── LocalSkillRegistry.java    # 平台预装 + 用户自定义
│   └── SkillLoader.java           # 热插拔加载器
├── match/
│   └── SemanticMatcher.java       # 语义 Top-K 匹配
├── resolve/
│   └── DependencyResolver.java     # 依赖解析
├── sandbox/
│   └── SkillSandbox.java           # 执行沙箱
└── config/ SkillConfig.java
```

**目录落盘（§3.0）**：
- 用户自定义 Skill：`<frameworkRoot>/.skills/`。
- 平台预置 Skill：随包在 `<frameworkRoot>/.platform/`，用户只读。

---

## 三、实现思路（核心设计）

### 3.1 注册中心

- 平台预装常用 Skill（随发行包），用户可在 Web 端开关。
- 用户自定义 Skill 脚本/配置存 `<frameworkRoot>/.skills/`，经 `LocalSkillRegistry` 注册。
- 每个 Skill 定义元数据：`name`、`description`、`trigger`（语义触发词）、`deps`、`entry`（脚本/类）。

### 3.2 语义匹配（Top-K，§6）

- `SemanticMatcher` 对用户意图/上下文做向量化，与 Skill `trigger` 向量比对，召回 Top-K（默认 K=5）注入，而非全量注入，节省 token。
- 匹配可借 `agent-memory` 的本地向量能力，复用本机索引。

### 3.3 热插拔加载

- `SkillLoader` 支持运行时加载/卸载 Skill（无需重启框架）。
- 加载后向 `agent-core` 的 `ToolGateway` 注册为 `Tool`。

### 3.4 依赖解析与沙箱

- `DependencyResolver`：解析 Skill 依赖（其他 Skill / 外部命令），缺失则提示。
- `SkillSandbox`：Skill 执行受限（限工作空间、受权限级别约束），危险动作转 ASK。

---

## 四、关键配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| skill.topK | 5 | 语义召回 Top-K |
| skill.user.dir | .skills | 用户 Skill 目录 |
| skill.platform.dir | .platform | 平台预置（只读） |
| skill.hotReload | true | 热插拔 |

---

## 五、与其他模块关系

- `agent-core`：Skill 经 `ToolGateway` 注册为 Tool。
- `agent-mcp`：Skill 可桥接 MCP 工具。
- `agent-executor`：Skill 执行受本端越界防护。
- `agent-permission`：Skill 危险操作转 ASK。

---

## 六、具体设计

> 核心思路：Skill 就是"可被语义召回的 Tool/子 Agent"。复用 LangChain4j 的 Tool 注册 + core 的 `ToolGateway`，匹配用本地向量（借 memory 后端），不过度造语义引擎。本模块只做职责、流程与边界层面的设计，不涉及具体代码实现。

### 6.1 注册与匹配职责
- **注册中心**：集中管理 Skill 生命周期，平台预装随包（`<frameworkRoot>/.platform/` 只读）与用户自定义（`<frameworkRoot>/.skills/`）两路加载；支持热插拔（加载/卸载单例，无需重启）。
- **语义匹配**：复用 memory 的本地向量后端，将用户意图与每个启用的 Skill 的 description/triggers 做相似度比对，取 Top-K 命中；匹配策略可换（向量/关键词），默认向量。
- **Skill → Tool 适配器**：把命中的 Skill 适配为 common 的 `Tool` 经网关注册；执行时走沙箱或子 Agent，并受 executor 越界防护。

### 6.2 Skill 元数据（落盘 `<frameworkRoot>/.skills/<id>/meta.json`）
```json
{ "id":"pdf", "name":"PDF 处理", "description":"解析/生成 PDF",
  "triggers":["pdf","导出"], "type":"TOOL", "sandbox":false, "enabled":true, "deps":[] }
```

### 6.3 设计模式应用
- **注册表模式**：注册中心集中管理生命周期，热插拔即增删注册项。
- **适配器模式**：Skill→Tool 适配器把 Skill 适配为 common `Tool`，无缝进 `ToolGateway`。
- **策略模式**：匹配可换实现（向量/关键词），默认向量。
- **观察者**：加载新 Skill 后发事件通知 core 刷新 Tool 列表（解耦，无循环依赖）。

### 6.4 验收点（具体）

- [ ] 平台 Skill 加载后处于启用但不可卸载状态（只读）。
- [ ] 用户装新 Skill 到 `<frameworkRoot>/.skills/` 后热加载生效，core 的 `ToolGateway` 出现该 Tool，无需重启。
- [ ] 对 10 个 Skill，注入上下文仅含 Top-K 命中项，网关注册总数 = 全量但注入按需。
- [ ] Skill 执行触发的文件路径经越界校验不逃逸工作空间。
- [ ] 关闭某 Skill 后，其 Tool 从网关注销，召回不再命中。

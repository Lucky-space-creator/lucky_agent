# agent-persona 模块实现文档

> 对应需求文档：§4.1 智能体个性模块

## 一、模块定位

`agent-persona` 负责个性配置中心、行为参数引擎、多 Agent 协作的角色与人格供给、个性-Skill 绑定。默认预设人格零配置可选，进阶自定义 System Prompt。

**责任边界**：
- 人格配置与行为参数。
- 多 Agent 协作的角色与人格供给；实际子代理执行由 `agent-core` 子代理执行器负责。
- 不负责推理/任务调度/工具执行。

---

## 二、实现框架（分层）

```
agent-persona
├── api/
│   ├── PersonaService.java     # 个性配置契约
│   ├── BehaviorEngine.java      # 行为参数契约
│   └── dto/ Persona, BehaviorParam
├── center/
│   ├── PersonaCenter.java      # 配置中心（预设+自定义）
│   └── PersonaStore.java       # 存 <frameworkRoot>/.config/
├── behavior/
│   └── BehaviorParamEngine.java  # 行为参数注入
├── collab/
│   └── MultiAgentOrchestrator.java  # 多 Agent 角色编排（执行委托 core 子代理执行器）
├── bind/
│   └── PersonaSkillBinder.java  # 个性-Skill 绑定
└── config/ PersonaConfig.java
```

**目录落盘（§3.0）**：人格配置存 `<frameworkRoot>/.config/`，运行时注入 LangChain4j/LangGraph4j。

---

## 三、实现思路（核心设计）

### 3.1 预设与自定义

- 默认预设人格（如"严谨工程师""快速助手"），零配置可选。
- 进阶自定义 System Prompt 与行为参数，存 `<frameworkRoot>/.config/`，运行时注入 `agent-core` 引擎。

### 3.2 行为参数引擎

- `BehaviorParamEngine` 将行为参数（语气/详尽度/主动性）转为 Prompt 约束，注入推理上下文。

### 3.3 多 Agent 协作

- `MultiAgentOrchestrator` 按主 Agent 的子任务需求准备角色、人格与偏好 Skill；实际子代理创建、执行与结果聚合由 `agent-core` 的 `SubAgentExecutor` 完成。
- persona 不持有任务调度与结果聚合逻辑，避免与 core 重复。
- 对用户透明：Web/CLI 默认展示统一结果，可按需展开子代理进度。

### 3.4 个性-Skill 绑定

- `PersonaSkillBinder`：特定人格可绑定偏好 Skill 集，提升语义召回相关性。

---

## 四、关键配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| persona.default | 严谨工程师 | 默认人格 |
| persona.config.dir | .config | 配置落盘 |

---

## 五、与其他模块关系

- `agent-core`：人格/行为参数注入引擎 System Prompt。
- `agent-skill`：个性绑定偏好 Skill。
- `agent-web`：前端人格选择入口。

---

## 六、具体设计

> 核心思路：人格 = "注入到 core 引擎 system prompt 的一段配置 + 行为参数"。不做自研编排，多 Agent 协作委托 LangChain4j 原生子 Agent 机制。不过度设计。本模块只做职责、流程与边界层面的设计，不涉及具体代码实现。

### 6.1 核心职责
- **人格中心**：维护预设人格（严谨工程师/快速助手等）与用户自定义人格，当前激活人格从 `<frameworkRoot>/.config/persona.json` 读取；未配置回落预设默认。
- **行为参数引擎**：把语气/详尽度/主动性等行为参数转为 system prompt 约束句片段，注入 core 引擎。
- **多 Agent 协作**：提供子代理所需的角色、人格与偏好 Skill；`agent-core` 的 `SubAgentFactory` 创建子代理时读取该配置，前端/CLI 只见统一结果，可按需展开。
- **个性-技能绑定**：给人格附加偏好 Skill 集，提升语义召回相关性。

### 6.2 落盘格式（`<frameworkRoot>/.config/persona.json`）
```json
{ "active":"strict", "custom":{ "systemPrompt":"...", "tone":"formal", "verbosity":0.6 } }
```

### 6.3 设计模式应用
- **策略模式**：每个人格即一套 prompt 策略，行为参数引擎按策略产出。
- **工厂**：人格中心按 id 造人格（预设/自定义）。
- **组合**：个性-技能绑定给人格附加偏好 Skill 集，提升语义召回相关性。

### 6.4 验收点（具体）

- [ ] 不配 persona 时回落预设默认，对话可正常跑。
- [ ] 改 `<frameworkRoot>/.config/persona.json` 并重启，读新值且 system prompt 含自定义句。
- [ ] 子代理携带指定人格/角色执行，前端/CLI 默认只见统一结果，可按需展开进度。
- [ ] 绑定偏好 Skill 后，语义召回对绑定 Skill 加权（命中率可观测）。

# agent-permission 模块实现文档

> 对应需求文档：§4.10 Agent 权限管理模块、§4.12 权限安全模块

## 一、模块定位

`agent-permission` 负责**工作空间注册、权限级别配置、deny/ask/allow 权限规则链、Web 操本机的转发与权限审计**。权限级别（只读/修改/全部）由用户配置、随调用带入对话上下文；权限规则链由框架执行，**执行臂为物理执行边界与最终裁决者**。本端越界防护由 `agent-executor` 的执行臂负责。

**责任边界**：
- 工作空间 ↔ 物理路径映射、权限级别配置（存 `<frameworkRoot>/.config/`）。
- `deny > ask > allow` 权限规则链与 `PermissionDecision` 裁决。
- Web 文件操作转发（框架服务端只转发指令/元数据，不落文件内容、不裁决）。
- 危险操作 ASK 确认触发、操作审计（仅元数据）。
- **不负责**本机 `realpath` 越界校验（那是执行臂的硬边界，见 executor）。

---

## 二、实现框架（分层）

```
agent-permission
├── api/
│   ├── WorkspaceRegistry.java     # 工作空间注册契约
│   ├── PermissionService.java     # 权限级别配置/查询契约
│   ├── FileOpForwarder.java       # Web 文件操作转发契约
│   └── dto/ Workspace, PermissionLevel, AuditMeta
├── registry/
│   └── LocalWorkspaceRegistry.java  # 读取 workspaceId ↔ 物理路径（写入归 agent-workspace）
├── config/
│   └── PermissionConfigStore.java   # 权限级别存 .config/
├── forward/
│   └── FileOpForwarderImpl.java      # 下发指令给执行臂，回传元数据
├── rules/
│   ├── PermissionRule.java           # 权限规则契约
│   ├── PermissionChain.java          # deny-wins 规则链
│   ├── PathRuleMatcher.java          # 路径规则（glob/regex + 锚定语义）
│   └── CommandRuleMatcher.java       # 命令规则（token 化匹配）
├── guard/
│   ├── DangerousOpDetector.java      # 危险操作识别 → ASK
│   └── AuditLogger.java              # 审计（仅元数据，.logs/）
└── config/ PermissionModuleConfig.java
```

**目录落盘（§3.0）**：
- 工作空间映射 + 权限级别：`<frameworkRoot>/.config/`（本机）。
- 审计日志：`.logs/`（仅元数据，不含文件内容）。

---

## 三、实现思路（核心设计）

### 3.1 用户指定路径 + 默认路径（§4.10）

- 用户在 Web 端"添加工作空间"，填写本机目录（如 `D:/mycode`）；框架生成 `workspaceId` 绑定该路径。
- 未指定时默认 `<workspaceRoot>`（`C:/lucky_agent`）下建子目录组织产物。
- 映射 `workspaceId ↔ 物理路径` 由 `agent-workspace` 写入 `<frameworkRoot>/.config/`，本模块只读取，不依赖服务端数据库（本机优先零托管）。

### 3.2 权限级别配置（非细粒度矩阵）

- 用户添加工作区时选：**只读 / 修改文件 / 全部权限（含执行）**。
- 配置随工作空间存 `<frameworkRoot>/.config/`。
- 每次对话调用，框架将该工作区权限级别作为上下文带入对话，作为体验层引导；精确权限由 `deny > ask > allow` 规则链裁决，**执行臂为物理执行边界与最终裁决者**。

### 3.3 权限规则链（八项差距 D14）

- 规则按顺序执行，`deny` 永远胜出（deny-wins）；无规则命中时按工作区级别走 `ask` 或 `allow`。
- MVP 支持路径规则（glob/regex）与命令规则（Apache Commons Exec token 化后匹配）。
- 路径锚定语义固定：`//abs`、`~/home`、`/project-root`、`relative` 在文档与实现中一致表达。
- 规则同步到执行臂本地（`PermissionRuleCache`），执行臂每次操作重新评估；框架侧裁决仅作提示。
- symlink 双路径检查、复合命令拆分、OS 级沙箱在 Phase 2 补齐。

### 3.4 Web 操本机文件链路

```
前端 → 框架文件服务(FileOpForwarder) → 下发指令给本机执行臂(4.11)
     → 执行臂在授权目录执行 → 结果(仅元数据)回传前端
```
框架服务端不接触文件内容，只转发指令与元数据。

### 3.5 危险操作确认（§4.12）

- `DangerousOpDetector` 识别删除/执行/覆盖等高风险动作 → 转 core 的 ASK 向用户确认。
- 结合 `agent-executor` 的回退快照（4.7）可撤销。

### 3.6 审计

- `AuditLogger` 仅记操作元数据（谁/何时/何操作/工作区），不留存文件内容（隐私）。

---

## 四、关键配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| permission.level.default | 修改文件 | 新建工作区默认级别 |
| permission.rule.file | <frameworkRoot>/.config/permission-rules.json | 权限规则配置 |
| permission.syncToArm | true | 权限规则同步到执行臂本地 |
| permission.config.dir | <frameworkRoot>/.config | 配置落盘 |
| permission.audit.dir | <frameworkRoot>/.logs | 审计日志 |

---

## 五、与其他模块关系

- `agent-workspace`：工作空间命名空间与配置中心（本模块侧重权限与转发）。
- `agent-executor`：本端 `realpath` 越界防护硬边界在此组件。
- `agent-core`：ASK 危险操作确认的触发点。
- `agent-web`：前端文件操作入口转发。

---

## 六、具体设计

> 核心思路：permission 模块负责"配置 + deny/ask/allow 规则链 + 转发 + 危险操作识别 + 审计"。三档权限级别作为体验引导，精确规则由规则链裁决，执行臂负责硬边界并作为最终裁决者。本模块只做职责、流程与边界层面的设计，不涉及具体代码实现。

### 6.1 核心职责
- **工作空间注册查询**：读取 `agent-workspace` 维护的 `workspaceId ↔ 路径 ↔ 权限级别` 映射（`<frameworkRoot>/.config/`），注册时校验路径本机存在，不写入映射。
- **权限级别查询**：对外提供"某工作区当前级别"，级别由 core 读取后带入对话上下文（只读/修改/全部）；规则链输出 `PermissionDecision` 给 core，执行臂为权限与越界的最终裁决者。
- **规则链裁决**：`PermissionChain` 按 `deny > ask > allow` 执行路径/命令规则，任一 `deny` 立即阻断；危险操作转 ASK。
- **指令转发**：仅把文件操作指令与元数据下发给本机执行臂，框架服务端不碰文件内容，返回结果也仅含元数据。
- **危险操作识别**：识别删除/执行/覆盖等高风险动作 → 发事件给 core 的 ASK 流程，由用户确认，不在本模块硬拦截。
- **审计**：仅记录操作元数据（谁/何时/何操作/工作区），不留存文件内容（隐私）。

### 6.2 设计模式应用
- **门面模式**：转发组件屏蔽执行臂通信细节，Web 层只调转发。
- **策略模式**：权限级别决定"带入上下文的提示词模板"，不加 if-else 大分支。
- **观察者/回调**：危险操作命中后发事件给 core 的 ASK 流程，模块间不反向依赖。
- **仓储**：工作空间注册隔离 `<frameworkRoot>/.config/` JSON 读写。

### 6.3 验收点（具体）

- [ ] 注册后 `<frameworkRoot>/.config/workspaces.json` 含 `workspaceId↔path↔level`，且无任何服务端写。
- [ ] core 拿到的级别被拼接进 system prompt，证据为对话请求含级别字段。
- [ ] 危险操作（删/执行/覆盖）识别命中，且 core 侧产生 ASK 而非直接执行。
- [ ] 审计行结构为 `{ts, user, wid, op, meta}`，无文件内容字节。
- [ ] 转发返回的执行结果不含文件正文（仅大小/路径/状态）。
- [ ] `deny > ask > allow` 规则链生效，任一 deny 立即阻断，不执行后续规则。
- [ ] 路径锚定语义与文档一致，`/project-root` 不会被误当成文件系统根。
- [ ] 复合命令按拆分后规则独立裁决，`safe-cmd && other-cmd` 不会整体放行。

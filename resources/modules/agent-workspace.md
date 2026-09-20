# agent-workspace 模块实现文档

> 对应需求文档：§4.8 工作区管理模块

## 一、模块定位

`agent-workspace` 负责工作区命名空间管理、工作区配置中心、任务分解进度。轻量化——工作区即"一个工作区/一个需求会话"，资源按 `workspaceId` 在本机隔离。

**责任边界**：
- 工作区命名空间与配置（路径映射 + 权限级别存 `<frameworkRoot>/.config/`）。
- 任务分解进度追踪。
- 不负责权限裁决逻辑（交给 permission 模块）。

---

## 二、实现框架（分层）

```
agent-workspace
├── api/
│   ├── WorkspaceManager.java   # 命名空间管理契约
│   ├── WorkspaceConfig.java     # 配置中心契约
│   └── dto/ Workspace, TaskProgress
├── ns/
│   └── NamespaceManager.java     # workspaceId 隔离
├── config/
│   └── WorkspaceConfigCenter.java  # 路径映射 + 权限级别（<frameworkRoot>/.config/）
├── progress/
│   └── TaskDecomposer.java        # 任务分解进度
└── config/ WorkspaceModuleConfig.java
```

**目录落盘（§3.0）**：工作空间路径映射 + 权限级别存 `<frameworkRoot>/.config/`；用户产物按 `workspaceId` 隔离于 `<workspaceRoot>/<workspace>/`。

---

## 三、实现思路（核心设计）

### 3.0 目录可见性约定（重要）

工作空间下分两类目录，**只有涉及 Agent 框架本身的目录才隐藏**，用户自己的工作区保持可见、可自由操作：

- **框架隐藏目录（十一个，以 `.` 开头）**：`.config`、`.memory`、`.platform`、`.rollback`、`.cache`、`.logs`、`.skills`、`.mcp`、`.tmp`、`.trash`、`.agent`。它们承载框架配置、记忆、缓存、审计、回收站、执行臂等运行数据，统一位于 `<frameworkRoot>/`，由框架标记为隐藏，用户一般无需直接改动。
- **用户可见工作区**：工作空间根下的其它全部目录与文件（用户的代码、文档、生成物，以及按 `workspaceId` 隔离的产物目录）一律**可见、可操作**，框架不对其做任何隐藏处理。框架只在这些目录内"操作"，不隐藏、不遮挡、不打扰用户。

> 隐藏的是"框架自己用的目录"，不是"用户的文件"。沙箱边界仍是工作空间根目录（realpath 校验），隐藏约定不影响边界判定。

### 3.1 轻量命名空间（§4.8）

- 工作区即"一个工作区/一个需求会话"，建名即用。
- 资源按 `workspaceId` 在本机工作空间内隔离（产物目录 `<workspace>/`、回退 `.rollback/<workspaceId>/`、锁 `<workspaceId>.*.lock`）。

### 3.2 配置中心

- 配置中心维护 `workspaceId ↔ 物理路径` 与权限级别，映射数据存 `<frameworkRoot>/.config/`（属框架隐藏目录），不依赖服务端数据库（本机优先零托管）。

### 3.3 任务分解进度

- 复杂任务由 core 拆成小任务列表，本模块记录每项状态（待执行/执行中/已完成/失败/挂起）与完成比例，供 Web/CLI 对话界面展示。
- 进度数据仅来自 core 的 PLAN/子代理执行事件，本模块不自行分解任务。

---

## 四、关键配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| workspace.config.dir | <frameworkRoot>/.config | 映射与权限落盘 |
| workspace.product.dir | <workspaceRoot>/<workspace>/ | 产物隔离目录 |

---

## 五、与其他模块关系

- `agent-permission`：权限级别配置在本模块配置中心，由 permission 读取带入上下文。
- `agent-executor`：产物/回退/锁均按 workspaceId 隔离。
- `agent-web`：前端工作区管理入口。

---

## 六、具体设计

> 核心思路：workspace 是"轻量命名空间 + 配置中心"。只维护 `workspaceId↔物理路径↔权限级别` 映射（存 `<frameworkRoot>/.config/`），并跟踪任务进度。不引入项目管理复杂度。本模块只做职责、流程与边界层面的设计，不涉及具体代码实现。

### 6.1 命名空间管理（NamespaceManager）

- **职责**：建名即用。创建时生成 `workspaceId`，并将"名称↔路径↔权限级别"写入 `<frameworkRoot>/.config/workspaces.json`（框架隐藏目录）。
- **隔离**：资源按 `workspaceId` 在工作空间内隔离——用户产物落 `<workspaceRoot>/<wid>/`，回退快照落 `<frameworkRoot>/.rollback/<wid>/`，读写锁文件名为 `<wid>.*.lock`。
- **查询**：提供"列表 / 按 id 取详情"两类能力，供 Web 前端与 executor、permission 复用。

### 6.2 配置中心（WorkspaceConfigCenter）

- **职责**：维护配置源，对外提供"某工作区的权限级别"与"物理路径"两路查询。permission 模块直接读取这里的权限级别并带入对话上下文，保证权限判定与配置同源、单一事实。
- **零托管**：配置落 `<frameworkRoot>/.config/`，不依赖任何服务端数据库。

### 6.3 任务进度（TaskDecomposer）

- **职责**：以订阅方式跟踪 core 的 PLAN/子代理执行事件，维护小任务列表、状态与完成比例，供前端展示。
- **解耦**：仅被动接收进度事件，不反向调用 core，避免循环依赖。

### 6.4 设计模式应用

- **仓储模式**：配置中心隔离 `<frameworkRoot>/.config/` 文件的读写细节，上层只关心配置内容。
- **享元**：工作区对象仅携带 id/path/level 轻量字段，重资源（如大文件句柄）不驻留。
- **观察者**：任务进度订阅 core PLAN 事件更新，模块间无强耦合。

### 6.5 验收点（具体）

- [ ] 创建工作区后，`<workspaceRoot>/<wid>/` 目录被建立，且 `<frameworkRoot>/.config/workspaces.json` 含该映射。
- [ ] `.rollback/<wid>/` 与锁文件均带 `wid`，跨工作区不串。
- [ ] permission 读到的权限级别与本模块配置中心一致（同一 config 源）。
- [ ] 任务进度视图返回的步骤状态，与 core 实际执行步骤对齐（断点可展示）。
- [ ] 复杂任务产生小任务列表，状态与完成比例在对话界面实时更新，断点/重连后可恢复展示。
- [ ] 框架隐藏目录（十一个 `.` 前缀目录）位于 `<frameworkRoot>/`，对用户不可见；用户自身的 `<wid>/` 产物目录及 `<workspaceRoot>/` 下非 `.` 文件保持可见、可操作。

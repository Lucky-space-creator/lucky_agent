# agent-mcp 模块实现文档

> 对应需求文档：§4.3 MCP 扩展服务模块

## 一、模块定位

`agent-mcp` 负责 MCP Server 的注册发现、连接管理、Tool 适配、用户授权隔离与健康探活。MCP 多为平台能力，用户点"授权连接"即可用。

**责任边界**：
- MCP 连接生命周期与 Tool 适配。
- 不负责推理、不负责本机文件越界防护（执行臂负责）。

---

## 二、实现框架（分层）

```
agent-mcp
├── api/
│   ├── McpRegistry.java       # 注册发现契约
│   ├── McpConnector.java      # 连接管理契约
│   └── dto/ McpServerDef, McpTool
├── registry/
│   └── LocalMcpRegistry.java   # 用户自建 MCP 配置（.mcp/）
├── connect/
│   ├── ConnectionManager.java  # 连接池 + 心跳
│   └── HealthProbe.java        # 健康探活
├── adapter/
│   └── ToolAdapter.java        # MCP Tool → agent-core Tool
├── auth/
│   └── UserAuthIsolator.java   # 用户授权隔离
└── config/ McpConfig.java
```

**目录落盘（§3.0）**：
- 用户自建 MCP Server 配置/脚本：`<frameworkRoot>/.mcp/`。
- 平台 MCP 连接配置：存 `<frameworkRoot>/.config/`。

---

## 三、实现思路（核心设计）

### 3.1 注册发现

- 平台预置 MCP Server 列表（随发行包/配置下发的"可用列表"，非用户数据）。
- 用户自建 MCP 配置存 `<frameworkRoot>/.mcp/`，经 `LocalMcpRegistry` 注册。

### 3.2 连接管理

- `ConnectionManager` 维护 MCP 长连接池，带心跳保活。
- `HealthProbe` 周期探活，失效连接自动重连/下线。

### 3.3 Tool 适配

- `ToolAdapter` 将 MCP 暴露的 Tool 映射为 `agent-core` 的 `Tool`，经 `ToolGateway` 注册。
- 调用经权限安全校验（转交 permission 模块）。

### 3.4 用户授权隔离

- `UserAuthIsolator`：用户需显式"授权连接"某 MCP，未授权不注入其 Tool。
- 授权状态存 `<frameworkRoot>/.config/`，不依赖服务端。

---

## 四、关键配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| mcp.user.dir | .mcp | 用户 MCP 目录 |
| mcp.heartbeatSec | 30 | 心跳周期 |
| mcp.healthProbeSec | 60 | 探活周期 |

---

## 五、与其他模块关系

- `agent-core`：MCP Tool 经 `ToolGateway` 注册。
- `agent-permission`：MCP 调用经权限校验。
- `agent-skill`：Skill 可桥接 MCP。

---

## 六、具体设计

> 核心思路：MCP 与 Skill 同构——都是"外部 Tool 源"。差异在连接管理（长连接+探活）与授权隔离。复用 core `ToolGateway`，不过度设计。本模块只做职责、流程与边界层面的设计，不涉及具体代码实现。

### 6.1 注册与连接职责
- **注册中心**：维护 MCP Server 的生命周期与"用户显式授权集合"；用户自建配置（`<frameworkRoot>/.mcp/`）与平台列表（`<frameworkRoot>/.config/mcp`）两路加载，未授权不注入其 Tool。
- **连接管理**：对 `McpSession` 做连接池 + 周期探活（HealthProbe），失效自动重连/降级；调用方对懒连与重连无感。
- **MCP Tool → Tool 适配器**：仅对"已授权"的 MCP 生成适配器并经网关注册；执行时经 `McpSession` 调远端，受 permission 越界校验。

### 6.2 设计模式应用
- **适配器模式**：把 MCP Tool 适配为 common `Tool`。
- **代理模式**：连接管理对 Session 做懒连/重连代理，调用方无感。
- **观察者**：授权变更事件 → 刷新 gateway 注册（同 Skill 解耦思路）。
- **注册表**：注册中心管生命周期 + 授权集合。

### 6.3 验收点（具体）

- [ ] 未出现在授权集合的 MCP，其 Tool 不被注册到 `ToolGateway`，LLM 看不到该 Tool。
- [ ] 强制关闭 MCP 进程，探活在心跳超时后标记下线，下一次获取触发重连或抛降级。
- [ ] 已授权 MCP 的 Tool 执行前经 `PermissionService` 校验（越界/危险操作→ASK）。
- [ ] 用户自建 MCP 配置写入 `<frameworkRoot>/.mcp/`，验证无外发该路径内容。
- [ ] 取消授权后，对应 Tool 从网关注销。

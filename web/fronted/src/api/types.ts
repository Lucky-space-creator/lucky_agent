// 与后端契约定义（resources/file/契约定义.md）对齐的类型

export interface SessionRef {
  sessionId: string
  userId: string
  workspaceId: string
}

export type AgentEventType =
  | 'thought'
  | 'progress'
  | 'content_delta'
  | 'action'
  | 'tool_result'
  | 'skill_invoke'
  | 'mcp_invoke'
  | 'task_plan'
  | 'task_progress'
  | 'ask'
  | 'options'
  | 'error'
  | 'token'
  | 'stop'

export interface AgentEvent {
  version: string
  type: AgentEventType
  eventId: string
  sessionId: string
  taskId?: string
  callId?: string
  ts: string
  payload: Record<string, any>
}

export interface Workspace {
  workspaceId: string
  name: string
  path: string
  permissionLevel: 'READ_ONLY' | 'MODIFY' | 'FULL'
  createdAt: string
  /** 内置默认工作空间：前端不展示、不可删除，仅在无自建工作空间时兜底使用。 */
  builtin?: boolean
}

export interface ModelConfig {
  id?: string
  name: string
  endpointUrl: string
  modelName: string
  contextWindow?: number
  temperature?: number
  maxTokens?: number
  enabled?: boolean
  role?: string
  /** 明文 Key（存于用户本机 settings.json，零托管）；列表接口恒为空，仅保存时可写。 */
  apiKey?: string
  /** 是否已配置 Key（列表接口脱敏后据此提示「已配置，留空不修改」）。 */
  keyConfigured?: boolean
}

/** 全局推理深度（枚举名，与后端 settings.json 一致）。 */
export type InferenceDepth = 'OFF' | 'QUICK' | 'BALANCED' | 'DEEP' | 'MAXIMUM'

/** 单端点用量统计（模型详情小窗数据源；与后端 model-usage.json 对齐）。 */
export interface ModelUsage {
  modelId: string
  modelName?: string
  /** 调用次数。 */
  calls: number
  /** 累计输入 token 数。 */
  inputTokens: number
  /** 累计输出 token 数。 */
  outputTokens: number
  /** 累计失败次数。 */
  errors: number
  /** 最近一次调用时间（毫秒时间戳），无调用为空。 */
  lastUsedAt?: number
}

/** 模型端点探活结果。 */
export interface ProbeResult {
  id: string
  name: string
  healthy: boolean
  status: number | null
  message: string
}

export interface FileEntry {
  name: string
  path: string
  dir: boolean
  size: number
}

export interface ExecResult {
  ok: boolean
  opType?: string
  requestId: string
  path?: string
  size: number
  summary?: string
  content?: string
  entries?: FileEntry[]
  error?: string
}

export type FileOpType =
  | 'READ'
  | 'WRITE'
  | 'DELETE'
  | 'LIST'
  | 'MKDIR'
  | 'RENAME'
  | 'STAT'
  | 'EXEC'

export interface FileOp {
  opType: FileOpType
  workspaceId: string
  path?: string
  content?: string
  args?: Record<string, any>
}

export interface RunResult {
  sessionId: string
  status: 'success' | 'max_turns' | 'max_budget' | 'error'
  summary: string
  error?: string
  tokenUsed?: number
  model?: string
}

export interface PermissionRule {
  version?: string
  id: string
  priority: number
  type: 'PATH' | 'COMMAND'
  matcher: { pattern: string; anchor: string }
  action: 'ALLOW' | 'DENY' | 'ASK'
  reason?: string
}

export interface SessionMessage {
  role: string
  content: string
  ts?: string
  /** 该消息执行期间创建的文件检查点 ID（可空；非空表示可消息级回溯）。 */
  checkpointIds?: string[]
}

export interface SessionSnapshot {
  sessionId: string
  userId: string
  workspaceId: string
  messages: SessionMessage[]
  state: Record<string, any>
}

/** 透明面板指标（与后端 /api/chat/{sessionId}/metrics 对齐，会话级统一累计）。 */
export interface SessionMetrics {
  tokenUsed: number
  inputTokens: number
  outputTokens: number
  modelCalls: number
  cacheHits: number
  cacheMisses: number
  cacheHitRate: number
  toolCounts: Record<string, number>
  toolOk: number
  toolFail: number
  skillInvokes: number
  mcpInvokes: number
  errors: number
  elapsedMs: number
  lastModel: string
  contextWindow: number
  windowUsage: number
  subAgents: Record<string, { done: number; total: number; status: string }>
}

/** MCP Server 视图（配置 + 运行状态 + 授权标记）。 */
export interface McpServerView {
  id: string
  name: string
  description: string
  type: 'STDIO' | 'HTTP'
  command: string
  args: string[]
  endpointUrl: string
  enabled: boolean
  status: string
  authorized: boolean
}

/** MCP Server 配置定义（本地上传返回，对应磁盘 meta.json）。 */
export interface McpServerDef {
  id: string
  name: string
  description: string
  type: 'STDIO' | 'HTTP'
  command: string
  args: string[]
  endpointUrl: string
  env?: Record<string, string>
  enabled: boolean
}

/** Skill 定义。 */
export interface SkillDef {
  id: string
  name: string
  description: string
  triggers: string[]
  type: 'TOOL' | 'SUBAGENT'
  sandbox: boolean
  enabled: boolean
  deps: string[]
  entry: string
  source: 'PLATFORM' | 'USER'
  /** Level 2 正文（SKILL.md 内容，编辑时回填）。 */
  skillMd?: string
  /** 缺失的依赖 Skill id（依赖不齐全时展示警告、禁止启用）。 */
  missingDeps?: string[]
}

/** 打开系统配置目录结果。 */
export interface SystemOpenResult {
  opened: boolean
  path: string
  /** 打开失败时的原因说明（可选）。 */
  message?: string
}

/** 单条规则（多条规则体系；默认存储于 LUCKY.md 的 `## 规则名` 分节）。 */
export interface RuleItem {
  /** 规则名（分节标题）。 */
  name: string
  /** 规则正文。 */
  content: string
  /** 是否启用；停用规则保留在文件中但不注入模型。 */
  enabled: boolean
  /** 作用域：global 全局 / project 项目（项目优先级更高）。 */
  scope: 'global' | 'project'
}

/** 规则总览（全局 + 当前项目）。 */
export interface RuleBundle {
  /** 存储形态：single-file（LUCKY.md 分节） / multi-file（rules 目录）。 */
  storage: 'single-file' | 'multi-file'
  global: RuleItem[]
  project: RuleItem[]
  /** 全局规则文件路径。 */
  globalPath: string
  /** 项目规则文件路径（未选工作空间时为空串）。 */
  projectPath: string
}

/** Agent 预设（null 字段 = 沿用 application.yml 默认值）。 */
export interface AgentPreset {
  /** ACT 阶段最大步数。 */
  maxSteps?: number | null
  /** 是否启用多 Agent / 子代理。 */
  subagentEnabled?: boolean | null
  /** 子代理并行上限。 */
  subagentMaxConcurrency?: number | null
  /** 是否启用客观验证链。 */
  verificationEnabled?: boolean | null
  /** 上下文压缩触发阈值（0~1）。 */
  contextThreshold?: number | null
  /** 失败步骤自动重试。 */
  autoRetry?: boolean | null
  /** 自定义系统提示词段。 */
  systemPrompt?: string | null
}

/** 预设总览：用户配置 + 合成后的生效值 + 覆盖来源标记。 */
export interface AgentPresetBundle {
  preset: AgentPreset
  /** 与 yml 合成后的最终生效值（设置页展示「当前生效值」）。 */
  effective: {
    maxSteps: number
    subagentEnabled: boolean
    subagentMaxConcurrency: number
    verificationEnabled: boolean
    contextThreshold: number
    autoRetry: boolean
    orchestratorMode: string
  }
  /** 各字段是否由用户自定义（true=已覆盖 yml）。 */
  overridden: Record<string, boolean>
}

/* ---------------- 工作流（agent-workflow 模块） ---------------- */

export type WorkflowNodeType = 'START' | 'END' | 'LLM' | 'TOOL' | 'CONDITION' | 'CODE' | 'SUBFLOW'

/** 输入连接器：从全局/上游作用域取值，写入当前节点输入参数。 */
export interface InputMapping {
  /** 源表达式，支持点路径（如 nodeA.result）或带引号的字面量。 */
  source: string
  /** 目标参数名（写入节点输入作用域）。 */
  target: string
}

/** 输出连接器：把节点输出字段写回工作流全局作用域。 */
export interface OutputMapping {
  source: string
  target: string
}

export interface WorkflowNodeDef {
  id: string
  type: WorkflowNodeType
  name?: string
  /**
   * 节点参数（按 type 决定键名，由后端 /api/workflows/node-types 下发定义）：
   * LLM=prompt/system、TOOL=toolName/params、CONDITION=condition、
   * CODE=command/timeoutMs/failOnError、SUBFLOW=subWorkflowId。
   */
  config?: Record<string, any>
  /**
   * 画布坐标（可视化元数据，引擎不读）。
   * 为空表示尚无人工布局，前端按拓扑自动分层摆位；用户拖动后会随定义一起落盘。
   */
  position?: { x: number; y: number } | null
  /** 输入连接器（可选，画布连线以外显式声明变量映射时使用）。 */
  inputs?: InputMapping[]
  /** 输出连接器（可选）。 */
  outputs?: OutputMapping[]
}

export interface WorkflowEdgeDef {
  /** 边 id；未提供时后端按 `source->target` 补齐。 */
  id?: string
  source: string
  target: string
  /** 条件表达式：非空时仅当求值为真才走此边（实现分支路由）。 */
  condition?: string
  /** 条件边标签（可视化提示用）。 */
  label?: string
}

export interface WorkflowTriggerDef {
  type: 'MANUAL' | 'INTERVAL' | 'WEBHOOK'
  enabled: boolean
  /** 间隔触发周期（毫秒，INTERVAL 类型用）。 */
  intervalMs?: number
}

export interface WorkflowDef {
  id: string
  name: string
  description?: string
  version?: number
  nodes: WorkflowNodeDef[]
  edges: WorkflowEdgeDef[]
  trigger?: WorkflowTriggerDef
  enabled: boolean
  createdAt?: number
  updatedAt?: number
}

/** 单个 config 字段定义（后端 NodeTypeCatalog 下发，前端据此动态渲染表单）。 */
export interface WorkflowConfigField {
  /** 写入 NodeDef.config 的键名。 */
  key: string
  label: string
  /** 控件类型：TEXT / TEXTAREA / NUMBER / BOOLEAN / SELECT / JSON。 */
  input: 'TEXT' | 'TEXTAREA' | 'NUMBER' | 'BOOLEAN' | 'SELECT' | 'JSON'
  required: boolean
  placeholder?: string
  hint?: string
  options?: { value: string; label: string }[]
  defaultValue?: any
}

/** 节点类型元数据（画布组件面板 + 属性表单的数据源）。 */
export interface WorkflowNodeTypeMeta {
  type: WorkflowNodeType
  label: string
  description: string
  /** 分组：structure / model / action / logic / composite。 */
  category: string
  /** 是否全流程唯一（START）。 */
  singleton: boolean
  /** 是否为流程必需节点（START / END）。 */
  required: boolean
  fields: WorkflowConfigField[]
}

export type WorkflowInstanceStatus = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SUSPENDED'

/** 后端 VariableScope 的序列化形态：变量统一挂在 data 下。 */
export interface VariableScope {
  data?: Record<string, any>
}

export type WorkflowNodeStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED' | 'WAITING'

export interface WorkflowNodeInstance {
  nodeId: string
  nodeName?: string
  type?: WorkflowNodeType
  status?: WorkflowNodeStatus
  /** 节点入参快照（VariableScope）。 */
  input?: VariableScope
  /** 节点产出快照（VariableScope）。 */
  output?: VariableScope
  error?: string
  startedAt?: number
  endedAt?: number
}

export interface WorkflowInstance {
  instanceId: string
  workflowId: string
  workflowName?: string
  status: WorkflowInstanceStatus
  /** 工作流级作用域最终快照。 */
  variables?: VariableScope
  nodeInstances?: Record<string, WorkflowNodeInstance>
  startedAt?: number
  endedAt?: number
  currentNodeId?: string
  error?: string
}



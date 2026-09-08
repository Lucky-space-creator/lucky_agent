// 与后端契约定义（resources/file/契约定义.md）对齐的类型

export interface SessionRef {
  sessionId: string
  userId: string
  workspaceId: string
}

export type AgentEventType =
  | 'thought'
  | 'content_delta'
  | 'action'
  | 'tool_result'
  | 'skill_invoke'
  | 'mcp_invoke'
  | 'task_plan'
  | 'task_progress'
  | 'ask'
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


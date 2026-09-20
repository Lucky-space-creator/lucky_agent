import { http } from './http'
import type {
  ExecResult,
  FileOp,
  InferenceDepth,
  McpServerDef,
  McpServerView,
  ModelConfig,
  ModelUsage,
  PermissionRule,
  ProbeResult,
  SessionMetrics,
  SessionSnapshot,
  SkillDef,
  SystemOpenResult,
  WorkflowDef,
  WorkflowInstance,
  Workspace,
} from './types'

/** 对话接口 */
export const chatApi = {
  submit(input: {
    sessionId?: string
    userId: string
    workspaceId: string
    content: string
    extra?: Record<string, any>
    /** 用户选定的模型端点 id（空则后端回退主端点）。 */
    modelId?: string | null
  }) {
    return http.post<{ sessionId: string; accepted: boolean }>('/api/chat/messages', input)
  },
  snapshot(session: { sessionId: string; userId: string; workspaceId: string }) {
    const q = new URLSearchParams(session).toString()
    return http.get<SessionSnapshot>(`/api/chat/${session.sessionId}/snapshot?${q}`)
  },
  history(session: { sessionId: string; userId: string; workspaceId: string }) {
    const q = new URLSearchParams(session).toString()
    return http.get<SessionSnapshot>(`/api/chat/${session.sessionId}/history?${q}`)
  },
  sessions(userId: string) {
    return http.get<SessionMeta[]>(`/api/chat/sessions?userId=${encodeURIComponent(userId)}`)
  },
  /** 新建空白会话（元数据落盘，支持多个新会话共存）。 */
  createSession(input: { userId: string; workspaceId: string; title?: string }) {
    return http.post<SessionMeta>('/api/chat/sessions', input)
  },
  /** 更新会话元数据（重命名 / 切换工作空间；缺省字段保持原值）。 */
  updateSession(input: { sessionId: string; userId: string; workspaceId?: string; title?: string }) {
    return http.put<SessionMeta>(`/api/chat/${input.sessionId}`, {
      userId: input.userId,
      workspaceId: input.workspaceId,
      title: input.title,
    })
  },
  /** 消息级回溯：撤销某条助手消息执行期间修改过的文件。 */
  rollbackMessage(input: { sessionId: string; workspaceId: string; messageTs: string }) {
    return http.post<{ restored: number }>(`/api/chat/${input.sessionId}/rollback`, {
      workspaceId: input.workspaceId,
      messageTs: input.messageTs,
    })
  },
  /** 回滚到消息节点：截断该消息之后的全部对话上下文（区别于消息级「撤销文件修改」）。 */
  rollbackToNode(input: { sessionId: string; messageTs: string }) {
    return http.post<{ removed: number; busy: boolean }>(`/api/chat/${input.sessionId}/rollback-node`, {
      messageTs: input.messageTs,
    })
  },
  destroy(session: { sessionId: string; userId: string }) {
    const q = new URLSearchParams({ userId: session.userId }).toString()
    return http.del<{ removed: boolean }>(`/api/chat/${session.sessionId}?${q}`)
  },
  cancel(session: { sessionId: string; userId: string; workspaceId: string }) {
    const q = new URLSearchParams({ userId: session.userId, workspaceId: session.workspaceId }).toString()
    return http.post<void>(`/api/chat/${session.sessionId}/cancel?${q}`, undefined)
  },
  /** 轮询会话实时指标（透明面板数据源）。 */
  metrics(session: { sessionId: string }) {
    return http.get<SessionMetrics>(`/api/chat/${session.sessionId}/metrics`)
  },
}

export interface SessionMeta {
  sessionId: string
  userId: string
  workspaceId: string
  title: string
  createdAt: number
  updatedAt: number
}

/** 工作区接口 */
export const workspaceApi = {
  list: () => http.get<Workspace[]>('/api/workspaces'),
  create: (input: { name: string; path: string; permissionLevel: string }) =>
    http.post<Workspace>('/api/workspaces', input),
  updatePermission: (workspaceId: string, level: string) =>
    http.put<Workspace>(`/api/workspaces/${workspaceId}/permission`, { level }),
  remove: (workspaceId: string) => http.del<{ removed: boolean }>(`/api/workspaces/${workspaceId}`),
}

/** 文件接口 */
export const fileApi = {
  op: (op: FileOp) => http.post<ExecResult>('/api/files/op', op),
  list: (workspaceId: string, path = '') =>
    http.get<ExecResult>(`/api/files/list?workspaceId=${encodeURIComponent(workspaceId)}&path=${encodeURIComponent(path)}`),
  stat: (workspaceId: string, path: string) =>
    http.get<ExecResult>(`/api/files/stat?workspaceId=${encodeURIComponent(workspaceId)}&path=${encodeURIComponent(path)}`),
}

/** 模型接入接口 */
export const modelApi = {
  list: () => http.get<ModelConfig[]>('/api/models'),
  save: (config: ModelConfig) => http.post<ModelConfig>('/api/models', config),
  remove: (id: string) => http.del<{ removed: boolean }>(`/api/models/${id}`),
  /** 读取指定端点明文 API Key（仅编辑弹窗回显用，列表接口不携带 Key）。 */
  key: (id: string) => http.get<{ id: string; apiKey: string }>(`/api/models/${id}/key`),
  /** 指定端点用量统计（模型详情小窗数据源；无调用记录返回空统计）。 */
  usage: (id: string) => http.get<ModelUsage>(`/api/models/${id}/usage`),
  /** 全部端点用量统计（列表角标/汇总视图数据源）。 */
  usages: () => http.get<Record<string, ModelUsage>>('/api/models/usage'),
  /** 探活：空参探活全部启用端点；{id} 探活指定端点；完整配置测试未保存的表单。 */
  probe: (body?: ModelConfig | { id: string } | null) =>
    http.post<{ ok: boolean; results: ProbeResult[] }>('/api/models/probe', body ?? undefined),
  /** 全局推理深度（枚举名，作用于模型请求）。 */
  depth: () => http.get<{ depth: InferenceDepth }>('/api/models/depth'),
  saveDepth: (depth: InferenceDepth) => http.put<{ depth: InferenceDepth }>('/api/models/depth', { depth }),
}

/** 权限接口 */
export const permissionApi = {
  rules: () => http.get<PermissionRule[]>('/api/permission/rules'),
  saveRules: (rules: PermissionRule[]) => http.put<{ count: number }>('/api/permission/rules', rules),
  level: (workspaceId: string) =>
    http.get<{ code: string; label: string }>(`/api/permission/workspaces/${workspaceId}/level`),
  confirm: (op: { opType: string; workspaceId: string; path: string }) =>
    http.post<{ ok: boolean }>('/api/permission/confirm', op),
}

/** MCP 管理接口 */
export const mcpApi = {
  list: () => http.get<McpServerView[]>('/api/mcp'),
  save: (def: Partial<McpServerView>) => http.post<McpServerView>('/api/mcp', def),
  remove: (id: string) => http.del<{ removed: boolean }>(`/api/mcp/${id}`),
  setEnabled: (id: string, enabled: boolean) =>
    http.post<{ enabled: boolean }>(`/api/mcp/${id}/enabled`, { enabled }),
  authorize: (id: string) => http.post<{ authorized: boolean }>(`/api/mcp/${id}/authorize`, undefined),
  revoke: (id: string) => http.post<{ revoked: boolean }>(`/api/mcp/${id}/revoke`, undefined),
  reconnect: (id: string) => http.post<{ status: string }>(`/api/mcp/${id}/reconnect`, undefined),
  /** 本地上传 MCP Server 包（.zip 或 meta.json）。 */
  upload: (file: File) => {
    const fd = new FormData()
    fd.append('file', file)
    return http.post<McpServerDef>('/api/mcp/upload', fd)
  },
}

/** Skill 管理接口 */
export const skillApi = {
  list: () => http.get<SkillDef[]>('/api/skills'),
  save: (def: Partial<SkillDef>) => http.post<SkillDef>('/api/skills', def),
  remove: (id: string) => http.del<{ removed: boolean }>(`/api/skills/${id}`),
  setEnabled: (id: string, enabled: boolean) =>
    http.post<{ enabled: boolean }>(`/api/skills/${id}/enabled`, { enabled }),
  reload: () => http.post<{ count: number }>('/api/skills/reload', undefined),
  /** 本地上传 Skill 包（.zip / meta.json / SKILL.md；支持单包批量导入）。 */
  upload: (file: File) => {
    const fd = new FormData()
    fd.append('file', file)
    return http.post<{ imported: string[]; count: number }>('/api/skills/upload', fd)
  },
  /** 从本机目录导入 Skill（目录名即 id，须含 meta.json 或 SKILL.md）。 */
  importDir: (path: string) => http.post<SkillDef>('/api/skills/import-dir', { path }),
}

/** 系统本地接口 */
export const systemApi = {
  openSettingsFile: () => http.post<SystemOpenResult>('/api/system/open-settings-file', undefined),
  /** 打开规则文件：不传 workspaceId 打开全局规则（框架根 LUCKY.md），传入则打开该工作空间的项目规则。 */
  openRulesFile: (workspaceId?: string) =>
    http.post<SystemOpenResult>('/api/system/open-rules-file', workspaceId ? { workspaceId } : undefined),
  /** 唤起本机原生目录选择框（后端代选），用户取消返回 cancelled=true。 */
  pickDirectory: (startPath?: string, title?: string) =>
    http.post<{ path: string; cancelled: boolean; message?: string }>('/api/system/pick-directory', {
      startPath,
      title,
    }),
  /** 浏览本机目录，返回子目录列表（供前端目录浏览器逐层导航）。 */
  browse: (path?: string) =>
    http.get<{ path: string; parent: string | null; dirs: { name: string; path: string }[] }>(
      `/api/system/browse${path ? `?path=${encodeURIComponent(path)}` : ''}`,
    ),
}

/** 隐私接口（本机单人使用，无账号体系） */
export const privacyApi = {
  clearMemory: (userId: string) =>
    http.post<{ ok: boolean }>(`/api/privacy/clear-memory?userId=${encodeURIComponent(userId)}`, undefined),
}

/** 工作流接口（agent-workflow 模块，后端 WorkflowController 已落地） */
export const workflowApi = {
  /** 列出全部工作流定义。 */
  list: () => http.get<WorkflowDef[]>('/api/workflows'),
  /** 获取单个工作流定义。 */
  get: (id: string) => http.get<WorkflowDef>(`/api/workflows/${id}`),
  /** 创建/更新工作流定义（后端校验：必须含 1 个 START + 至少 1 个 END 节点）。 */
  save: (def: Partial<WorkflowDef>) =>
    def.id ? http.put<WorkflowDef>(`/api/workflows/${def.id}`, def) : http.post<WorkflowDef>('/api/workflows', def),
  remove: (id: string) => http.del<{ removed: boolean }>(`/api/workflows/${id}`),
  /** 启停工作流。 */
  setEnabled: (id: string, enabled: boolean) =>
    http.post<WorkflowDef>(`/api/workflows/${id}/enabled?enabled=${enabled}`, {}),
  /** 结构校验（DAG/环检测）。 */
  validate: (def: Partial<WorkflowDef>) =>
    http.post<{ valid: boolean; startNodeId?: string; nodeCount?: number; topologicalOrder?: string[] }>(
      '/api/workflows/validate',
      def,
    ),
  /** 触发执行（mode=sync 等待返回实例；variables 为节点入参）。 */
  trigger: (id: string, variables?: Record<string, any>) =>
    http.post<WorkflowInstance>(`/api/workflows/${id}/trigger`, { variables: variables ?? {}, mode: 'sync' }),
  /** 运行实例列表。 */
  instances: () => http.get<WorkflowInstance[]>('/api/workflows/instances'),
  /** 单个运行实例详情。 */
  instance: (instanceId: string) => http.get<WorkflowInstance>(`/api/workflows/instances/${instanceId}`),
}

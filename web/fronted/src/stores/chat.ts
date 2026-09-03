import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { chatApi, modelApi, type SessionMeta } from '@/api'
import { connectSse } from '@/api/sse'
import type { AgentEvent, SessionMetrics, SessionRef } from '@/api/types'
import { useWorkspaceStore } from './workspace'
import { useModelStore } from './model'

export interface ToolCallView {
  id: string
  tool: string
  args: Record<string, any>
  status: 'running' | 'done'
  ok?: boolean
  summary?: string
  error?: string
}

export interface TaskPlanView {
  taskId: string
  title: string
  status: 'pending' | 'running' | 'done' | 'failed' | 'ask'
}

export interface AskView {
  question: string
  risk?: string
  op?: { opType: string; path: string; args: Record<string, any> }
}

export interface ChatItem {
  id: string
  role: 'user' | 'assistant'
  content: string
  ts: string
  status: 'running' | 'done' | 'error'
  error?: string
  thoughts: string[]
  toolCalls: ToolCallView[]
  plan: TaskPlanView[] | null
  ask?: AskView
  tokenUsed?: number
  model?: string
}

export interface ChatSession {
  id: string
  title: string
  workspaceId: string
  createdAt: number
}

const uid = () => Math.random().toString(36).slice(2, 10) + Date.now().toString(36)

/**
 * 本机单人使用，无账号概念：用户标识固定为此常量，前后端共用。
 * 记忆目录、会话列表、会话落盘均按此值分片，改名/换设备都不会漂移。
 */
export const LOCAL_USER_ID = 'local-user'

function userId(): string {
  return LOCAL_USER_ID
}

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<ChatSession[]>([])
  const currentSessionId = ref<string | null>(null)
  const messages = ref<ChatItem[]>([])
  const running = ref(false)
  const error = ref<string | null>(null)
  const loaded = ref(false)

  /** 透明面板实时指标（P3）。 */
  const metrics = ref<SessionMetrics | null>(null)
  let metricsTimer: ReturnType<typeof setInterval> | null = null

  let activeClose: (() => void) | null = null

  const currentSession = computed(() => sessions.value.find((s) => s.id === currentSessionId.value) ?? null)

  function closeActiveStream() {
    if (activeClose) {
      activeClose()
      activeClose = null
    }
  }

  /** 停止指标轮询。 */
  function stopMetrics() {
    if (metricsTimer) {
      clearInterval(metricsTimer)
      metricsTimer = null
    }
  }

  /** 拉取一次会话指标（供立即刷新与收尾补拉）。 */
  async function refreshMetrics(sessionId: string) {
    try {
      metrics.value = await chatApi.metrics({ sessionId })
    } catch {
      // 轮询失败静默，保持上次值
    }
  }

  /** 启动指标轮询（运行期每 1.5s 拉取一次会话指标；先立即拉一次，避免任务 <1.5s 时面板空白）。 */
  function startMetrics(sessionId: string) {
    stopMetrics()
    metrics.value = null
    refreshMetrics(sessionId)
    metricsTimer = setInterval(() => {
      refreshMetrics(sessionId)
    }, 1500)
  }

  function sessionExists(id: string): boolean {
    return sessions.value.some((s) => s.id === id)
  }

  /** 从后端加载会话列表（启动时）。 */
  async function loadSessions() {
    try {
      const metas = await chatApi.sessions(userId())
      sessions.value = metas.map((m: SessionMeta) => ({
        id: m.sessionId,
        title: m.title || '对话',
        workspaceId: m.workspaceId,
        createdAt: m.createdAt,
      }))
      loaded.value = true
    } catch {
      loaded.value = true
    }
  }

  /** 加载会话历史（选择会话时）。历史端点仅依赖 sessionId，workspaceId 为空也尝试加载。 */
  async function loadHistory(id: string) {
    const ws = useWorkspaceStore()
    const workspaceId = ws.current?.workspaceId ?? ws.workspaces[0]?.workspaceId ?? ''
    closeActiveStream()
    currentSessionId.value = id
    try {
      const snap = await chatApi.history({ sessionId: id, userId: userId(), workspaceId })
      // 最新消息在后，按存储顺序映射即可；加载完成后由视图滚动到底部
      messages.value = (snap.messages ?? []).map((m) => ({
        id: uid(),
        role: m.role === 'user' ? 'user' : 'assistant',
        content: m.content ?? '',
        ts: m.ts ?? '',
        status: 'done' as const,
        thoughts: [],
        toolCalls: [],
        plan: null,
      }))
    } catch {
      messages.value = []
    }
    error.value = null
  }

  function ensureSession(): string {
    if (currentSessionId.value && sessionExists(currentSessionId.value)) {
      return currentSessionId.value
    }
    const session: ChatSession = { id: uid(), title: '新会话', workspaceId: '', createdAt: Date.now() }
    sessions.value.unshift(session)
    currentSessionId.value = session.id
    messages.value = []
    error.value = null
    return session.id
  }

  async function newSession() {
    closeActiveStream()
    const session: ChatSession = { id: uid(), title: '新会话', workspaceId: '', createdAt: Date.now() }
    sessions.value.unshift(session)
    currentSessionId.value = session.id
    messages.value = []
    error.value = null
  }

  async function selectSession(id: string) {
    if (id === currentSessionId.value) return
    await loadHistory(id)
  }

  async function removeSession(id: string) {
    closeActiveStream()
    try {
      await chatApi.destroy({ sessionId: id, userId: userId() })
    } catch {
      // 忽略删除失败
    }
    sessions.value = sessions.value.filter((s) => s.id !== id)
    if (currentSessionId.value === id) {
      currentSessionId.value = sessions.value[0]?.id ?? null
      messages.value = []
      if (currentSessionId.value) {
        await loadHistory(currentSessionId.value)
      }
    }
  }

  /**
   * 发送前探活：探测全部启用端点，无可用端点时返回拦截原因（null 表示放行）。
   * 探活接口本身异常时放行（交由实际模型调用暴露错误），避免误伤。
   */
  async function sendGate(): Promise<string | null> {
    const modelStore = useModelStore()
    if (modelStore.configs.length === 0) {
      return null
    }
    try {
      // 只对「实际会生效的端点」放行：显式选中优先，否则主端点
      const active = modelStore.active
      if (!active?.id) return null
      const res = await modelApi.probe({ id: active.id })
      const r = res.results?.[0]
      if (!r || r.healthy) {
        return null
      }
      return `当前模型端点「${r.name || active.name || '未命名'}」不可用：${r.message ?? '探活失败'}。请前往「设置 → 模型」测试连接。`
    } catch {
      return null
    }
  }

  /** 提交用户输入（异步：POST 立即返回，事件经 SSE 驱动收尾）。 */
  async function submit(content: string, extra?: Record<string, any>) {
    const ws = useWorkspaceStore()
    // 普通对话无需强制选择工作空间：有当前工作空间用当前，否则用默认（第一个）或空串
    const workspaceId = ws.current?.workspaceId ?? ws.workspaces[0]?.workspaceId ?? ''
    const sessionId = ensureSession()
    const session = sessions.value.find((s) => s.id === sessionId)
    if (session && (session.title === '新会话' || session.title === '对话')) {
      session.title = content.slice(0, 24)
    }

    messages.value.push({
      id: uid(),
      role: 'user',
      content,
      ts: new Date().toISOString(),
      status: 'done',
      thoughts: [],
      toolCalls: [],
      plan: null,
    })

    // 发送前探活：无可用模型端点时内联拦截，不进入 SSE/POST
    const gateMsg = await sendGate()
    if (gateMsg) {
      messages.value.push({
        id: uid(),
        role: 'assistant',
        content: '',
        ts: new Date().toISOString(),
        status: 'error',
        error: gateMsg,
        thoughts: [],
        toolCalls: [],
        plan: null,
      })
      return
    }

    const assistant: ChatItem = {
      id: uid(),
      role: 'assistant',
      content: '',
      ts: new Date().toISOString(),
      status: 'running',
      thoughts: [],
      toolCalls: [],
      plan: null,
    }
    messages.value.push(assistant)

    running.value = true
    error.value = null
    closeActiveStream()

    const ref: SessionRef = {
      sessionId,
      userId: userId(),
      workspaceId,
    }

    /**
     * 事件回写必须作用到 messages 里的响应式代理，而不是上面那个原始对象。
     * ref 数组是深层响应式的：push 进数组后，数组内存放的是原始对象，读取时才包成 Proxy。
     * 若直接改原始对象，属性值虽然变了，却不会触发依赖通知，界面会停在「空气泡」不刷新。
     */
    const assistantIndex = messages.value.length - 1
    const liveItem = (): ChatItem | null => messages.value[assistantIndex] ?? null

    activeClose = connectSse(ref, {
      onEvent: (e) => {
        const item = liveItem()
        if (item) applyEvent(item, e)
        if (e.type === 'stop' || e.type === 'error') {
          running.value = false
          // 收尾补拉一次最终指标（轮次/步骤/消耗），保证面板停留在真实终态
          refreshMetrics(sessionId)
          stopMetrics()
          if (activeClose) {
            activeClose()
            activeClose = null
          }
        }
      },
      onError: (m) => {
        // 连接失败直接落在消息内提示，避免只出现在全局（无渲染）里
        const item = liveItem()
        if (item) {
          item.status = 'error'
          item.error = m
        }
        error.value = m
        running.value = false
        stopMetrics()
      },
    })

    startMetrics(sessionId)
    try {
      // 带上用户在模型选择器里选定的端点，否则后端固定走主端点、界面选择形同虚设
      const modelStore = useModelStore()
      await chatApi.submit({
        sessionId,
        userId: ref.userId,
        workspaceId: ref.workspaceId,
        content,
        extra,
        modelId: modelStore.selectedId,
      })
      // 后端接受即返回；最终结果由 stop/error 事件驱动
    } catch (e) {
      const item = liveItem()
      if (item) {
        item.status = 'error'
        item.error = (e as Error).message
      }
      error.value = (e as Error).message
      running.value = false
      refreshMetrics(sessionId)
      stopMetrics()
      closeActiveStream()
    }
  }

  async function cancel() {
    const ws = useWorkspaceStore()
    const sessionId = currentSessionId.value
    if (sessionId && ws.current) {
      try {
        await chatApi.cancel({ sessionId, userId: userId(), workspaceId: ws.current.workspaceId })
      } catch {
        // 忽略取消失败
      }
    }
    running.value = false
    stopMetrics()
    closeActiveStream()
    const last = messages.value[messages.value.length - 1]
    if (last && last.status === 'running') {
      last.status = 'error'
      last.error = '已取消'
    }
  }

  /** 危险操作确认的展示名：映射 opType → 工具名。 */
  function opToTool(opType: string | undefined): string {
    return {
      WRITE: 'file.write',
      DELETE: 'file.delete',
      EXEC: 'shell.exec',
      RENAME: 'file.rename',
      MKDIR: 'file.mkdir',
    }[opType ?? ''] ?? 'file.op'
  }

  /**
   * 用户确认高危操作：确认选择立即转为「调用结果」卡片展示（不再悬浮问句）。
   * 允许 → 原地续跑放行执行；拒绝 → 发真实反馈让模型调整方案。
   */
  function confirmAsk(item: ChatItem, allow: boolean) {
    if (!item.ask?.op) return
    const op = item.ask.op
    // 立即消除确认问句悬浮，把选择作为一次工具调用结果记录到该条消息
    item.ask = undefined
    const opType = (op as { opType?: string })?.opType
    item.toolCalls.push({
      id: 'confirm_' + uid(),
      tool: opToTool(opType),
      args: (op?.args ?? {}) as Record<string, any>,
      status: allow ? 'running' : 'done',
      ok: allow,
      summary: allow ? '用户已确认执行' : '用户已拒绝',
    })
    if (allow) {
      resumeAfterConfirm(item, op)
    } else {
      submit('用户拒绝了该操作，请调整方案或说明。')
    }
  }

  /**
   * 确认后原地续跑：复用当前 assistant 消息，清除 ask 卡片并转为运行中，
   * 建立 SSE 接收续跑结果；不追加/不显示新的用户消息（确认不是一次新对话）。
   */
  async function resumeAfterConfirm(item: ChatItem, confirmOp: Record<string, any>) {
    const ws = useWorkspaceStore()
    const sessionId = currentSessionId.value
    if (!sessionId) return
    const workspaceId = ws.current?.workspaceId ?? ws.workspaces[0]?.workspaceId ?? ''
    const idx = messages.value.findIndex((m) => m.id === item.id)
    if (idx < 0) return

    const assistant = messages.value[idx]
    assistant.ask = undefined
    assistant.error = undefined
    assistant.status = 'running'

    running.value = true
    error.value = null
    closeActiveStream()

    const ref: SessionRef = {
      sessionId,
      userId: userId(),
      workspaceId,
    }
    const liveItem = (): ChatItem | null => messages.value[idx] ?? null

    activeClose = connectSse(ref, {
      onEvent: (e) => {
        const it = liveItem()
        if (it) applyEvent(it, e)
        if (e.type === 'stop' || e.type === 'error') {
          // 收尾：把确认时插入的「正在执行」卡片标记为完成，作为调用结果展示
          const m = liveItem()
          if (m) {
            for (const tc of m.toolCalls) {
              if (tc.id.startsWith('confirm_') && tc.status === 'running') {
                tc.status = 'done'
                tc.ok = e.type === 'stop'
                tc.summary = e.type === 'stop' ? '已执行完成' : '执行失败或取消'
              }
            }
          }
          running.value = false
          refreshMetrics(sessionId)
          stopMetrics()
          if (activeClose) {
            activeClose()
            activeClose = null
          }
        }
      },
      onError: (m) => {
        const it = liveItem()
        if (it) {
          it.status = 'error'
          it.error = m
        }
        error.value = m
        running.value = false
        stopMetrics()
      },
    })

    startMetrics(sessionId)
    try {
      const modelStore = useModelStore()
      await chatApi.submit({
        sessionId,
        userId: ref.userId,
        workspaceId: ref.workspaceId,
        content: '',
        extra: { confirm: confirmOp },
        modelId: modelStore.selectedId,
      })
    } catch (e) {
      const it = liveItem()
      if (it) {
        it.status = 'error'
        it.error = (e as Error).message
      }
      error.value = (e as Error).message
      running.value = false
      refreshMetrics(sessionId)
      stopMetrics()
      closeActiveStream()
    }
  }

  function applyEvent(item: ChatItem, event: AgentEvent) {
    const p = event.payload
    switch (event.type) {
      case 'thought': {
        if (p.content) {
          item.thoughts.push(String(p.content))
        }
        break
      }
      case 'content_delta': {
        // 最终回复正文流式增量：逐字追加到消息内容，前端实时渲染
        const delta = p.delta
        if (delta) {
          item.content = (item.content || '') + String(delta)
        }
        break
      }
      case 'action': {
        item.toolCalls.push({
          id: event.callId ?? uid(),
          tool: String(p.tool ?? 'tool'),
          args: (p.args ?? {}) as Record<string, any>,
          status: 'running',
        })
        break
      }
      case 'tool_result': {
        const call = item.toolCalls.find((c) => c.id === (event.callId ?? p.callId))
        if (call) {
          call.status = 'done'
          call.ok = Boolean(p.ok)
          call.summary = p.summary
          call.error = p.error
        }
        break
      }
      case 'task_plan': {
        if (Array.isArray(p.tasks)) {
          item.plan = (p.tasks as { taskId: string; title: string }[]).map((t) => ({
            taskId: t.taskId,
            title: t.title,
            status: 'pending',
          }))
        }
        break
      }
      case 'task_progress': {
        if (item.plan) {
          const t = item.plan.find((x) => x.taskId === p.taskId)
          if (t) t.status = String(p.status) as TaskPlanView['status']
        }
        break
      }
      case 'ask': {
        item.ask = {
          question: String(p.question ?? '操作需确认'),
          risk: p.risk,
          op: p.op as AskView['op'],
        }
        break
      }
      case 'skill_invoke': {
        // Skill 调用并入透明面板工具区展示
        item.toolCalls.push({
          id: event.callId ?? 'skill_' + uid(),
          tool: `skill.${p.skillId ?? p.skill ?? 'skill'}`,
          args: (p.args ?? {}) as Record<string, any>,
          status: 'done',
          ok: true,
          summary: p.summary,
        })
        break
      }
      case 'mcp_invoke': {
        item.toolCalls.push({
          id: event.callId ?? 'mcp_' + uid(),
          tool: `mcp.${p.serverId ?? p.server ?? 'mcp'}.${p.tool ?? 'tool'}`,
          args: (p.args ?? {}) as Record<string, any>,
          status: 'done',
          ok: true,
          summary: p.summary,
        })
        break
      }
      case 'token': {
        // 事件 used 为本次模型调用增量，消息维度累加展示（会话级总量见透明面板）
        item.tokenUsed = (item.tokenUsed ?? 0) + Number(p.used ?? 0)
        break
      }
      case 'stop': {
        if (p.summary) item.content = String(p.summary)
        item.status = 'done'
        break
      }
      case 'error': {
        item.status = 'error'
        item.error = String(p.msg ?? '运行出错')
        break
      }
      default:
        break
    }
  }

  return {
    sessions,
    currentSessionId,
    currentSession,
    messages,
    running,
    error,
    loaded,
    metrics,
    loadSessions,
    loadHistory,
    ensureSession,
    newSession,
    selectSession,
    removeSession,
    submit,
    cancel,
    confirmAsk,
  }
})

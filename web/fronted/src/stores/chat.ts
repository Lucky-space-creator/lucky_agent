import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { chatApi, modelApi, type SessionMeta } from '@/api'
import { connectSse } from '@/api/sse'
import type { AgentEvent, SessionMetrics, SessionRef } from '@/api/types'
import { useWorkspaceStore } from './workspace'
import { useModelStore } from './model'
import { useToastStore } from './toast'

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

/**
 * 会话级「执行面板」：聚合一次对话运行期间的执行过程（分析/规划/执行进度/工具调用/思考次数），
 * 悬浮于对话框上方可收缩展示；不再混入回复正文。
 */
export interface RunPanelView {
  /** 当前阶段（分析 / 规划 / 执行 / 验证 / 完成）。 */
  phase: string
  /** 一次 LLM 思考 = 1 条（后端已按轮聚合）。 */
  thoughts: string[]
  /** 系统执行进度/状态消息（分析判定、验证结果、安全阀等，不占思考计数）。 */
  logs: string[]
  toolCalls: ToolCallView[]
  plan: TaskPlanView[] | null
  asking: boolean
  done: boolean
}

export function emptyRunPanel(): RunPanelView {
  return { phase: '', thoughts: [], logs: [], toolCalls: [], plan: null, asking: false, done: false }
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
  /** 该消息执行期间产生的文件检查点 ID（可消息级回溯）。 */
  checkpointIds?: string[]
  /** 已执行过回溯（撤销入口置灰）。 */
  rolledBack?: boolean
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

  /** 会话级执行面板状态（悬浮展示执行过程，独立于回复正文）。 */
  const runPanel = ref<RunPanelView>(emptyRunPanel())

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

  /**
   * 收尾后从后端历史补齐各消息的文件检查点 ID。
   * SSE 不携带检查点信息，靠 content 对齐历史记录补齐（含确认续跑导致的历史多一条的场景）。
   */
  async function syncCheckpointIds(sessionId: string) {
    const ws = useWorkspaceStore()
    const workspaceId = ws.current?.workspaceId ?? ws.workspaces[0]?.workspaceId ?? ''
    try {
      const snap = await chatApi.history({ sessionId, userId: userId(), workspaceId })
      const histAssist = (snap.messages ?? []).filter((m) => m.role === 'assistant' && m.checkpointIds?.length)
      if (!histAssist.length) return
      const memAssist = messages.value.filter((m) => m.role === 'assistant' && !m.checkpointIds?.length)
      for (let i = memAssist.length - 1; i >= 0; i--) {
        const m = memAssist[i]
        if (!m.content) continue
        const rec = [...histAssist].reverse().find((h) => h.content === m.content)
        if (rec?.checkpointIds?.length) m.checkpointIds = rec.checkpointIds
      }
    } catch {
      // 同步失败静默：下次进入会话时会从历史补齐
    }
  }

  /** 消息级回溯：撤销该消息执行期间修改过的文件（成功后撤销入口置灰）。 */
  async function rollbackMessage(item: ChatItem) {
    const sessionId = currentSessionId.value
    if (!sessionId || !item.ts) return 0
    const ws = useWorkspaceStore()
    const session = sessions.value.find((s) => s.id === sessionId)
    const workspaceId = session?.workspaceId || ws.current?.workspaceId || ws.workspaces[0]?.workspaceId || ''
    const res = await chatApi.rollbackMessage({ sessionId, workspaceId, messageTs: item.ts })
    if (res.restored > 0) {
      item.rolledBack = true
      item.checkpointIds = []
    }
    return res.restored
  }

  /**
   * 回滚到消息节点：截断该消息之后的全部对话上下文。
   * 成功后从内存消息列表中删除该消息之后的所有项（服务端已同步截断磁盘与内存态）。
   * 区别于 rollbackMessage（仅撤销文件修改，不动上下文）。
   */
  async function rollbackToNode(item: ChatItem): Promise<{ removed: number; busy: boolean }> {
    const sessionId = currentSessionId.value
    if (!sessionId || !item.ts) return { removed: 0, busy: false }
    const toast = useToastStore()
    try {
      const res = await chatApi.rollbackToNode({ sessionId, messageTs: item.ts })
      if (res.removed > 0) {
        const idx = messages.value.findIndex((m) => m.id === item.id)
        if (idx >= 0) messages.value.splice(idx + 1)
        toast.success(`已回滚到该节点，删除 ${res.removed} 条后续消息`)
      } else if (res.busy) {
        toast.error('会话正在运行中，无法回滚，请等待完成')
      }
      return res
    } catch {
      toast.error('回滚失败，请重试')
      return { removed: 0, busy: false }
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
        checkpointIds: m.checkpointIds?.length ? m.checkpointIds : undefined,
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
    // 复用已有的未使用新会话（与 newSession 的「只存留一个新会话」约束一致）
    const pending = sessions.value.find((s) => s.title === '新会话')
    if (pending) {
      currentSessionId.value = pending.id
      messages.value = []
      error.value = null
      return pending.id
    }
    const ws = useWorkspaceStore()
    const workspaceId = ws.current?.workspaceId ?? ws.workspaces[0]?.workspaceId ?? ''
    const session: ChatSession = { id: uid(), title: '新会话', workspaceId, createdAt: Date.now() }
    sessions.value.unshift(session)
    currentSessionId.value = session.id
    messages.value = []
    error.value = null
    return session.id
  }

  /**
   * 新建会话：
   * - 不传 workspaceId（顶部「新对话」）→ 创建不绑定任何工作空间的空白会话，由用户稍后选择/绑定；
   * - 传 workspaceId（项目节点「＋」）→ 创建绑定该项目的会话。
   * 只允许存留一个「同一绑定」下未使用的新会话。
   */
  async function newSession(workspaceId?: string) {
    closeActiveStream()
    const effWs = workspaceId ?? ''
    // 只能存留一个同绑定下未使用的新会话：复用它而不是继续新建
    const pending = sessions.value.find((s) => s.title === '新会话' && (s.workspaceId ?? '') === effWs)
    if (pending) {
      currentSessionId.value = pending.id
      messages.value = []
      error.value = null
      return
    }
    let session: ChatSession
    try {
      const meta = await chatApi.createSession({ userId: userId(), workspaceId: effWs, title: '新会话' })
      session = {
        id: meta.sessionId,
        title: meta.title || '新会话',
        workspaceId: meta.workspaceId || effWs,
        createdAt: meta.createdAt || Date.now(),
      }
    } catch {
      session = { id: uid(), title: '新会话', workspaceId: effWs, createdAt: Date.now() }
    }
    sessions.value.unshift(session)
    currentSessionId.value = session.id
    messages.value = []
    error.value = null
  }

  /** 重命名会话。 */
  async function renameSession(id: string, title: string) {
    const s = sessions.value.find((x) => x.id === id)
    if (!s || !title.trim()) return
    const prev = s.title
    s.title = title.trim()
    try {
      const meta = await chatApi.updateSession({ sessionId: id, userId: userId(), title: s.title })
      if (meta.title) s.title = meta.title
    } catch {
      s.title = prev
    }
  }

  /**
   * 切换当前会话绑定的工作空间（同步工作空间 store 与后端元数据）。
   *
   * <p>已有对话记录的会话被后端锁定：切换被拒时回滚并返回 false（调用方提示用户）。</p>
   *
   * @returns 是否切换成功（false 表示该会话已有对话记录，工作空间被锁定）
   */
  async function setSessionWorkspace(workspaceId: string): Promise<boolean> {
    const ws = useWorkspaceStore()
    ws.setCurrent(workspaceId)
    const session = currentSession.value
    if (!session || session.workspaceId === workspaceId) {
      return true
    }
    const prev = session.workspaceId
    session.workspaceId = workspaceId
    try {
      const meta = await chatApi.updateSession({ sessionId: session.id, userId: userId(), workspaceId })
      if (meta.workspaceId && meta.workspaceId !== workspaceId) {
        // 后端拒绝（会话已有对话记录）：回滚并提示
        session.workspaceId = prev
        ws.setCurrent(prev)
        return false
      }
      return true
    } catch {
      session.workspaceId = prev
      ws.setCurrent(prev)
      return false
    }
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
    const sessionId = ensureSession()
    const session = sessions.value.find((s) => s.id === sessionId)
    // 会话绑定的工作空间优先（Composer 切换后即绑定），否则回退当前工作空间/默认
    const workspaceId =
      session?.workspaceId || ws.current?.workspaceId || ws.workspaces[0]?.workspaceId || ''
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
    // 新一轮运行：重置执行面板（悬浮于对话框上方，不混入回复正文）
    runPanel.value = emptyRunPanel()

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
          // 从后端历史补齐本轮产生的文件检查点，使刚完成的回复立即可回溯
          syncCheckpointIds(sessionId)
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
    runPanel.value.done = true
    runPanel.value.phase = '已取消'
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
    // 确认续跑也是新一轮执行：重置执行面板，避免残留上一轮进度
    runPanel.value = emptyRunPanel()

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
          syncCheckpointIds(sessionId)
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
        // 一次 LLM 思考 = 1 条（后端已按轮聚合 thinkTail）：
        // 计入执行面板思考次数、并入暂存的消息思考区，不当作回复正文
        if (p.content) {
          runPanel.value.thoughts.push(String(p.content))
        }
        break
      }
      case 'progress': {
        // 系统执行进度/状态消息（分析/验证/安全阀等）：只进执行面板日志，不占思考计数
        if (p.content) {
          runPanel.value.logs.push(String(p.content))
          const phase = phaseOf(String(p.content))
          if (phase) runPanel.value.phase = phase
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
        runPanel.value.toolCalls.push({
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
        const panelCall = runPanel.value.toolCalls.find((c) => c.id === (event.callId ?? p.callId))
        if (panelCall) {
          panelCall.status = 'done'
          panelCall.ok = Boolean(p.ok)
          panelCall.summary = p.summary
          panelCall.error = p.error
        }
        break
      }
      case 'task_plan': {
        if (Array.isArray(p.tasks)) {
          const plan: TaskPlanView[] = (p.tasks as { taskId: string; title: string }[]).map((t) => ({
            taskId: t.taskId,
            title: t.title,
            status: 'pending' as const,
          }))
          item.plan = plan
          runPanel.value.plan = [...plan]
        }
        break
      }
      case 'task_progress': {
        if (item.plan) {
          const t = item.plan.find((x) => x.taskId === p.taskId)
          if (t) t.status = String(p.status) as TaskPlanView['status']
        }
        if (runPanel.value.plan) {
          const t = runPanel.value.plan.find((x) => x.taskId === p.taskId)
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
        runPanel.value.asking = true
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
        runPanel.value.toolCalls.push({
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
        runPanel.value.toolCalls.push({
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
        runPanel.value.done = true
        runPanel.value.phase = p.reason === 'cancelled' ? '已取消' : '完成'
        break
      }
      case 'error': {
        item.status = 'error'
        item.error = String(p.msg ?? '运行出错')
        runPanel.value.done = true
        runPanel.value.phase = '出错'
        break
      }
      default:
        break
    }
  }

  /** 从系统进度消息推导阶段标签（供执行面板头部展示）。 */
  function phaseOf(log: string): string {
    if (log.startsWith('收到你的请求') || log.startsWith('已确认')) return '分析'
    if (log.includes('开始PLAN阶段')) return '规划'
    if (log.includes('开始ACT阶段') || log.includes('单 Agent 直接执行') || log.startsWith('【分析】拆分为')) return '执行'
    if (log.startsWith('【验证】')) return '验证'
    if (log.includes('安全阀') || log.startsWith('【复查】')) return '复查'
    if (log.includes('已取消')) return '已取消'
    return ''
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
    runPanel,
    loadSessions,
    loadHistory,
    ensureSession,
    newSession,
    renameSession,
    setSessionWorkspace,
    selectSession,
    removeSession,
    submit,
    cancel,
    confirmAsk,
    rollbackMessage,
    rollbackToNode,
  }
})

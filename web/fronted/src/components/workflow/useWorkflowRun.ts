/**
 * 工作流运行态：ASYNC 触发 + SSE 事件流 + 实例快照对齐。
 *
 * <p><b>为什么需要「快照 + 事件」两层而不是只用其中一层：</b></p>
 * <ul>
 *   <li>只用 SYNC 触发（改造前的做法）：HTTP 请求要等到整条工作流跑完才返回，
 *       前端全程只有一个转圈，看不到任何中间进度 —— 这是「执行界面不行」的根因。</li>
 *   <li>只用 SSE：后端事件总线是 `multicast` sink，订阅前发布的事件不补发；
 *       ASYNC 触发返回 instanceId 到浏览器建立连接之间存在真实窗口，
 *       工作流如果秒级完成，很可能一帧都收不到。</li>
 *   <li>只用轮询：节点状态可拿到，但状态跃迁的时刻会丢失（无法展示「正在跑哪个节点」的
 *       实时感），且必须把轮询间隔压到很低才够灵敏。</li>
 * </ul>
 *
 * <p><b>因此本模块的口径是：快照为事实源，事件为推进信号。</b>
 * 事件只提供「哪个节点刚发生了什么」，节点状态一律以
 * {@code GET /instances/{id}} 的快照为准；两者冲突时按状态单调性取更靠后者
 * （PENDING → RUNNING → COMPLETED/FAILED/SKIPPED 单调不可逆），
 * 因为快照可能比事件滞后一个 RTT。</p>
 *
 * <p>另外常开一条 2s 的安全轮询：即使事件流整体失效（代理缓冲、浏览器限制），
 * 运行推进与终态也能被发现。</p>
 */
import { computed, onBeforeUnmount, ref } from 'vue'
import { workflowApi } from '@/api'
import type { WorkflowEvent, WorkflowInstance, WorkflowNodeStatus } from '@/api/types'

/** 执行轨迹中的一行（一个节点）。 */
export interface RunTrackItem {
  nodeId: string
  name: string
  type?: string
  status: WorkflowNodeStatus
  startedAt?: number
  endedAt?: number
  /** 最近一条节点事件的描述（例如跳过原因，快照里没有这个信息）。 */
  message?: string
  error?: string
}

/** 下发给画布节点的运行标记。 */
export interface RunMark {
  status: WorkflowNodeStatus
  /** 已耗时（运行中为实时值）。 */
  ms?: number
}

/** 事件乐观态：节点在当前实例里的最新跃迁，作为快照滞后时的补偿。 */
interface NodeHint {
  status: WorkflowNodeStatus
  startedAt?: number
  endedAt?: number
  message?: string
}

/** 状态单调序：数值大者更靠后；终态并列（互斥，同档取快照）。 */
const RANK: Record<WorkflowNodeStatus, number> = {
  PENDING: 0,
  WAITING: 0,
  RUNNING: 1,
  COMPLETED: 2,
  FAILED: 2,
  SKIPPED: 2,
}

const REFRESH_COALESCE_MS = 250
const SAFETY_POLL_MS = 2000
const TICK_MS = 500

export interface UseWorkflowRunOptions {
  /** 当前工作流 id；未打开编辑器时为 undefined。 */
  workflowId: () => string | undefined
  /** 节点 id → 展示名（实例 nodeName 缺失时回落）。 */
  nameOf: (nodeId: string) => string
  /** 节点 id → 画布序号，用于「未开始节点」的稳定排序。 */
  orderOf?: (nodeId: string) => number
  /**
   * 定义中的全部节点 id（画布顺序）。
   *
   * <p>为什么要传：实例快照与事件流都只包含「已经进入过」的节点，若轨迹只按这两者取集合，
   * 运行中途分母会偏小 —— 一个 3 节点工作流跑了 1 个就显示「1/2 完成」，
   * 而画布上明明有 3 个节点（其中 1 个还标着待执行），两处数字对不上。
   * 把定义里的节点并进来，分母即为「这条工作流一共有几个节点」，与画布一致。</p>
   */
  defNodeIds?: () => string[]
}

export function useWorkflowRun(opts: UseWorkflowRunOptions) {
  /** 触发中的 HTTP 请求（触发本身，不含后续执行）。 */
  const triggering = ref(false)
  /** 最近一次快照里工作流是否仍在执行。 */
  const running = ref(false)
  /** 是否仍在主动跟踪（用户点「停止监控」后为 false，后台执行不受影响）。 */
  const watching = ref(false)
  const live = ref(false)
  /** 事件流是否已失效（降级为纯轮询）。 */
  const degraded = ref(false)
  /** 事件流连接态：本地实测建连约 0.8s，这段窗口既不能谎称「实时」也不能谎称「轮询」。 */
  const sseState = ref<'idle' | 'connecting' | 'live' | 'failed'>('idle')

  const instance = ref<WorkflowInstance | null>(null)
  const hints = ref<Record<string, NodeHint>>({})
  const workflowMessage = ref('')

  /** 供耗时展示的时钟（仅跟踪期间走秒）。 */
  const now = ref(Date.now())

  let instanceId: string | null = null
  let closeSse: (() => void) | null = null
  let coalesceTimer: ReturnType<typeof setTimeout> | null = null
  let safetyTimer: ReturnType<typeof setInterval> | null = null
  let tickTimer: ReturnType<typeof setInterval> | null = null
  let disposed = false

  // ---------------- 派生状态 ----------------

  const elapsedMs = computed(() => {
    const i = instance.value
    if (!i?.startedAt) return 0
    const end = i.endedAt && i.endedAt > 0 ? i.endedAt : now.value
    return Math.max(0, end - i.startedAt)
  })

  /**
   * 执行轨迹：节点集合 = 快照 ∪ 事件 ∪ 定义，顺序按 startedAt（未开始者垫底、按画布序）。
   *
   * <p>为什么不用快照的 Map 顺序：后端 {@code nodeInstances} 是
   * {@code ConcurrentHashMap}，序列化顺序不稳定，直接展示会出现轨迹乱跳。</p>
   *
   * <p>为什么并入定义节点：运行中途「尚未进入」的节点在快照与事件里都不存在，
   * 只按前两者取集合会让轨迹与分母随执行进度增长（3 节点工作流中途显示「1/2 完成」）。
   * 并入后轨迹自始就与画布一致：未开始者以 PENDING 呈现，分母恒为节点总数。</p>
   *
   * <p><b>仅在未终态时并入</b>：已结束的历史实例若也并入当前定义，就会显示
   * 「某个定义里后来才加的节点一直是待执行」；终态的轨迹应忠实反映当次实际走过的节点。</p>
   */
  const track = computed<RunTrackItem[]>(() => {
    const snap = instance.value
    const ni = snap?.nodeInstances ?? {}
    const settled = snap?.status === 'COMPLETED' || snap?.status === 'FAILED'
    const ids: string[] = []
    const seen = new Set<string>()
    const push = (id?: string | null) => {
      if (id && !seen.has(id)) {
        seen.add(id)
        ids.push(id)
      }
    }
    Object.keys(ni).forEach(push)
    Object.keys(hints.value).forEach(push)
    if (!settled) opts.defNodeIds?.().forEach(push)

    const items = ids.map<RunTrackItem>((id) => {
      const snap = ni[id]
      const hint = hints.value[id]
      const snapStatus = (snap?.status ?? 'PENDING') as WorkflowNodeStatus
      // 单调合并：事件领先（快照还没刷新）时采用事件态，其余一律信快照
      const status =
        hint && RANK[hint.status] > RANK[snapStatus] ? hint.status : snapStatus
      return {
        nodeId: id,
        name: snap?.nodeName || opts.nameOf(id) || id,
        type: snap?.type,
        status,
        startedAt: snap?.startedAt || hint?.startedAt,
        endedAt: snap?.endedAt || hint?.endedAt,
        message: hint?.message,
        error: snap?.error,
      }
    })

    const order = (id: string) => {
      const i = opts.orderOf?.(id) ?? -1
      return i < 0 ? Number.MAX_SAFE_INTEGER : i
    }
    return items.sort((a, b) => {
      const sa = a.startedAt || Number.MAX_SAFE_INTEGER
      const sb = b.startedAt || Number.MAX_SAFE_INTEGER
      if (sa !== sb) return sa - sb
      return order(a.nodeId) - order(b.nodeId)
    })
  })

  const counts = computed(() => {
    const c = { total: track.value.length, done: 0, failed: 0, skipped: 0, running: 0 }
    for (const t of track.value) {
      if (t.status === 'COMPLETED') c.done += 1
      else if (t.status === 'FAILED') c.failed += 1
      else if (t.status === 'SKIPPED') c.skipped += 1
      else if (t.status === 'RUNNING') c.running += 1
    }
    return c
  })

  /** 下发给画布节点的标记（运行中的节点耗时实时刷新）。 */
  const marks = computed<Record<string, RunMark>>(() => {
    const out: Record<string, RunMark> = {}
    for (const t of track.value) {
      out[t.nodeId] = { status: t.status, ms: durationOf(t) }
    }
    return out
  })

  /** 首个失败节点，供视图自动定位（省掉用户「找错在哪」的一步）。 */
  const firstFailedNodeId = computed(
    () => track.value.find((t) => t.status === 'FAILED')?.nodeId ?? null,
  )

  const mode = computed<'live' | 'connecting' | 'poll' | 'idle'>(() => {
    if (!watching.value) return 'idle'
    if (sseState.value === 'live') return 'live'
    if (sseState.value === 'failed') return 'poll'
    return 'connecting'
  })

  function durationOf(t: RunTrackItem): number | undefined {
    if (!t.startedAt) return undefined
    const end = t.endedAt && t.endedAt > 0 ? t.endedAt : now.value
    return Math.max(0, end - t.startedAt)
  }

  // ---------------- 内部：快照与事件 ----------------

  function applySnapshot(snap: WorkflowInstance) {
    instance.value = snap
    now.value = Date.now()
    const stillRunning = snap.status === 'RUNNING'
    running.value = stillRunning
    if (!stillRunning) settle()
  }

  function applyHint(e: WorkflowEvent) {
    const id = e.nodeId
    if (!id) return
    const at = e.timestamp || Date.now()
    const prev = hints.value[id]
    const next: NodeHint = {
      status: prev?.status ?? 'PENDING',
      startedAt: prev?.startedAt,
      endedAt: prev?.endedAt,
      message: prev?.message,
    }
    switch (e.type) {
      case 'NODE_STARTED':
        next.status = 'RUNNING'
        next.startedAt = at
        break
      case 'NODE_COMPLETED':
        next.status = 'COMPLETED'
        next.endedAt = at
        break
      case 'NODE_FAILED':
        next.status = 'FAILED'
        next.endedAt = at
        break
      case 'NODE_SKIPPED':
        next.status = 'SKIPPED'
        next.endedAt = at
        break
      default:
        // WORKFLOW_* / VARIABLE_UPDATED 不改节点态，仅记描述
        if (e.message) workflowMessage.value = e.message
        return
    }
    next.message = e.message ?? next.message
    hints.value = { ...hints.value, [id]: next }
    // 运行中的节点需要实时耗时
    now.value = Date.now()
  }

  async function refresh() {
    if (!instanceId) return
    try {
      applySnapshot(await workflowApi.instance(instanceId))
    } catch {
      // 拉快照失败不改本地状态：事件流与安全轮询都会再给机会
    }
  }

  function scheduleRefresh(delay = REFRESH_COALESCE_MS) {
    if (coalesceTimer) clearTimeout(coalesceTimer)
    coalesceTimer = setTimeout(() => {
      coalesceTimer = null
      void refresh()
    }, delay)
  }

  function subscribe(id: string) {
    closeSse?.()
    sseState.value = 'connecting'
    closeSse = workflowApi.events(id, {
      onOpen: () => {
        live.value = true
        degraded.value = false
        sseState.value = 'live'
      },
      onEvent: (e) => {
        live.value = true
        if (sseState.value !== 'live') sseState.value = 'live'
        applyHint(e)
        // 终端事件后引擎还要走 finally 落盘，稍等再拉最终快照
        scheduleRefresh(e.type.startsWith('WORKFLOW_') ? 120 : REFRESH_COALESCE_MS)
      },
      onError: () => {
        live.value = false
        degraded.value = true
        sseState.value = 'failed'
      },
    })
  }

  /** 停止一切跟踪动作（不影响后台执行）。 */
  function stopTimers() {
    if (coalesceTimer) {
      clearTimeout(coalesceTimer)
      coalesceTimer = null
    }
    if (safetyTimer) {
      clearInterval(safetyTimer)
      safetyTimer = null
    }
    if (tickTimer) {
      clearInterval(tickTimer)
      tickTimer = null
    }
    closeSse?.()
    closeSse = null
    live.value = false
    sseState.value = 'idle'
  }

  function settle() {
    watching.value = false
    stopTimers()
  }

  function startTimers() {
    watching.value = true
    if (!tickTimer) {
      tickTimer = setInterval(() => {
        now.value = Date.now()
      }, TICK_MS)
    }
    if (!safetyTimer) {
      safetyTimer = setInterval(() => {
        if (watching.value) scheduleRefresh(0)
      }, SAFETY_POLL_MS)
    }
  }

  // ---------------- 对外动作 ----------------

  /**
   * ASYNC 触发一次运行并开始跟踪。
   *
   * <p>抛出的异常仅代表「触发失败」（定义非法、网络错误）；
   * 触发成功后的执行失败会落在快照状态里，不抛异常。</p>
   */
  async function start(variables?: Record<string, any>) {
    const id = opts.workflowId()
    if (!id) throw new Error('工作流未打开，无法运行')
    reset()
    triggering.value = true
    try {
      const inst = await workflowApi.trigger(id, variables ?? {}, 'ASYNC')
      instanceId = inst.instanceId
      applySnapshot(inst)
      // 秒级工作流可能在触发返回时就已结束，此时没有可跟踪的事件
      if (running.value) {
        subscribe(inst.instanceId)
        startTimers()
        scheduleRefresh(0)
      }
    } catch (e) {
      running.value = false
      instanceId = null
      throw e
    } finally {
      triggering.value = false
    }
  }

  /** 用户主动停止跟踪（后台执行不受影响，需显式说明）。 */
  function stop() {
    if (!instanceId) return
    stopTimers()
    watching.value = false
  }

  /**
   * 挂到「已经存在的」实例上：载入快照，若仍在执行则继续跟踪。
   *
   * <p>两个使用场景：列表页点「运行」后跳进编辑器跟踪该次执行；
   * 点开历史运行记录做只读回放（此时快照已终态，不会订阅事件流）。</p>
   */
  async function attach(id: string) {
    reset()
    const snap = await workflowApi.instance(id)
    instanceId = id
    applySnapshot(snap)
    if (running.value) {
      subscribe(id)
      startTimers()
    }
  }

  /** 清空运行态（切换工作流 / 关闭面板时调用）。 */
  function reset() {
    stopTimers()
    instanceId = null
    instance.value = null
    hints.value = {}
    workflowMessage.value = ''
    running.value = false
    watching.value = false
    degraded.value = false
    now.value = Date.now()
  }

  onBeforeUnmount(() => {
    disposed = true
    reset()
  })

  return {
    // 状态
    triggering,
    running,
    watching,
    degraded,
    instance,
    track,
    workflowMessage,
    elapsedMs,
    // 派生
    counts,
    marks,
    firstFailedNodeId,
    mode,
    // 动作
    start,
    attach,
    stop,
    reset,
    refresh,
    durationOf,
    get disposed() {
      return disposed
    },
  }
}

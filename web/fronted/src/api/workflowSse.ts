import type { WorkflowEvent, WorkflowEventType } from './types'

const EVENT_TYPES: WorkflowEventType[] = [
  'WORKFLOW_STARTED',
  'NODE_STARTED',
  'NODE_COMPLETED',
  'NODE_FAILED',
  'NODE_SKIPPED',
  'VARIABLE_UPDATED',
  'WORKFLOW_COMPLETED',
  'WORKFLOW_FAILED',
]

export interface WorkflowSseHandlers {
  onEvent: (event: WorkflowEvent) => void
  onOpen?: () => void
  /** 有限重连耗尽后的最终失败（此前的事件不会重放）。 */
  onError?: (message: string) => void
}

/**
 * 订阅单个工作流实例的执行事件流（SSE）。
 *
 * <p><b>与 {@link connectSse}（会话流）的两点关键差别：</b></p>
 * <ol>
 *   <li><b>不会自动关闭。</b>后端 `WorkflowController#events` 的 Flux 由
 *       `mergeWith(Flux.interval(15s))` 维持，工作流结束后连接仍保持打开，
 *       因此终端事件（WORKFLOW_COMPLETED / WORKFLOW_FAILED）到达后
 *       <b>由调用方</b>在拿到最终快照后再关闭 —— 否则可能丢掉紧跟在
 *       终端事件之后的落盘状态。</li>
 *   <li><b>不保证事件完整。</b>后端用的是 `multicast().onBackpressureBuffer()`，
 *       订阅之前发布的事件不会补发。ASYNC 触发到浏览器建立连接之间存在
 *       真实窗口，故调用方必须用实例快照做对齐（见 `useWorkflowRun`）。</li>
 * </ol>
 *
 * <p>连接中断时有限重连（最多 3 次、指数退避），重连成功后只会收到
 * 「此刻之后」的事件 —— 这正是必须配合快照兜底的原因。</p>
 *
 * @returns 关闭函数（幂等）
 */
export function connectWorkflowSse(instanceId: string, handlers: WorkflowSseHandlers): () => void {
  let closed = false
  let es: EventSource | null = null
  let retries = 0
  let closeTimer: ReturnType<typeof setTimeout> | null = null

  const url = `/api/workflows/instances/${encodeURIComponent(instanceId)}/events`

  const cleanup = () => {
    if (es) {
      es.close()
      es = null
    }
    if (closeTimer) {
      clearTimeout(closeTimer)
      closeTimer = null
    }
  }

  const close = () => {
    closed = true
    cleanup()
  }

  const open = () => {
    if (closed) return
    cleanup()
    es = new EventSource(url)

    const onData = (raw: string) => {
      try {
        handlers.onEvent(JSON.parse(raw) as WorkflowEvent)
      } catch {
        // 忽略无法解析的帧（心跳注释帧不会走 listener，无需处理）
      }
    }

    for (const type of EVENT_TYPES) {
      es.addEventListener(type, (e: MessageEvent) => onData(e.data as string))
    }

    es.onopen = () => {
      retries = 0
      handlers.onOpen?.()
    }

    es.onerror = () => {
      if (closed) return
      if (retries < 3) {
        retries += 1
        const delay = Math.min(1000 * 2 ** retries, 8000)
        closeTimer = setTimeout(open, delay)
      } else {
        closed = true
        cleanup()
        handlers.onError?.('事件流连接中断，已切换为快照轮询')
      }
    }
  }

  open()
  return close
}

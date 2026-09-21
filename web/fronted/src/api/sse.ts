import type { AgentEvent, AgentEventType, SessionRef } from './types'

const EVENT_TYPES: AgentEventType[] = [
  'thought',
  'progress',
  'content_delta',
  'action',
  'tool_result',
  'skill_invoke',
  'mcp_invoke',
  'task_plan',
  'task_progress',
  'ask',
  'options',
  'error',
  'token',
  'stop',
]

export interface SseHandlers {
  onEvent: (event: AgentEvent) => void
  onError?: (message: string) => void
}

/**
 * 订阅会话事件流（SSE）。
 *
 * 收到 stop / error 事件后自动关闭；连接中断时有限重连（最多 3 次、指数退避）。
 * 返回关闭函数（幂等）。
 */
export function connectSse(session: SessionRef, handlers: SseHandlers): () => void {
  let closed = false
  let es: EventSource | null = null
  let retries = 0
  let closeTimer: ReturnType<typeof setTimeout> | null = null

  const params = new URLSearchParams({ userId: session.userId, workspaceId: session.workspaceId })
  const url = `/api/chat/${session.sessionId}/events?${params.toString()}`

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
        const event = JSON.parse(raw) as AgentEvent
        handlers.onEvent(event)
        if (event.type === 'stop' || event.type === 'error') {
          closed = true
          cleanup()
        }
      } catch {
        // 忽略无法解析的帧
      }
    }

    for (const type of EVENT_TYPES) {
      es.addEventListener(type, (e: MessageEvent) => onData(e.data as string))
    }

    es.onopen = () => {
      retries = 0
    }

    es.onerror = () => {
      // 已因 stop/error 关闭则不重连
      if (closed) return
      if (retries < 3) {
        retries += 1
        const delay = Math.min(1000 * 2 ** retries, 8000)
        closeTimer = setTimeout(open, delay)
      } else {
        closed = true
        cleanup()
        handlers.onError?.('事件流连接中断')
      }
    }
  }

  open()
  return close
}

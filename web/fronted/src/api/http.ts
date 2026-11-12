// 轻量 fetch 封装：统一 JSON 序列化与错误提取

export class ApiError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

type Options = {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  /** 超时毫秒；0 表示不超时。缺省时普通请求 30s、表单上传 10min。 */
  timeoutMs?: number
}

/** 普通 JSON 请求超时：后端卡死时不再无限挂起（此前 fetch 无任何超时，界面永久转圈）。 */
const DEFAULT_TIMEOUT_MS = 30_000
/** 文件上传（multipart）超时：大包导入/解压可能较慢，放宽到 10 分钟。 */
const UPLOAD_TIMEOUT_MS = 600_000

/**
 * 构造超时信号。
 *
 * <p>优先用 `AbortSignal.timeout`；老浏览器不支持时退回定时器 + AbortController，
 * 两条路径都保证 fetch 最终被中止，不会把请求永远挂着。</p>
 */
function timeoutSignal(ms: number): { signal?: AbortSignal; cancel: () => void } {
  if (ms <= 0) return { cancel: () => {} }
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function') {
    return { signal: AbortSignal.timeout(ms), cancel: () => {} }
  }
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(new DOMException('timeout', 'TimeoutError')), ms)
  return { signal: ctrl.signal, cancel: () => clearTimeout(timer) }
}

async function request<T>(path: string, options: Options = {}): Promise<T> {
  const { method = 'GET', body } = options
  const isForm = body instanceof FormData
  const ms = options.timeoutMs ?? (isForm ? UPLOAD_TIMEOUT_MS : DEFAULT_TIMEOUT_MS)
  const { signal, cancel } = timeoutSignal(ms)
  let res: Response
  try {
    res = await fetch(path, {
      method,
      headers: body !== undefined && !isForm ? { 'Content-Type': 'application/json' } : undefined,
      body: isForm ? body : body !== undefined ? JSON.stringify(body) : undefined,
      signal,
    })
  } catch (e) {
    // 超时与网络错误区分开：前者提示可重试，后者多半是后端未启动
    if ((e as Error)?.name === 'TimeoutError' || (e as Error)?.name === 'AbortError') {
      throw new ApiError(0, `请求超时（${Math.round(ms / 1000)}s），请重试`)
    }
    throw new ApiError(0, `无法连接后端服务：${(e as Error)?.message ?? '网络错误'}`)
  } finally {
    cancel()
  }
  if (!res.ok) {
    let message = `请求失败（HTTP ${res.status}）`
    try {
      const data = await res.json()
      if (data && typeof data === 'object') {
        message = String((data as { error?: unknown }).error ?? data.msg ?? message)
      }
    } catch {
      // 响应体非 JSON，保留默认消息
    }
    throw new ApiError(res.status, message)
  }
  const text = await res.text()
  if (!text) {
    return undefined as T
  }
  try {
    return JSON.parse(text) as T
  } catch {
    return text as unknown as T
  }
}

export const http = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body: unknown) => request<T>(path, { method: 'POST', body }),
  put: <T>(path: string, body: unknown) => request<T>(path, { method: 'PUT', body }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}

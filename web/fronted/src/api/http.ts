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
}

async function request<T>(path: string, options: Options = {}): Promise<T> {
  const { method = 'GET', body } = options
  const isForm = body instanceof FormData
  const res = await fetch(path, {
    method,
    headers: body !== undefined && !isForm ? { 'Content-Type': 'application/json' } : undefined,
    body: isForm ? body : body !== undefined ? JSON.stringify(body) : undefined,
  })
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

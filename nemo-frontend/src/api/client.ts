import { ApiError } from './errors'
import type { ApiResponse } from './types'

const API_BASE = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '/api'

export function apiUrl(path: string): string { return `${API_BASE}${path}` }
let csrf: { token: string; headerName: string } | null = null
let csrfPromise: Promise<{ token: string; headerName: string }> | null = null
let csrfEpoch = 0

export function clearCsrf(): void { csrf = null; csrfPromise = null; csrfEpoch++ }

async function loadCsrf(): Promise<{ token: string; headerName: string }> {
  if (csrf) return csrf
  csrfPromise ??= request<{ token: string; headerName: string }>('/user/csrf', { timeoutMs: 8000 })
  const pending = csrfPromise
  const epoch = csrfEpoch
  try {
    const token = await pending
    if (epoch === csrfEpoch) csrf = token
    return token
  } finally { if (csrfPromise === pending) csrfPromise = null }
}

interface RequestOptions extends Omit<RequestInit, 'body' | 'signal'> {
  body?: unknown
  signal?: AbortSignal | null | undefined
  timeoutMs?: number
  mutation?: boolean
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  return requestAttempt<T>(path, options, true)
}

async function requestAttempt<T>(path: string, options: RequestOptions, canRefreshCsrf: boolean): Promise<T> {
  const controller = new AbortController()
  let appliedCsrfToken: string | null = null
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json')
  if (options.body !== undefined) headers.set('Content-Type', 'application/json')
  if (options.mutation && path !== '/user/csrf') {
    const token = await loadCsrf()
    appliedCsrfToken = token.token
    headers.set(token.headerName, token.token)
  }
  const timeout = window.setTimeout(() => controller.abort('timeout'), options.timeoutMs ?? 15000)
  const externalSignal = options.signal
  const abort = () => controller.abort('external')
  externalSignal?.addEventListener('abort', abort, { once: true })
  try {
    const response = await fetch(`${API_BASE}${path}`, {
      ...options, body: options.body === undefined ? null : JSON.stringify(options.body),
      credentials: 'include', headers, signal: controller.signal
    })
    const text = await response.text()
    let envelope: ApiResponse<T>
    try { envelope = JSON.parse(text) as ApiResponse<T> }
    catch { throw new ApiError(response.ok ? '服务返回了无法识别的数据' : `服务暂不可用（${response.status}）`, response.ok ? 'protocol' : 'http', undefined, undefined, response.status) }
    if (!response.ok) throw new ApiError(envelope.message || `请求失败（${response.status}）`, 'http', envelope.code, envelope.errorKey ?? undefined, response.status)
    if (typeof envelope !== 'object' || typeof envelope.code !== 'number' || !('data' in envelope))
      throw new ApiError('服务响应格式不正确', 'protocol')
    if (envelope.code !== 0) throw new ApiError(envelope.message || '操作失败', 'business', envelope.code, envelope.errorKey ?? undefined)
    return envelope.data
  } catch (error) {
    // This filter rejects before business execution. Only this explicit response
    // is safe to retry; never repeat a mutation after network or business errors.
    if (canRefreshCsrf && options.mutation && error instanceof ApiError
        && error.status === 403 && error.errorKey === 'CSRF_INVALID' && !options.signal?.aborted) {
      if (csrf?.token === appliedCsrfToken) clearCsrf()
      return requestAttempt<T>(path, options, false)
    }
    if (error instanceof ApiError) throw error
    if (controller.signal.aborted) throw new ApiError(controller.signal.reason === 'timeout' ? '请求超时，请稍后重试' : '请求已取消', controller.signal.reason === 'timeout' ? 'timeout' : 'aborted')
    throw new ApiError('无法连接服务，请检查网络', 'network')
  } finally {
    window.clearTimeout(timeout); externalSignal?.removeEventListener('abort', abort)
  }
}

export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (failureCount >= 2 || !(error instanceof ApiError)) return false
  if (error.errorKey === 'JUDGE_BUSY') return false
  return error.kind === 'network' || error.kind === 'timeout' || (error.status !== undefined && error.status >= 500)
}

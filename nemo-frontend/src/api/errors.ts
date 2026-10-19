export type ApiErrorKind = 'network'|'timeout'|'aborted'|'http'|'business'|'protocol'

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly kind: ApiErrorKind,
    public readonly code?: number,
    public readonly errorKey?: string,
    public readonly status?: number
  ) { super(message); this.name = 'ApiError' }
}

export function readableError(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return '请求失败，请稍后重试'
}

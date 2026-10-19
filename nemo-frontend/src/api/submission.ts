import { apiUrl, request } from './client'
import type { ExecutionMode, Id, LanguageId, PageResult, Submission } from './types'

export interface SubmissionFilters { current: number; pageSize: number; problemNo?: number | undefined; language?: LanguageId | undefined; status?: number | undefined; sortField?: string; sortOrder?: string }
export function submitCode(body: { questionId: Id; language: LanguageId; executionMode: ExecutionMode; code: string; problemVersion: number; clientRequestId: string }): Promise<Id> {
  return request('/question/question_submit/do', { method: 'POST', body, mutation: true, timeoutMs: 15000 })
}
export function getSubmission(id: Id, signal?: AbortSignal): Promise<Submission> { return request(`/question/question_submit/get/vo?id=${encodeURIComponent(id)}`, { signal }) }
export function recoverSubmission(clientRequestId: string): Promise<{ id: Id; status: number } | null> { return request(`/question/question_submit/by_request?clientRequestId=${encodeURIComponent(clientRequestId)}`) }
export function listMySubmissions(filters: SubmissionFilters, signal?: AbortSignal): Promise<PageResult<Submission>> {
  return request('/question/question_submit/my/list/page', { method: 'POST', body: filters, signal })
}

export interface SubmissionActivityDay { date: string; submissions: number; problems: number }
export interface SubmissionActivity { month: string; today: string; timezone: string; days: SubmissionActivityDay[] }
export function getSubmissionActivity(month: string, signal?: AbortSignal): Promise<SubmissionActivity> {
  return request(`/question/question_submit/my/activity?month=${encodeURIComponent(month)}`, { signal })
}

export interface SubmissionProgress { id: Id; status: number; terminal: boolean; updatedAt?: string }

export function watchSubmissionProgress(
  id: Id,
  onProgress: (progress: SubmissionProgress) => void,
  onConnectionChange?: (connected: boolean) => void
): () => void {
  const source = new EventSource(apiUrl(`/question/question_submit/events?id=${encodeURIComponent(id)}`), { withCredentials: true })
  source.onopen = () => onConnectionChange?.(true)
  source.onerror = () => onConnectionChange?.(false)
  source.addEventListener('submission-progress', event => {
    try { onProgress(JSON.parse((event as MessageEvent<string>).data) as SubmissionProgress) }
    catch { onConnectionChange?.(false) }
  })
  return () => { source.close(); onConnectionChange?.(false) }
}

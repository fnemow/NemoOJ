import type { Id,Submission,SubmissionStatus } from '@/api/types'

export interface RecentSubmissionResult {
  id: Id
  status: SubmissionStatus
  verdict: string | null
  executedTime: number | null
  executedMemory: number | null
  completedAt: string
}

function scope(userId: Id, questionId: Id): string {
  return `${encodeURIComponent(String(userId))}.${encodeURIComponent(String(questionId))}`
}

function key(userId: Id, questionId: Id): string {
  return `nemooj.recent-result.v2.${scope(userId, questionId)}`
}

export function pendingSubmissionKey(userId: Id, questionId: Id): string {
  return `nemooj.pending.v2.${scope(userId, questionId)}`
}

export function toRecentResult(submission: Submission): RecentSubmissionResult {
  return {
    id: submission.id,
    status: submission.status,
    verdict: submission.verdict ?? submission.judgeInfo?.message ?? null,
    executedTime: submission.judgeInfo?.executedTime ?? null,
    executedMemory: submission.judgeInfo?.executedMemory ?? null,
    completedAt: submission.updateTime
  }
}

export function saveRecentResult(userId: Id, questionId: Id, submission: Submission): RecentSubmissionResult {
  const result = toRecentResult(submission)
  localStorage.setItem(key(userId, questionId), JSON.stringify(result))
  return result
}

export function loadRecentResult(userId: Id, questionId: Id): RecentSubmissionResult | null {
  try {
    const raw = localStorage.getItem(key(userId, questionId))
    if (!raw) return null
    const value = JSON.parse(raw) as Partial<RecentSubmissionResult>
    if (value.id == null || (value.status !== 2 && value.status !== 3)) return null
    return {
      id: String(value.id), status: value.status, verdict: value.verdict ?? null,
      executedTime: typeof value.executedTime === 'number' ? value.executedTime : null,
      executedMemory: typeof value.executedMemory === 'number' ? value.executedMemory : null,
      completedAt: typeof value.completedAt === 'string' ? value.completedAt : ''
    }
  } catch { return null }
}

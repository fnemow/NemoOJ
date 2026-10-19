const prefix = 'nemooj.ai.plan.task.'
export function readPlanTask(userId?: string): string {
  if (!userId) return ''
  try { sessionStorage.removeItem('nemooj.ai.plan.task'); return sessionStorage.getItem(prefix + userId) ?? '' }
  catch { return '' }
}
export function writePlanTask(userId: string, taskId: string): void {
  try { sessionStorage.setItem(prefix + userId, taskId) } catch { /* Optional persistence. */ }
}

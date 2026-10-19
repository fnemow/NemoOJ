import type { SubmissionActivityDay } from '@/api/submission'

export function shanghaiDate(now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en', { timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit' }).formatToParts(now)
  const value = (key: Intl.DateTimeFormatPartTypes) => parts.find(part => part.type === key)?.value ?? ''
  return `${value('year')}-${value('month')}-${value('day')}`
}
export function shiftMonth(month: string, delta: number): string {
  const [year, number] = month.split('-').map(Number)
  const date = new Date(Date.UTC(year!, number! - 1 + delta, 1))
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth()+1).padStart(2,'0')}`
}
export function calendarDays(month: string): Array<string | null> {
  const [year, number] = month.split('-').map(Number)
  const first = new Date(Date.UTC(year!, number! - 1, 1))
  const offset = (first.getUTCDay()+6)%7
  const count = new Date(Date.UTC(year!, number!, 0)).getUTCDate()
  return Array.from({length:Math.ceil((offset+count)/7)*7},(_,index) =>
    index < offset || index >= offset+count ? null : `${month}-${String(index-offset+1).padStart(2,'0')}`)
}
export function activityLevel(count: number): number {
  return count <= 0 ? 0 : count < 4 ? 1 : count < 8 ? 2 : count < 16 ? 3 : 4
}
export function activityTooltip(day: SubmissionActivityDay): string {
  const [year,month,date] = day.date.split('-').map(Number)
  return `${year}年${month}月${date}日：提交了${day.problems}道题目，共${day.submissions}次提交`
}

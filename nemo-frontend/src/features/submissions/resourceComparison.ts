import type { Submission } from '@/api/types'
export function comparable(a:Submission,b:Submission):boolean{
  return a.userId===b.userId&&a.questionId===b.questionId&&a.problemVersion!=null&&a.problemVersion===b.problemVersion&&a.language===b.language&&!!a.executionMode&&a.executionMode===b.executionMode
    &&a.status===2&&b.status===2&&(a.verdict??a.judgeInfo?.message)?.toLowerCase()==='accepted'&&(b.verdict??b.judgeInfo?.message)?.toLowerCase()==='accepted'
    &&!!a.runtime?.runtimeId&&a.runtime.runtimeId===b.runtime?.runtimeId&&!!a.effectiveLimits&&!!b.effectiveLimits
    &&a.effectiveLimits.timeLimitMs===b.effectiveLimits.timeLimitMs&&a.effectiveLimits.memoryLimitKB===b.effectiveLimits.memoryLimitKB
    &&a.effectiveLimits.outputLimitBytes===b.effectiveLimits.outputLimitBytes
}
export function resourceDelta(current:number|null|undefined,previous:number|null|undefined):string{
  if(current==null||previous==null||!Number.isFinite(current)||!Number.isFinite(previous))return '—'
  const delta=current-previous;return `${delta>0?'+':''}${Math.round(delta*100)/100}`
}

import type { PrivateBatchItem } from '@/api/ai'
export const terminalTask = (status:string) => ['SUCCEEDED','FAILED','CANCELLED'].includes(status)
export function trainingStage(item:Pick<PrivateBatchItem,'status'|'stage'|'repairCount'>):string {
  if(item.status==='SUCCEEDED')return '验证通过，可开始练习'
  if(item.status==='FAILED')return '生成未通过，可单题重试'
  if(item.status==='CANCELLED')return '任务已取消'
  if(item.status==='QUEUED')return '等待生成'
  const stages:Record<string,string>={CLAIMED:'正在准备',GENERATING:'正在生成题目',PRECHECK:'检查结构与协议',VALIDATION_QUEUED:'等待沙箱验证',VALIDATING:'正在验证三种语言与两种模式',REPAIRING:`正在自动修复（第 ${item.repairCount||1} 轮）`,PUBLISHING:'正在保存题目'}
  return stages[item.stage??'']??'正在处理'
}
export const difficultyLabel={EASY:'简单',MEDIUM:'中等',HARD:'困难'} as const

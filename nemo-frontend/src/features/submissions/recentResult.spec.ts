import { beforeEach,describe,expect,it } from 'vitest'
import { loadRecentResult,pendingSubmissionKey,saveRecentResult,toRecentResult } from './recentResult'
import type { Submission } from '@/api/types'

const submission = {
  id:'91',status:3,verdict:'runtime_error',judgeInfo:{message:'runtime_error',executedTime:18,executedMemory:2048},
  updateTime:'2026-09-22T12:00:00Z'
} as Submission

describe('recent submission resource result',()=>{
  beforeEach(()=>localStorage.clear())
  it('keeps verdict and resource usage for the next submission',()=>{
    expect(toRecentResult(submission)).toMatchObject({id:'91',status:3,executedTime:18,executedMemory:2048})
    saveRecentResult('user-1','question-1',submission)
    expect(loadRecentResult('user-1','question-1')).toMatchObject({id:'91',verdict:'runtime_error',executedTime:18,executedMemory:2048})
  })
  it('isolates recent and pending submissions by user and question',()=>{
    saveRecentResult('user-1','question-1',submission)
    expect(loadRecentResult('user-2','question-1')).toBeNull()
    expect(loadRecentResult('user-1','question-2')).toBeNull()
    expect(pendingSubmissionKey('user-1','question-1')).not.toBe(pendingSubmissionKey('user-2','question-1'))
  })
  it('ignores malformed stored data',()=>{
    localStorage.setItem('nemooj.recent-result.v2.user-1.question-1','{"status":0}')
    expect(loadRecentResult('user-1','question-1')).toBeNull()
  })
})

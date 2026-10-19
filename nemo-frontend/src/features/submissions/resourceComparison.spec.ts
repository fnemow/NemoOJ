import { describe,it,expect } from 'vitest'
import { comparable,resourceDelta } from './resourceComparison'
import type { Submission } from '@/api/types'
const submission={id:'1',userId:'u',questionId:'q',problemVersion:1,language:'java',executionMode:'FUNCTION',status:2,verdict:'accepted',runtime:{runtimeId:'java8'},effectiveLimits:{timeLimitMs:2000,memoryLimitKB:262144,outputLimitBytes:1000}} as Submission
describe('resource comparisons',()=>{
  it('compares successful submissions only under equal execution conditions',()=>{
    expect(comparable(submission,{...submission,id:'2'})).toBe(true)
    for(const patch of [{userId:'other'},{questionId:'other'},{problemVersion:2},{language:'python'},{executionMode:'ACM'},{status:3},{verdict:'wrong_answer'},{runtime:null},{effectiveLimits:null}])
      expect(comparable(submission,{...submission,...patch} as Submission)).toBe(false)
  })
  it('keeps zero measurements and hides absent values',()=>{
    expect(resourceDelta(0,1)).toBe('-1');expect(resourceDelta(2,0)).toBe('+2');expect(resourceDelta(null,0)).toBe('—')
  })
})

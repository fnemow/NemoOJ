import {beforeEach,describe,it,expect} from 'vitest'
import {readPlanTask,writePlanTask} from './taskStorage'
beforeEach(()=>sessionStorage.clear())
describe('learning task account isolation',()=>{
  it('restores each account independently and does not restore guest tasks',()=>{
    writePlanTask('user-1','task-1');writePlanTask('user-2','task-2')
    expect(readPlanTask('user-2')).toBe('task-2');expect(readPlanTask('user-1')).toBe('task-1')
    expect(readPlanTask()).toBe('')
  })
  it('discards the legacy unscoped reference rather than assigning it to a user',()=>{
    sessionStorage.setItem('nemooj.ai.plan.task','previous-user-task')
    expect(readPlanTask('user-2')).toBe('');expect(sessionStorage.getItem('nemooj.ai.plan.task')).toBeNull()
  })
})

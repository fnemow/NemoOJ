import { describe,it,expect } from 'vitest'
import { trainingStage,terminalTask } from './training'
describe('training progress',()=>{
  it('uses actual task stages rather than fake percentages',()=>{
    expect(trainingStage({status:'RUNNING',stage:'VALIDATING',repairCount:0})).toContain('三种语言')
    expect(trainingStage({status:'RUNNING',stage:'REPAIRING',repairCount:2})).toContain('第 2 轮')
    expect(trainingStage({status:'SUCCEEDED',stage:'COMPLETE',repairCount:1})).toContain('可开始练习')
  })
  it('polls unfinished tasks and stops after all terminal outcomes',()=>{
    expect(terminalTask('QUEUED')).toBe(false);expect(terminalTask('RUNNING')).toBe(false)
    for(const status of ['SUCCEEDED','FAILED','CANCELLED'])expect(terminalTask(status)).toBe(true)
  })
})

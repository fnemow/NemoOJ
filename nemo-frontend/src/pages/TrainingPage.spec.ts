import { ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import TrainingPage from './TrainingPage.vue'
import * as api from '@/api/ai'
const user=ref({id:'owner-1',userRole:'user'})
vi.mock('@/composables/useSession',()=>({useSession:()=>({user})}))
vi.mock('@/api/ai',()=>({getAiEntitlements:vi.fn(),listStudyPlans:vi.fn(),getPlanTopics:vi.fn(),listPrivateBatches:vi.fn(),listPrivateProblems:vi.fn(),getAiTask:vi.fn(),createPrivateBatch:vi.fn(),retryPrivateBatchItem:vi.fn()}))
const mounted:Array<ReturnType<typeof mount>>=[],clients:QueryClient[]=[]
beforeEach(()=>{
  vi.clearAllMocks();localStorage.clear();user.value={id:'owner-1',userRole:'user'}
  vi.mocked(api.getAiEntitlements).mockResolvedValue({tier:'VIP',vipActive:true,planEnabled:true,privateProblemsEnabled:true,analysisEnabled:true,usedToday:0,dailyLimit:null,remainingToday:null,businessDate:'2026-10-10'})
  vi.mocked(api.listStudyPlans).mockResolvedValue([])
  vi.mocked(api.getPlanTopics).mockResolvedValue([{tag:'数组',total:20,accepted:0,available:20}])
  vi.mocked(api.listPrivateBatches).mockResolvedValue({current:1,pageSize:10,total:0,records:[]})
  vi.mocked(api.listPrivateProblems).mockResolvedValue([])
})
afterEach(()=>{mounted.splice(0).forEach(w=>w.unmount());clients.splice(0).forEach(c=>c.clear())})
async function render(path='/study-plans'){
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/study-plans',component:TrainingPage},{path:'/private-problems/:id',component:{template:'<div>练习</div>'}}]})
  await router.push(path);await router.isReady()
  const client=new QueryClient({defaultOptions:{queries:{retry:false}}});clients.push(client)
  const errors=vi.fn()
  const wrapper=mount(TrainingPage,{global:{plugins:[router,ElementPlus,[VueQueryPlugin,{queryClient:client}]],config:{errorHandler:errors}}});mounted.push(wrapper)
  await flushPromises();return {wrapper,router,errors,client}
}
it('keeps the private goal while switching tabs and restores the URL tab',async()=>{
  const {wrapper,router,errors}=await render('/study-plans?tab=private')
  await wrapper.get('textarea').setValue('训练红黑树旋转')
  await router.push('/study-plans?tab=plans');await flushPromises()
  await router.push('/study-plans?tab=private');await flushPromises()
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('训练红黑树旋转')
  expect(wrapper.findAll('.el-form textarea')).toHaveLength(0)
  expect(errors).not.toHaveBeenCalled()
})
it('clears the goal and uses owner-scoped queries when the account changes',async()=>{
  const {wrapper,errors}=await render('/study-plans?tab=private')
  await wrapper.get('textarea').setValue('owner-1 私人需求')
  user.value={id:'owner-2',userRole:'user'};await flushPromises()
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('')
  expect(api.listPrivateBatches).toHaveBeenCalledTimes(2)
  expect(errors).not.toHaveBeenCalled()
})
it('opens a validated slot immediately even when the other slots failed or remain queued',async()=>{
  vi.mocked(api.listPrivateBatches).mockResolvedValue({current:1,pageSize:10,total:1,records:[{id:'batch',goal:'树的递进练习',rowVersion:1,status:'RUNNING',createTime:'2026-10-10',items:[
    {difficulty:'EASY',privateProblemId:'ready',taskId:'a',status:'SUCCEEDED',title:'基础树题',problemStatus:'READY',repairCount:0,attempt:1,completed:false},
    {difficulty:'MEDIUM',privateProblemId:'failed',taskId:'b',status:'FAILED',title:'中等树题',problemStatus:'REJECTED',repairCount:2,attempt:1,completed:false},
    {difficulty:'HARD',privateProblemId:'queued',taskId:'c',status:'QUEUED',title:'困难树题',problemStatus:'GENERATING',repairCount:0,attempt:1,completed:false}
  ]}]})
  const {wrapper,errors}=await render('/study-plans?tab=private')
  expect(wrapper.get('a[href="/private-problems/ready"]').text()).toContain('开始练习')
  expect(wrapper.find('a[href="/private-problems/failed"]').exists()).toBe(false)
  expect(wrapper.findAll('.spinner')).toHaveLength(1)
  expect(wrapper.text()).toContain('重试这道题')
  expect(errors).not.toHaveBeenCalled()
})

it('keeps failure details collapsed and retries the selected slot with its current version',async()=>{
  const batch={id:'failed-batch',goal:'红黑树',rowVersion:7,status:'PARTIAL_FAILED',createTime:'2026-10-14',items:[
    {difficulty:'EASY' as const,privateProblemId:'failed',taskId:'failed-task',status:'FAILED',title:'AI 题目生成中',problemStatus:'REJECTED',message:'完整的失败原因，包含 <script>unsafe()</script>，可以展开查看。',repairCount:2,attempt:1,completed:false}
  ]}
  vi.mocked(api.listPrivateBatches).mockResolvedValue({current:1,pageSize:10,total:1,records:[batch]})
  vi.mocked(api.retryPrivateBatchItem).mockResolvedValue(batch)
  const {wrapper,errors}=await render('/study-plans?tab=private')
  expect(wrapper.get('.private-card h3').text()).toBe('简单训练题待重试')
  const details=wrapper.get('.failure-details')
  expect((details.element as HTMLDetailsElement).open).toBe(false)
  expect(details.text()).toContain(batch.items[0]!.message)
  expect(details.find('script').exists()).toBe(false)
  const retryButton=wrapper.findAll('button').find(b=>b.text()==='重试这道题')!
  await retryButton.trigger('click');await flushPromises()
  expect(api.retryPrivateBatchItem).toHaveBeenCalledWith('failed-batch','EASY',7,expect.any(String))
  expect(errors).not.toHaveBeenCalled()
})

it('shows one selected group, keeps it selected on refresh, and pages the directory',async()=>{
  const batch=(id:string,goal:string)=>({id,goal,rowVersion:1,status:'SUCCEEDED',createTime:'2026-10-14',items:[
    {difficulty:'EASY' as const,privateProblemId:`${id}-problem`,taskId:`${id}-task`,status:'SUCCEEDED',title:`${goal}入门`,problemStatus:'READY',repairCount:0,attempt:1,completed:false}
  ]})
  const first=batch('first','红黑树'),second=batch('second','单调栈'),older=batch('older','动态规划')
  const newest=[first,second]
  vi.mocked(api.listPrivateBatches).mockImplementation(async(current)=>({current,pageSize:5,total:6,records:current===1?newest:[older]}))
  const {wrapper,client,errors}=await render('/study-plans?tab=private')
  expect(wrapper.findAll('.private-batch')).toHaveLength(1)
  expect(wrapper.find('a[href="/private-problems/first-problem"]').exists()).toBe(true)
  await wrapper.findAll('.batch-directory-item')[1]!.trigger('click')
  expect(wrapper.find('a[href="/private-problems/second-problem"]').exists()).toBe(true)
  expect(wrapper.find('a[href="/private-problems/first-problem"]').exists()).toBe(false)
  await client.invalidateQueries({queryKey:['private-batches']});await flushPromises()
  expect(wrapper.find('a[href="/private-problems/second-problem"]').exists()).toBe(true)
  await wrapper.get('.batch-directory .btn-next').trigger('click');await flushPromises()
  expect(api.listPrivateBatches).toHaveBeenLastCalledWith(2,5,expect.any(AbortSignal),'','')
  expect(wrapper.findAll('.private-batch')).toHaveLength(1)
  expect(wrapper.find('a[href="/private-problems/older-problem"]').exists()).toBe(true)
  const created=batch('created','链表')
  newest.unshift(created);vi.mocked(api.createPrivateBatch).mockResolvedValue(created)
  await wrapper.get('textarea').setValue('链表')
  await wrapper.findAll('button').find(b=>b.text()==='生成三道训练题')!.trigger('click');await flushPromises()
  expect(api.listPrivateBatches).toHaveBeenLastCalledWith(1,5,expect.any(AbortSignal),'','')
  expect(wrapper.find('a[href="/private-problems/created-problem"]').exists()).toBe(true)
  expect(errors).not.toHaveBeenCalled()
})

it('pages legacy problems instead of appending every old problem',async()=>{
  vi.mocked(api.listPrivateProblems).mockResolvedValue(Array.from({length:12},(_,i)=>({id:`legacy-${i}`,title:`历史题目${i}`,tags:['数组'],difficulty:'EASY',status:'READY'})))
  const {wrapper,errors}=await render('/study-plans?tab=private')
  expect((wrapper.get('.legacy').element as HTMLDetailsElement).open).toBe(false)
  expect(wrapper.findAll('.legacy .plan-item')).toHaveLength(5)
  await wrapper.get('.legacy .btn-next').trigger('click');await flushPromises()
  expect(wrapper.find('a[href="/private-problems/legacy-5"]').exists()).toBe(true)
  expect(wrapper.find('a[href="/private-problems/legacy-0"]').exists()).toBe(false)
  expect(errors).not.toHaveBeenCalled()
})

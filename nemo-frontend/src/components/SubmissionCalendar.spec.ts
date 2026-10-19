import { ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { getSubmissionActivity } from '@/api/submission'
import type { User } from '@/api/types'
import SubmissionCalendar from './SubmissionCalendar.vue'
import { shanghaiDate } from '@/features/submissions/activity'

const initialUser: User={id:'one',userAccount:'member',userName:'用户一',userRole:'user',userAvatar:null,userProfile:null,createTime:'2026-01-01'}
const user=ref<User|null>({...initialUser})
vi.mock('@/composables/useSession',()=>({useSession:()=>({user})}))
vi.mock('@/api/submission',()=>({getSubmissionActivity:vi.fn()}))
const clients:QueryClient[]=[],wrappers:Array<ReturnType<typeof mount>>=[]
const today=shanghaiDate(),month=today.slice(0,7)
const empty={month,today,timezone:'Asia/Shanghai',days:[]}
beforeEach(()=>{vi.clearAllMocks();user.value={...initialUser};vi.mocked(getSubmissionActivity).mockResolvedValue(empty)})
afterEach(()=>{wrappers.splice(0).forEach(w=>w.unmount());clients.splice(0).forEach(c=>c.clear());vi.useRealTimers()})
async function render() {
  const client=new QueryClient({defaultOptions:{queries:{retry:false}}});clients.push(client)
  const wrapper=mount(SubmissionCalendar,{global:{plugins:[[VueQueryPlugin,{queryClient:client}]]}})
  wrappers.push(wrapper);await flushPromises();return wrapper
}
it('shows colored own counts, a descriptive tooltip and prevents advancing into a future month',async()=>{
  vi.mocked(getSubmissionActivity).mockResolvedValue({...empty,days:[{date:today,submissions:5,problems:2}]})
  const wrapper=await render();const cell=wrapper.get(`[data-date="${today}"]`)
  expect(cell.classes()).toContain('level-2');expect(cell.attributes('aria-label')).toContain('提交了2道题目，共5次提交')
  expect(wrapper.get('[aria-label="下一个月"]').attributes('disabled')).toBeDefined()
})
it('renders hover details without relying on the application to globally register Tooltip',async()=>{
  vi.useFakeTimers()
  vi.mocked(getSubmissionActivity).mockResolvedValue({...empty,days:[{date:today,submissions:5,problems:2}]})
  const wrapper=await render();await wrapper.get(`[data-date="${today}"]`).trigger('mouseenter')
  await vi.advanceTimersByTimeAsync(150);await flushPromises()
  expect(document.querySelector('[role="tooltip"]')?.textContent).toContain('提交了2道题目，共5次提交')
})
it('loads the previous month without requesting unrelated submission pages',async()=>{
  const wrapper=await render();await wrapper.get('[aria-label="上一个月"]').trigger('click');await flushPromises()
  expect(getSubmissionActivity).toHaveBeenCalledTimes(2)
  expect(vi.mocked(getSubmissionActivity).mock.calls[1]?.[0]).not.toBe(month)
})
it('does not request private activity while anonymous',async()=>{
  user.value=null;const wrapper=await render()
  expect(wrapper.text()).toContain('登录后查看个人提交');expect(getSubmissionActivity).not.toHaveBeenCalled()
})
it('clears the preceding account counts on account switch',async()=>{
  vi.mocked(getSubmissionActivity).mockResolvedValueOnce({...empty,days:[{date:today,submissions:20,problems:1}]}).mockResolvedValueOnce(empty)
  const wrapper=await render();expect(wrapper.get(`[data-date="${today}"]`).classes()).toContain('level-4')
  user.value={...initialUser,id:'two'};await flushPromises()
  expect(wrapper.get(`[data-date="${today}"]`).classes()).toContain('level-0')
})
it('reports lookup failures and allows retry instead of inventing zero counts',async()=>{
  vi.mocked(getSubmissionActivity).mockRejectedValueOnce(new Error('unavailable')).mockResolvedValueOnce(empty)
  const wrapper=await render();expect(wrapper.text()).toContain('加载失败')
  expect(wrapper.get(`[data-date="${today}"]`).attributes('aria-label')).toContain('暂不可用')
  await wrapper.get('.retry').trigger('click');await flushPromises()
  expect(wrapper.text()).not.toContain('加载失败')
})

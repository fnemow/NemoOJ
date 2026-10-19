import { mount, flushPromises } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SubmissionAiAnalysis from './SubmissionAiAnalysis.vue'
import { getAiEntitlements, getAiTask, getAnalysis, requestAnalysis } from '@/api/ai'
import { ApiError } from '@/api/errors'

vi.mock('@/api/ai', () => ({ getAiEntitlements: vi.fn(), getAiTask: vi.fn(), getAnalysis: vi.fn(), requestAnalysis: vi.fn() }))
const report = { id: 'report', verdict: 'compile_error', modelId: 'model', promptVersion: '1', createTime: '', report: {
  verdictEvidence: {}, likelyCause: '缺少分号', locations: [{ line: 3, symbol: 'search', explanation: '语句没有结束' }], suggestions: [{ title: '补全语法', detail: '在第 3 行添加分号' }],
  complexity: { time: '不适用', space: '不适用', explanation: '编译未通过' }, uncertainty: ''
} }
const mounted: Array<ReturnType<typeof mount>> = []
const clients: QueryClient[] = []
async function render(status = 3, verdict = 'compile_error') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  clients.push(client)
  const wrapper = mount(SubmissionAiAnalysis, { props: { userId: 'user-1', submissionId: 'sub-1', status, verdict },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]], stubs: {
      ElButton: { props: ['disabled', 'loading'], template: '<button :disabled="disabled || loading"><slot/></button>' },
      ElDrawer: { props: ['modelValue'], template: '<div v-if="modelValue"><slot/></div>' }
      ,ElRadioGroup:{name:'ElRadioGroup',props:['modelValue'],template:'<div><slot/></div>'},ElRadioButton:true
    } } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  vi.resetAllMocks(); localStorage.clear()
  vi.mocked(getAiEntitlements).mockResolvedValue({ tier: 'FREE', vipActive: false, analysisEnabled: true, planEnabled: false, usedToday: 0, dailyLimit: 5, remainingToday: 5, businessDate: '2026-09-26' })
  vi.mocked(getAnalysis).mockRejectedValue(new ApiError('不存在', 'business', 40400))
  vi.mocked(requestAnalysis).mockResolvedValue({ taskId: 'task-1', type: 'ANALYSIS', status: 'QUEUED' })
  vi.mocked(getAiTask).mockResolvedValue({ taskId: 'task-1', type: 'ANALYSIS', status: 'RUNNING' })
})
afterEach(() => { mounted.splice(0).forEach(w => w.unmount()); clients.splice(0).forEach(c => c.clear()) })
describe('Submission AI analysis entry', () => {
  it('allows compile-error submissions and displays their completed report', async () => {
    const wrapper = await render()
    expect(wrapper.text()).toContain('AI 错误分析')
    vi.mocked(getAnalysis).mockResolvedValue(report)
    vi.mocked(getAiTask).mockResolvedValue({ taskId: 'task-1', type: 'ANALYSIS', status: 'SUCCEEDED' })
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(requestAnalysis).toHaveBeenCalledWith('sub-1', expect.any(String))
    expect(wrapper.text()).toContain('缺少分号')
    expect(wrapper.text()).not.toContain('在第 3 行添加分号')
    wrapper.findComponent({name:'ElRadioGroup'}).vm.$emit('update:modelValue','detail')
    await flushPromises()
    expect(wrapper.text()).toContain('在第 3 行添加分号')
    expect(wrapper.find('.analysis-section.cause').text()).toContain('错误原因')
    expect(wrapper.find('.analysis-section.locations').text()).toContain('第 3 行')
    expect(wrapper.find('.analysis-section.suggestions').text()).toContain('补全语法')
    expect(wrapper.find('.analysis-section.complexity').text()).toContain('编译未通过')
    expect(localStorage.getItem('nemooj.ai.analysis.task.user-1.sub-1')).toBe('task-1')
    await wrapper.get('.location-line').trigger('click')
    expect(wrapper.emitted('navigateLine')).toEqual([[3]])
  })
  it('offers optimization for Accepted and does not duplicate an in-flight request', async () => {
    const wrapper = await render(2, 'accepted')
    expect(wrapper.text()).toContain('AI 优化建议')
    await wrapper.get('button').trigger('click'); await flushPromises()
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(requestAnalysis).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('正在分析代码')
    expect(wrapper.find('.analysis-progress .loading-spinner').exists()).toBe(true)
  })
  it('restores an existing report without a stored task or consuming quota', async () => {
    vi.mocked(getAnalysis).mockResolvedValue(report)
    vi.mocked(getAiEntitlements).mockResolvedValue({ tier: 'FREE', vipActive: false, analysisEnabled: true, planEnabled: false, usedToday: 5, dailyLimit: 5, remainingToday: 0, businessDate: '2026-09-26' })
    const wrapper = await render()
    expect(wrapper.text()).toContain('查看 AI 分析')
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('缺少分号')
    expect(requestAnalysis).not.toHaveBeenCalled()
  })
  it('does not load a different users stored task', async () => {
    localStorage.setItem('nemooj.ai.analysis.task.user-2.sub-1', 'other-task')
    await render()
    expect(getAiTask).not.toHaveBeenCalled()
  })
  it('does not offer analysis before the judge finishes', async () => {
    const wrapper = await render(1)
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    expect(getAnalysis).not.toHaveBeenCalled()
    expect(requestAnalysis).not.toHaveBeenCalled()
  })
})

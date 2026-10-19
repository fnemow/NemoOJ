import { ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SubmissionDetailPage from './SubmissionDetailPage.vue'
import { getSubmission, listMySubmissions } from '@/api/submission'
import { getAiEntitlements, getAnalysis } from '@/api/ai'
import { ApiError } from '@/api/errors'
import type { Submission } from '@/api/types'

vi.mock('@/api/submission', () => ({ getSubmission: vi.fn(), listMySubmissions: vi.fn().mockResolvedValue({records:[]}), watchSubmissionProgress: vi.fn(() => () => {}) }))
vi.mock('@/api/ai', () => ({ getAiEntitlements: vi.fn(), getAnalysis: vi.fn(), getAiTask: vi.fn(), requestAnalysis: vi.fn() }))
vi.mock('@/composables/useSession', () => ({ useSession: () => ({ user: ref({ id: 'user-1', userRole: 'user' }) }) }))

const submission: Submission = {
  id: 'sub-1', questionId: 'question-1', problemNo: 1, questionTitle: '两数之和', userId: 'user-1',
  language: 'java', executionMode: 'FUNCTION', code: 'class Solution { /* submitted source */ }',
  status: 2, verdict: 'accepted', problemVersion: 1,
  judgeInfo: { message: 'accepted', executedTime: 123, executedMemory: 2048 },
  runtime: { runtimeId: 'java8', displayName: 'Java 8' }, effectiveLimits: null, diagnostics: null,
  createTime: '2026-09-26T03:00:00Z', updateTime: '2026-09-26T03:00:02Z'
}
const mounted: Array<ReturnType<typeof mount>> = []
const clients: QueryClient[] = []
async function render() {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/submissions/:submissionId', component: SubmissionDetailPage },
    { path: '/submissions', component: { template: '<h1>我的提交列表</h1>' } },
    { path: '/problems/:problemNo', component: { template: '<h1>题目工作区</h1>' } },
    { path: '/problems', component: { template: '<h1>题库已显示</h1>' } }
  ] })
  await router.push('/submissions/sub-1'); await router.isReady()
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  clients.push(client)
  const onError = vi.fn()
  const wrapper = mount({ template: '<RouterLink to="/problems">返回题库</RouterLink><RouterView/>' }, {
    global: { plugins: [router, [VueQueryPlugin, { queryClient: client }]], config: { errorHandler: onError }, stubs: {
      ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot/></button>' },
      ElDrawer: { props: ['modelValue'], template: '<div v-if="modelValue"><slot/></div>' }, ElSkeleton: true
      , ElRadioGroup: true, ElRadioButton: true
    } }
  })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, router, onError }
}
beforeEach(() => {
  vi.resetAllMocks(); localStorage.clear()
  vi.mocked(getSubmission).mockResolvedValue(submission)
  vi.mocked(listMySubmissions).mockResolvedValue({ records: [], total: 0, current: 1, size: 20 })
  vi.mocked(getAiEntitlements).mockResolvedValue({ tier: 'FREE', vipActive: false, analysisEnabled: true, planEnabled: false, usedToday: 0, dailyLimit: 5, remainingToday: 5, businessDate: '2026-09-26' })
  vi.mocked(getAnalysis).mockRejectedValue(new ApiError('不存在', 'business', 40400))
})
afterEach(() => { mounted.splice(0).forEach(w => w.unmount()); clients.splice(0).forEach(c => c.clear()) })

describe('submission details remain readable and navigable', () => {
  it('copies the original source without rendered line numbers or formatting', async () => {
    const descriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    try {
      const { wrapper } = await render()
      const button = wrapper.findAll('button').find(button => button.text() === '复制源码')!
      await button.trigger('click'); await flushPromises()
      expect(writeText).toHaveBeenCalledWith(submission.code)
    } finally {
      if (descriptor) Object.defineProperty(navigator, 'clipboard', descriptor)
      else Reflect.deleteProperty(navigator, 'clipboard')
    }
  })
  it('renders actual submitted source, time and memory without setup errors', async () => {
    const { wrapper, onError } = await render()
    expect(wrapper.text()).toContain('提交详情')
    expect(wrapper.get('.source code').text()).toBe(submission.code)
    expect(wrapper.get('.summary').text()).toContain('123 ms')
    expect(wrapper.get('.summary').text()).toContain('2.0 MiB')
    expect(onError).not.toHaveBeenCalled()
  })
  it('shows compilation diagnostics with unavailable resource measurements', async () => {
    vi.mocked(getSubmission).mockResolvedValue({ ...submission, status: 3, verdict: 'compile_error', judgeInfo: null,
      diagnostics: { phase: 'compile', message: '/app/Solution.java:3: missing return statement', truncated: false } })
    const { wrapper, onError } = await render()
    expect(wrapper.text()).toContain('编译错误')
    expect(wrapper.get('.diagnostic-panel').text()).toContain('missing return statement')
    expect(wrapper.get('.summary').text()).toContain('— ms')
    expect(wrapper.get('.source code').text()).toBe(submission.code)
    expect(onError).not.toHaveBeenCalled()
  })
  it('links a compiler location to the submitted line and keeps source inert', async () => {
    vi.mocked(getSubmission).mockResolvedValue({ ...submission, language: 'cpp', executionMode: 'ACM',
      code: '#include <iostream>\nint main() {\n  return missing;\n}\n// <img src=x onerror=alert(1)>', status: 3,
      diagnostics: { phase: 'compile', message: '/app/main.cpp:3:10: error: missing was not declared\n  return missing;\n         ^', truncated: true } })
    const { wrapper, onError } = await render()
    await wrapper.get('.line-link').trigger('click'); await flushPromises()
    expect(wrapper.get('.code-line.is-highlighted').attributes('data-line')).toBe('3')
    expect(wrapper.get('.source .syntax-keyword').text()).toBe('include')
    expect(wrapper.find('.source img').exists()).toBe(false)
    expect(wrapper.text()).toContain('部分输出已截断')
    await wrapper.get('.reader-toolbar button').trigger('click')
    expect(wrapper.get('.source-scroll').classes()).toContain('wrapped')
    expect(onError).not.toHaveBeenCalled()
  })
  it('keeps source and navigation working when AI services fail', async () => {
    vi.mocked(getAiEntitlements).mockRejectedValue(new Error('AI unavailable'))
    vi.mocked(getAnalysis).mockRejectedValue(new Error('AI report unavailable'))
    const { wrapper, router, onError } = await render()
    expect(wrapper.get('.source code').text()).toBe(submission.code)
    await wrapper.get('a[href="/problems"]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('题库已显示')
    await router.push('/submissions/sub-1'); await flushPromises()
    expect(wrapper.text()).toContain('提交详情')
    expect(onError).not.toHaveBeenCalled()
  })
  it('updates the source and measurements when switching between submissions', async () => {
    const { wrapper, router, onError } = await render()
    vi.mocked(getSubmission).mockResolvedValue({ ...submission, id: 'sub-2', code: 'print(42)', language: 'python', judgeInfo: { executedTime: 0, executedMemory: 0, message: 'accepted' } })
    await router.push('/submissions/sub-2'); await flushPromises()
    expect(wrapper.get('.source code').text()).toBe('print(42)')
    expect(wrapper.get('.summary').text()).toContain('0 ms')
    expect(wrapper.get('.summary').text()).toContain('0.0 MiB')
    expect(onError).not.toHaveBeenCalled()
  })
  it('shows request errors with retry while keeping links usable', async () => {
    vi.mocked(getSubmission).mockRejectedValue(new ApiError('提交不存在或无权查看', 'business', 40400))
    const { wrapper, onError } = await render()
    expect(wrapper.get('[role="alert"]').text()).toContain('提交不存在或无权查看')
    vi.mocked(getSubmission).mockResolvedValue(submission)
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(wrapper.get('.source code').text()).toBe(submission.code)
    await wrapper.get('a[href="/submissions"]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('我的提交列表')
    expect(onError).not.toHaveBeenCalled()
  })
})

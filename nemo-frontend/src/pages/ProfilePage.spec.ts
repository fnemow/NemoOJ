import { ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { User } from '@/api/types'
import type { AiEntitlements } from '@/api/ai'
import { getAiEntitlements } from '@/api/ai'
import ProfilePage from './ProfilePage.vue'

const initialUser: User = { id: 'user-1', userAccount: 'user_one', userName: '用户一', userRole: 'user',
  userAvatar: null, userProfile: null, createTime: '2026-10-12T00:00:00Z' }
const user = ref<User>({ ...initialUser })
vi.mock('@/composables/useSession', () => ({ useSession: () => ({ user }), sessionKey: ['session'] }))
vi.mock('@/api/ai', () => ({ getAiEntitlements: vi.fn() }))
const wrappers: Array<ReturnType<typeof mount>> = [], clients: QueryClient[] = []
const free: AiEntitlements = { tier: 'FREE', vipActive: false, analysisEnabled: true, planEnabled: false,
  usedToday: 0, dailyLimit: 5, remainingToday: 5, businessDate: '2026-10-12' }

beforeEach(() => {
  vi.clearAllMocks()
  user.value = { ...initialUser }
  vi.mocked(getAiEntitlements).mockResolvedValue(free)
})
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); clients.splice(0).forEach(c => c.clear()) })
async function render(client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  clients.push(client)
  const wrapper = mount(ProfilePage, { global: { plugins: [ElementPlus, [VueQueryPlugin, { queryClient: client }]] } })
  wrappers.push(wrapper); await flushPromises(); return wrapper
}

it('shows VIP for an active membership even though the account role remains user and AI is unavailable', async () => {
  vi.mocked(getAiEntitlements).mockResolvedValue({ ...free, tier: 'VIP', vipActive: true, analysisEnabled: false })
  const wrapper = await render()
  expect(wrapper.get('.identity').text()).toContain('VIP 用户')
  expect(wrapper.get('.identity').text()).not.toContain('普通用户')
})
it.each(['FREE', 'VIP'] as const)('shows an ordinary user for inactive %s membership', async tier => {
  vi.mocked(getAiEntitlements).mockResolvedValue({ ...free, tier, vipActive: false })
  expect((await render()).get('.identity').text()).toContain('普通用户')
})
it('keeps administrator identity without requesting member-only entitlements', async () => {
  user.value = { ...user.value, userRole: 'admin' }
  expect((await render()).get('.identity').text()).toContain('管理员')
  expect(getAiEntitlements).not.toHaveBeenCalled()
})
it('rechecks a cached ordinary membership on entering the profile after a VIP upgrade', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['ai-entitlements', 'user-1'], free)
  vi.mocked(getAiEntitlements).mockResolvedValue({ ...free, tier: 'VIP', vipActive: true })
  expect((await render(client)).get('.identity').text()).toContain('VIP 用户')
  expect(getAiEntitlements).toHaveBeenCalledTimes(1)
})
it('does not expose the preceding user VIP status after switching accounts', async () => {
  vi.mocked(getAiEntitlements).mockResolvedValueOnce({ ...free, tier: 'VIP', vipActive: true }).mockResolvedValueOnce(free)
  const wrapper = await render()
  expect(wrapper.get('.identity').text()).toContain('VIP 用户')
  user.value = { ...user.value, id: 'user-2', userName: '用户二' }; await flushPromises()
  expect(wrapper.get('.identity').text()).toContain('用户二')
  expect(wrapper.get('.identity').text()).toContain('普通用户')
  expect(wrapper.get('.identity').text()).not.toContain('VIP 用户')
})
it('shows a retryable unavailable status instead of misclassifying a failed lookup', async () => {
  vi.mocked(getAiEntitlements).mockRejectedValueOnce(new Error('Network unavailable')).mockResolvedValueOnce({ ...free, tier: 'VIP', vipActive: true })
  const wrapper = await render()
  expect(wrapper.get('.identity').text()).toContain('身份暂不可用')
  expect(wrapper.get('.identity').text()).not.toContain('普通用户')
  await wrapper.get('.membership-retry').trigger('click'); await flushPromises()
  expect(wrapper.get('.identity').text()).toContain('VIP 用户')
})

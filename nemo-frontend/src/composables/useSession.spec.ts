import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AppHeader from '@/components/AppHeader.vue'
import { getSession, logout as logoutApi } from '@/api/user'
import type { User } from '@/api/types'
import { sessionKey, useSession } from './useSession'

vi.mock('@/api/user', () => ({ getSession: vi.fn(), logout: vi.fn() }))
const user: User = { id: 'owner-1', userAccount: 'owner_1', userName: '测试用户', userRole: 'user', userProfile: null, userAvatar: null, createTime: '' }
const wrappers: Array<ReturnType<typeof mount>> = []
const clients: QueryClient[] = []
beforeEach(() => { vi.resetAllMocks(); vi.mocked(getSession).mockResolvedValue(user); vi.mocked(logoutApi).mockResolvedValue(true) })
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); clients.splice(0).forEach(c => c.clear()) })

async function render() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  clients.push(client)
  client.setQueryData(sessionKey, user)
  client.setQueryData(['submission', user.id, '123'], { code: 'private source' })
  client.setQueryData(['public', 'problems'], { records: [] })
  const Observer = defineComponent({ setup() {
    const session = useSession()
    return () => h('span', { class: 'other-user' }, session.user.value?.userName ?? '未登录')
  } })
  let session!: ReturnType<typeof useSession>
  const Root = defineComponent({ setup() {
    session = useSession()
    return () => h('div', [h(AppHeader, { user: session.user.value, state: session.state.value, onLogout: session.logout }), h(Observer)])
  } })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/problems', component: { render: () => h('div') } }, { path: '/:pathMatch(.*)*', component: { render: () => h('div') } }] })
  await router.push('/problems'); await router.isReady()
  const wrapper = mount(Root, { global: { plugins: [router, [VueQueryPlugin, { queryClient: client }]], stubs: { ElButton: { template: '<button><slot/></button>' } } } })
  wrappers.push(wrapper); await flushPromises()
  return { wrapper, client, session, router }
}

describe('logout updates every active session observer', () => {
  it('immediately replaces account information with login without remounting the page', async () => {
    const { wrapper, client, router } = await render()
    expect(wrapper.get('.profile-copy').text()).toContain('测试用户')
    await wrapper.get('.logout').trigger('click'); await flushPromises()
    expect(wrapper.find('.profile').exists()).toBe(false)
    expect(wrapper.get('a.login').text()).toBe('登录')
    expect(wrapper.get('.other-user').text()).toBe('未登录')
    expect(wrapper.find('a[href="/submissions"]').exists()).toBe(false)
    expect(router.currentRoute.value.path).toBe('/problems')
    expect(client.getQueryData(['submission', user.id, '123'])).toBeUndefined()
    expect(client.getQueryData(['public', 'problems'])).toEqual({ records: [] })
    client.setQueryData(sessionKey, { ...user, id: 'owner-2', userName: '另一用户' })
    await flushPromises()
    expect(wrapper.get('.profile-copy').text()).toContain('另一用户')
    expect(wrapper.get('.other-user').text()).toBe('另一用户')
  })

  it('cancels a pending session refresh and ignores its late authenticated result', async () => {
    const { wrapper, client, session } = await render()
    let resolve!: (user: User) => void
    let signal: AbortSignal | undefined
    vi.mocked(getSession).mockImplementation(value => { signal = value; return new Promise(done => { resolve = done }) })
    const pending = client.invalidateQueries({ queryKey: sessionKey })
    await flushPromises()
    await session.logout(); await flushPromises()
    expect(signal?.aborted).toBe(true)
    resolve(user); await pending; await flushPromises()
    expect(wrapper.find('.profile').exists()).toBe(false)
    expect(wrapper.get('a.login').text()).toBe('登录')
    expect(client.getQueryData(sessionKey)).toBeNull()
  })

  it('keeps the session when the server rejects logout', async () => {
    const { wrapper, session, client } = await render()
    vi.mocked(logoutApi).mockRejectedValue(new Error('network unavailable'))
    await expect(session.logout()).rejects.toThrow('network unavailable')
    expect(wrapper.get('.profile-copy').text()).toContain('测试用户')
    expect(client.getQueryData(['submission', user.id, '123'])).toBeDefined()
  })
})

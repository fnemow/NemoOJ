import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RegisterPage from './RegisterPage.vue'
import { register } from '@/api/user'
const replace = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRouter: () => ({ replace }) }))
vi.mock('@/api/user', () => ({ register: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn() } }))
function render() {
  return mount(RegisterPage, { global: { stubs: {
    ElForm: { template: '<form><slot/></form>' },
    ElFormItem: { template: '<div><slot/></div>' },
    ElInput: { props: ['modelValue', 'type'], emits: ['update:modelValue'], template: '<input :type="type" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">' },
    ElButton: { template: '<button><slot/></button>' }, RouterLink: { template: '<a><slot/></a>' }
  } } })
}
beforeEach(() => vi.resetAllMocks())
describe('invited registration', () => {
  it('requires an invitation before sending any registration request', async () => {
    const wrapper = render()
    await wrapper.get('button').trigger('click')
    expect(wrapper.get('[role="alert"]').text()).toContain('请填写管理员提供的邀请码')
    expect(register).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('passes the trimmed invitation with account fields and navigates on success', async () => {
    const wrapper = render()
    await wrapper.get('[aria-label="邀请码"]').setValue(' invited-code ')
    await wrapper.get('[aria-label="账号"]').setValue('invited_user')
    await wrapper.get('[aria-label="密码"]').setValue('password123')
    await wrapper.get('[aria-label="确认密码"]').setValue('password123')
    vi.mocked(register).mockResolvedValue('123')
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(register).toHaveBeenCalledWith({ userAccount: 'invited_user', userPassword: 'password123', checkPassword: 'password123', invitationCode: 'invited-code' })
    expect(replace).toHaveBeenCalledWith({ path: '/login', query: { account: 'invited_user' } })
    wrapper.unmount()
  })
})

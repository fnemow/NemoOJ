import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import RefreshButton from './RefreshButton.vue'

describe('RefreshButton', () => {
  it('forwards refresh clicks and shows the requested label', async () => {
    const click = vi.fn()
    const wrapper = mount(RefreshButton, { attrs: { onClick: click, size: 'small' }, slots: { default: '刷新结果' } })
    expect(wrapper.text()).toBe('刷新结果')
    await wrapper.get('button').trigger('click')
    expect(click).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ loading: true })
    expect(wrapper.get('button').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    await wrapper.get('button').trigger('click')
    expect(click).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ loading: false })
    await wrapper.get('button').trigger('click')
    expect(click).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
})

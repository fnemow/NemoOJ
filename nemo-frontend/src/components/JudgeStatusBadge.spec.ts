import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import JudgeStatusBadge from './JudgeStatusBadge.vue'

describe('JudgeStatusBadge', () => {
  it('shows a spinner only while waiting or judging', async () => {
    const wrapper = mount(JudgeStatusBadge, { props: { status: 0 } })
    expect(wrapper.text()).toContain('等待判题')
    expect(wrapper.find('.loading-spinner').exists()).toBe(true)
    expect(wrapper.attributes('aria-busy')).toBe('true')

    await wrapper.setProps({ status: 1 })
    expect(wrapper.text()).toContain('判题中')
    expect(wrapper.find('.loading-spinner').exists()).toBe(true)

    await wrapper.setProps({ status: 2, verdict: 'accepted' })
    expect(wrapper.text()).toContain('通过')
    expect(wrapper.find('.loading-spinner').exists()).toBe(false)
    expect(wrapper.attributes('aria-busy')).toBe('false')
  })
})

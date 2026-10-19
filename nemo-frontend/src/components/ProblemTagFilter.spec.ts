import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ProblemTagFilter from './ProblemTagFilter.vue'

function render(selected: string[] = []) {
  return mount(ProblemTagFilter, {
    props: { modelValue: selected, options: ['简单', '中等', '困难', '数组', '二叉树'] },
    global: { stubs: {
      ElPopover: { name: 'TagPopoverStub', props: ['visible'], emits: ['update:visible'], template: '<div><span @click="$emit(\'update:visible\', !visible)"><slot name="reference"/></span><div v-if="visible"><slot/></div></div>' },
      ElTag: { template: '<span><slot/></span>' }
    } }
  })
}

describe('ProblemTagFilter', () => {
  it('opens a grouped grid of selectable tags without a text input', async () => {
    const wrapper = render()
    await wrapper.find('.tag-trigger').trigger('click')
    expect(wrapper.find('input').exists()).toBe(false)
    expect(wrapper.find('.tag-trigger').attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('[aria-label="难度"]').findAll('.tag-choice')).toHaveLength(3)
    expect(wrapper.find('[aria-label="算法与数据结构"]').findAll('.tag-choice')).toHaveLength(2)
    wrapper.unmount()
  })

  it('adds a second tag and lets an existing selection be toggled off', async () => {
    const wrapper = render(['数组'])
    await wrapper.find('.tag-trigger').trigger('click')
    expect(wrapper.find('button[aria-label="数组"]').attributes('aria-pressed')).toBe('true')
    await wrapper.find('button[aria-label="二叉树"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([['数组', '二叉树']])
    await wrapper.setProps({ modelValue: ['数组', '二叉树'] })
    expect(wrapper.find('.tag-summary').text()).toBe('已选 2 个标签')
    await wrapper.find('button[aria-label="数组"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[1]).toEqual([['二叉树']])
    wrapper.unmount()
  })

  it('clears all selected tags and closes with Escape', async () => {
    const wrapper = render(['简单', '数组'])
    await wrapper.find('.tag-trigger').trigger('click')
    await wrapper.find('.clear-button').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([[]])
    await wrapper.setProps({ modelValue: [] })
    expect(wrapper.find('.clear-button').attributes('disabled')).toBeDefined()
    await wrapper.find('.tag-panel').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('.tag-trigger').attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('.tag-summary').text()).toBe('选择标签')
    wrapper.unmount()
  })
})

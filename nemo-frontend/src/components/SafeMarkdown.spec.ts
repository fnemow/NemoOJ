import { mount } from '@vue/test-utils'
import { describe,expect,it } from 'vitest'
import SafeMarkdown from './SafeMarkdown.vue'
describe('SafeMarkdown',()=>{
  it('renders markdown without creating executable markup',()=>{const wrapper=mount(SafeMarkdown,{props:{source:'**safe** <img src=x onerror=alert(1)> [bad](javascript:alert(1))'}});expect(wrapper.find('strong').text()).toBe('safe');expect(wrapper.find('img').exists()).toBe(false);expect(wrapper.find('a').exists()).toBe(false)})
})

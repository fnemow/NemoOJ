import { mount,flushPromises } from '@vue/test-utils'
import { createMemoryHistory,createRouter } from 'vue-router'
import { describe,expect,it } from 'vitest'
import App from './App.vue'
import { assistantVisible,openSiteAssistant } from '@/composables/useSiteAssistant'

describe('assistant page visibility',()=>{
  it('hides on the poster page and closes an open popup when returning home',async()=>{
    const router=createRouter({history:createMemoryHistory(),routes:[
      {path:'/',name:'home',component:{template:'<div>海报页</div>'}},
      {path:'/problems',name:'problems',component:{template:'<div>题库</div>'}}
    ]})
    openSiteAssistant()
    await router.push('/');await router.isReady()
    const wrapper=mount(App,{global:{plugins:[router],stubs:{SiteAssistant:{template:'<button data-test="assistant">AI 助手</button>'}}}})
    expect(wrapper.find('[data-test="assistant"]').exists()).toBe(false)
    expect(assistantVisible.value).toBe(false)
    await router.push('/problems');await flushPromises()
    expect(wrapper.find('[data-test="assistant"]').exists()).toBe(true)
    openSiteAssistant();await router.push('/');await flushPromises()
    expect(wrapper.find('[data-test="assistant"]').exists()).toBe(false)
    expect(assistantVisible.value).toBe(false)
    await router.push('/problems');await flushPromises()
    expect(wrapper.find('[data-test="assistant"]').exists()).toBe(true)
    expect(assistantVisible.value).toBe(false)
    wrapper.unmount()
  })
})

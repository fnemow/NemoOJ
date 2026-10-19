import { mount } from '@vue/test-utils'
import { createMemoryHistory,createRouter } from 'vue-router'
import { describe,expect,it } from 'vitest'
import AppHeader from './AppHeader.vue'
import type { User } from '@/api/types'

const baseUser: User={id:'1',userAccount:'member',userName:'Member',userAvatar:null,userProfile:null,userRole:'user',createTime:'2026-01-01T00:00:00+08:00'}

async function render(role: User['userRole'] | 'anonymous'){
  const router=createRouter({history:createMemoryHistory(),routes:[
    {path:'/',component:{template:'<div/>'}},
    {path:'/problems',component:{template:'<div/>'}},
    {path:'/submissions',component:{template:'<div/>'}},
    {path:'/feedback',component:{template:'<div/>'}},
    {path:'/study-plans',component:{template:'<div/>'}},
    {path:'/admin/problems',component:{template:'<div/>'}},
    {path:'/me',component:{template:'<div/>'}}
    ,{path:'/login',component:{template:'<div/>'}}
  ]})
  await router.push(role==='admin'?'/admin/problems':'/problems');await router.isReady()
  return mount(AppHeader,{props:{user:role==='anonymous'?null:{...baseUser,userRole:role},state:role==='anonymous'?'anonymous':'ready'},global:{plugins:[router],stubs:{ElButton:{template:'<button><slot/></button>'}}}})
}

describe('AppHeader role navigation',()=>{
  it('offers login but no registration to anonymous visitors',async()=>{
    const wrapper=await render('anonymous')
    expect(wrapper.text()).toContain('登录')
    expect(wrapper.find('a[href="/login"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/register"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('注册')
    wrapper.unmount()
  })
  it('shows only member navigation to an ordinary user',async()=>{
    const wrapper=await render('user')
    expect(wrapper.text()).toContain('题库')
    expect(wrapper.text()).toContain('提交记录')
    expect(wrapper.text()).not.toContain('AI 助手')
    expect(wrapper.find('.assistant-nav').exists()).toBe(false)
    expect(wrapper.find('a[href="/feedback"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('管理后台')
    wrapper.unmount()
  })

  it('shows only the management entry to an administrator',async()=>{
    const wrapper=await render('admin')
    expect(wrapper.text()).toContain('管理后台')
    expect(wrapper.text()).not.toContain('提交记录')
    expect(wrapper.text()).not.toContain('意见反馈')
    expect(wrapper.find('.assistant-nav').exists()).toBe(false)
  })
})

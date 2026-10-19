import { mount } from '@vue/test-utils'
import { describe,it,expect } from 'vitest'
import CustomStructureInfo from './CustomStructureInfo.vue'
import type { ProblemSpec } from '@/api/types'
describe('provided node description',()=>{
  it('describes injected fields and reference identity without editable definitions',()=>{
    const spec:ProblemSpec={mode:'function',className:'Solution',methodName:'solve',parameters:[{name:'start',type:'node<RBNode>'}],returnType:'boolean',resultSource:'return',comparator:{type:'EXACT'},harnessVersion:1,dataStructures:[{name:'RBNode',fields:[{name:'value',type:'int'},{name:'parent',type:'ref'},{name:'children',type:'ref[]'}]}]}
    const wrapper=mount(CustomStructureInfo,{props:{spec}})
    expect(wrapper.text()).toContain('RBNode');expect(wrapper.text()).toContain('引用列表');expect(wrapper.text()).toContain('无需重复定义')
    expect(wrapper.find('textarea').exists()).toBe(false)
    wrapper.unmount()
  })
  it('leaves old problem statements unchanged',()=>{
    const wrapper=mount(CustomStructureInfo,{props:{spec:null}});expect(wrapper.find('section').exists()).toBe(false);wrapper.unmount()
  })
})

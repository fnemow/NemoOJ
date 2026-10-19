import { afterEach,beforeEach,describe,expect,it,vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import CodeEditor from './CodeEditor.vue'

beforeEach(()=>vi.stubGlobal('matchMedia',()=>({matches:true})))
afterEach(()=>vi.unstubAllGlobals())
const props={modelValue:'class Solution:\n    pass\n',language:'python' as const,documentKey:'templates',theme:'vs-dark' as const,fontSize:14,mode:'ACM' as const}

describe('data structure template toolbar',()=>{
  it('hides insertion controls for core mode with platform-provided custom nodes',async()=>{
    const wrapper=mount(CodeEditor,{props:{...props,mode:'FUNCTION',problemSpec:{mode:'function',className:'Solution',methodName:'solve',parameters:[{name:'start',type:'node<RBNode>'}],returnType:'boolean',resultSource:'return',comparator:{type:'EXACT'},harnessVersion:1,dataStructures:[{name:'RBNode',fields:[{name:'value',type:'int'}]}]}}})
    await nextTick()
    expect(wrapper.find('.structure-toolbar').exists()).toBe(false)
    expect(wrapper.find('button').exists()).toBe(false)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    wrapper.unmount()
  })
  it('only exposes ACM insertion while switching modes without rewriting source',async()=>{
    const wrapper=mount(CodeEditor,{props})
    expect(wrapper.find('button').exists()).toBe(true)
    await wrapper.setProps({mode:'FUNCTION'})
    expect(wrapper.find('.structure-toolbar').exists()).toBe(false)
    expect(wrapper.get('textarea').element.value).toBe(props.modelValue)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    await wrapper.setProps({mode:'ACM'})
    expect(wrapper.find('button').exists()).toBe(true)
    expect(wrapper.get('textarea').element.value).toBe(props.modelValue)
    wrapper.unmount()
  })
  it('inserts a helper on mobile without overwriting the solution',async()=>{
    const wrapper=mount(CodeEditor,{props})
    await wrapper.get('button').trigger('click')
    const value=wrapper.emitted('update:modelValue')?.[0]?.[0] as string
    expect(value).toContain('class RBNode:')
    expect(value.endsWith(props.modelValue)).toBe(true)
    await wrapper.setProps({modelValue:value})
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
    expect(wrapper.get('[role="status"]').text()).toContain('已存在')
    wrapper.unmount()
  })
  it('keeps read-only editors free of insertion controls',async()=>{
    const wrapper=mount(CodeEditor,{props:{...props,readOnly:true}})
    await nextTick()
    expect(wrapper.find('button').exists()).toBe(false)
    expect(wrapper.get('textarea').attributes('readonly')).toBeDefined()
    wrapper.unmount()
  })
  it('selects templates in the current language',async()=>{
    const wrapper=mount(CodeEditor,{props:{...props,language:'java',modelValue:'class Solution {}'}})
    await wrapper.get('select').setValue('UnionFind')
    await wrapper.get('button').trigger('click')
    const value=wrapper.emitted('update:modelValue')?.[0]?.[0] as string
    expect(value).toContain('class UnionFind {')
    expect(value.endsWith('class Solution {}')).toBe(true)
    wrapper.unmount()
  })
  it('adapts mobile typography and can switch themes before Monaco is loaded',async()=>{
    const wrapper=mount(CodeEditor,{props})
    await nextTick()
    await wrapper.setProps({fontSize:18,theme:'vs'})
    expect(wrapper.get('.code-editor-shell').attributes('style')).toContain('--editor-font-size: 18px')
    expect(wrapper.get('.code-editor-shell').attributes('style')).toContain('--editor-line-height: 29px')
    expect(wrapper.get('textarea').element.value).toBe(props.modelValue)
    wrapper.unmount()
  })
})

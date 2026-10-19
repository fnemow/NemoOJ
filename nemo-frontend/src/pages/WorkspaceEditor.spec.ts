import { defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProblemWorkspacePage from './ProblemWorkspacePage.vue'
import PrivateProblemPage from './PrivateProblemPage.vue'
import { getCapabilities, getProblem, getTemplate } from '@/api/question'
import { getPrivateProblem, getPrivateProblemSubmissions } from '@/api/ai'
import { readDraft,writeDraft } from '@/features/workspace/drafts'
import { usePreferencesStore } from '@/stores/preferences'
import { retiredAcmIoTemplates } from '@/features/workspace/retiredAcmIoTemplates'
import type { Problem, ProblemCapabilities } from '@/api/types'

vi.mock('@/api/question',()=>({getProblem:vi.fn(),getCapabilities:vi.fn(),getTemplate:vi.fn()}))
vi.mock('@/api/submission',()=>({getSubmission:vi.fn(),recoverSubmission:vi.fn(),submitCode:vi.fn()}))
vi.mock('@/api/ai',()=>({getPrivateProblem:vi.fn(),getPrivateProblemSubmissions:vi.fn(),submitPrivateProblem:vi.fn()}))
vi.mock('@/composables/useSession',()=>({useSession:()=>({user:ref(null),state:ref('anonymous'),logout:vi.fn()})}))
vi.mock('@/features/workspace/drafts',()=>({draftKey:(...parts:string[])=>parts.join(':'),readDraft:vi.fn().mockResolvedValue(undefined),writeDraft:vi.fn().mockImplementation(async value=>({...value,revision:1})),listenDrafts:()=>()=>{}}))

const editorMounted=vi.fn()
const Editor=defineComponent({
  props:['modelValue','fontSize','documentKey'],emits:['update:modelValue','requestRestore','requestSave'],
  setup(){editorMounted()},
  template:'<textarea aria-label="代码编辑器" :value="modelValue" :data-font="fontSize" @input="$emit(\'update:modelValue\',$event.target.value)"/>'
})
const stubs={
  CodeEditor:Editor,
  ElButton:{props:['disabled'],template:'<button :disabled="disabled"><slot/></button>'},
  ElSelect:{props:['modelValue','disabled'],emits:['update:modelValue','change'],template:'<select :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\',$event.target.value);$emit(\'change\',$event.target.value)"><slot/></select>'},
  ElOption:{props:['value','label','disabled'],template:'<option :value="value" :disabled="disabled">{{label}}</option>'},
  AppHeader:{template:'<aside>保留的导航栏</aside>'},
  ProblemStatement:{template:'<article>题目描述</article>'},SafeMarkdown:true,CustomStructureInfo:true,ElTag:true,ElAlert:true,ElSkeleton:true
}
const problem:Problem={id:'q1',problemNo:1,title:'两数之和',content:'题目',tagList:[],submitNum:0,acceptedNum:0,problemSpec:null,judgeConfig:null,samples:[],problemVersion:1,isPublished:true,createTime:'',updateTime:''}
const caps:ProblemCapabilities={questionId:'q1',problemVersion:1,mode:'FUNCTION',capabilitiesVersion:'v1',availableModes:['ACM','FUNCTION'],expiresAt:'',recommendedLanguage:'java',languages:[{id:'java',displayName:'Java 8',runtimeId:'java8',enabled:true,modes:['ACM','FUNCTION'],maxCodeBytes:10000,effectiveLimits:{timeLimitMs:2000,memoryLimitKB:262144,outputLimitBytes:4096}}]}
const wrappers:Array<ReturnType<typeof mount>>=[]
const clients:QueryClient[]=[]
async function render(privatePage=false){
  const router=createRouter({history:createMemoryHistory(),routes:[{path:privatePage?'/private-problems/:id':'/problems/:problemId',component:privatePage?PrivateProblemPage:ProblemWorkspacePage},{path:'/study-plans',component:{template:'<div/>'}}]})
  await router.push(privatePage?'/private-problems/p1':'/problems/1')
  const pinia=createPinia(),client=new QueryClient({defaultOptions:{queries:{retry:false}}})
  clients.push(client)
  const wrapper=mount(privatePage?PrivateProblemPage:ProblemWorkspacePage,{global:{plugins:[pinia,router,[VueQueryPlugin,{queryClient:client}]],stubs}})
  wrappers.push(wrapper)
  await flushPromises()
  return {wrapper,router,client,prefs:usePreferencesStore(pinia)}
}
beforeEach(()=>{
  vi.clearAllMocks();localStorage.clear();sessionStorage.clear()
  vi.mocked(readDraft).mockReset().mockResolvedValue(undefined)
  vi.mocked(getProblem).mockResolvedValue(problem)
  vi.mocked(getCapabilities).mockResolvedValue(caps)
  vi.mocked(getTemplate).mockImplementation(async(_id,language,_version,mode)=>({questionId:'q1',problemVersion:1,language,mode:mode??'ACM',fileName:'Solution.java',templateVersion:'v1',code:mode==='FUNCTION'?'class Solution {}':'class Main {}',instructions:''}))
  vi.mocked(getPrivateProblem).mockResolvedValue({id:'p1',title:'私人题',difficulty:'EASY',tags:[],status:'READY',currentVersion:{id:'pv1',version:1,content:'描述',samples:[],problemSpec:null,availableModes:['ACM','FUNCTION'],templates:[{language:'java',mode:'ACM',fileName:'Main.java',code:'class Main {}'},{language:'java',mode:'FUNCTION',fileName:'Solution.java',code:'class Solution {}'}]}})
  vi.mocked(getPrivateProblemSubmissions).mockResolvedValue({records:[],total:0,current:1,size:20})
})
afterEach(()=>{wrappers.splice(0).forEach(w=>w.unmount());clients.splice(0).forEach(c=>c.clear())})

describe('workspace editor expansion and mode selection',()=>{
  it('expands and restores without remounting the editor, overwriting code or changing saved font preferences',async()=>{
    const {wrapper,prefs}=await render()
    const editor=wrapper.get('textarea')
    await editor.setValue('class Solution { /* my draft */ }')
    await wrapper.get('[aria-label="编辑器全屏"]').trigger('click')
    expect(wrapper.classes()).toContain('editor-expanded')
    expect(wrapper.get('aside').text()).toContain('导航栏')
    expect(wrapper.get('textarea').element).toBe(editor.element)
    expect(wrapper.get('textarea').attributes('data-font')).toBe('16')
    expect(prefs.fontSize).toBe(14)
    window.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',cancelable:true}))
    await flushPromises()
    expect(wrapper.classes()).not.toContain('editor-expanded')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toContain('my draft')
    expect(wrapper.get('textarea').attributes('data-font')).toBe('14')
    expect(editorMounted).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).not.toContain('Solution.java')
    await wrapper.get('[aria-label="编辑器全屏"]').trigger('click')
    wrapper.getComponent(Editor).vm.$emit('requestRestore')
    await flushPromises()
    expect(wrapper.classes()).not.toContain('editor-expanded')
  })
  it('uses a dropdown that saves the outgoing public draft and keeps the expanded view during mode switches',async()=>{
    const {wrapper}=await render()
    await wrapper.get('textarea').setValue('my function draft')
    await wrapper.get('[aria-label="编辑器全屏"]').trigger('click')
    await wrapper.get('select[aria-label="判题模式"]').setValue('ACM')
    await flushPromises()
    expect(writeDraft).toHaveBeenCalledWith(expect.objectContaining({code:'my function draft',key:expect.stringContaining('FUNCTION')}))
    expect(getTemplate).toHaveBeenLastCalledWith('1','java',1,'ACM')
    expect(wrapper.classes()).toContain('editor-expanded')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('class Main {}')
  })
  it('disables unsupported modes and leaves unavailable templates unreachable',async()=>{
    vi.mocked(getCapabilities).mockResolvedValue({...caps,mode:'ACM',availableModes:['ACM'],languages:[{...caps.languages[0]!,modes:['ACM']}]})
    const {wrapper}=await render()
    expect(wrapper.get('select[aria-label="判题模式"]').attributes('disabled')).toBeDefined()
    await wrapper.get('select[aria-label="判题模式"]').setValue('FUNCTION')
    await flushPromises()
    expect(getTemplate).toHaveBeenCalledTimes(1)
    expect(getTemplate).toHaveBeenLastCalledWith('1','java',1,'ACM')
  })
  it('keeps private source intact and does not consume Escape already handled by editor suggestions',async()=>{
    const {wrapper,router}=await render(true)
    await wrapper.get('textarea').setValue('private source')
    await wrapper.get('[aria-label="编辑器全屏"]').trigger('click')
    const consumed=new KeyboardEvent('keydown',{key:'Escape',cancelable:true})
    consumed.preventDefault();window.dispatchEvent(consumed);await flushPromises()
    expect(wrapper.classes()).toContain('editor-expanded')
    await wrapper.get('[aria-label="退出编辑器全屏"]').trigger('click')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('private source')
    expect(editorMounted).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).not.toContain('Main.java')
    await wrapper.get('select[aria-label="判题模式"]').setValue('FUNCTION');await flushPromises()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('class Solution {}')
    await wrapper.get('[aria-label="编辑器全屏"]').trigger('click')
    await router.push('/private-problems/p2');await flushPromises()
    expect(wrapper.classes()).not.toContain('editor-expanded')
  })
  it('initializes a private problem to its supported mode instead of using a template for another mode',async()=>{
    vi.mocked(getPrivateProblem).mockResolvedValue({id:'p1',title:'核心代码题',difficulty:'EASY',tags:[],status:'READY',currentVersion:{id:'pv1',version:1,content:'描述',samples:[],availableModes:['FUNCTION'],templates:[{language:'java',mode:'FUNCTION',fileName:'Solution.java',code:'class Solution {}'}]}})
    const {wrapper}=await render(true)
    expect((wrapper.get('select[aria-label="判题模式"]').element as HTMLSelectElement).value).toBe('FUNCTION')
    expect(wrapper.get('select[aria-label="判题模式"]').attributes('disabled')).toBeDefined()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('class Solution {}')
  })
  it('restores edited ACM drafts across the IO template version upgrade',async()=>{
    vi.mocked(getCapabilities).mockResolvedValue({...caps,mode:'ACM'})
    vi.mocked(getTemplate).mockResolvedValue({questionId:'q1',problemVersion:1,language:'java',mode:'ACM',fileName:'Main.java',templateVersion:'java8-acm-io-v3',code:'new IO skeleton',instructions:''})
    vi.mocked(readDraft).mockImplementation(async key=>key.endsWith(':java8-v2')?{key,code:'my existing ACM solution',templateVersion:'java8-v2',revision:9,updatedAt:0}:undefined)
    const {wrapper}=await render()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('my existing ACM solution')
    expect(readDraft).toHaveBeenCalledWith('guest:q1:1:java:ACM:java8-v2')
    await wrapper.get('textarea').setValue('my updated ACM solution')
    wrapper.getComponent(Editor).vm.$emit('requestSave');await flushPromises()
    expect(writeDraft).toHaveBeenCalledWith(expect.objectContaining({key:'guest:q1:1:java:ACM:java8-acm-io-v3',code:'my updated ACM solution',templateVersion:'java8-acm-io-v3'}))
  })
  it('prefers an existing new-version draft over the legacy copy',async()=>{
    vi.mocked(getCapabilities).mockResolvedValue({...caps,mode:'ACM'})
    vi.mocked(getTemplate).mockResolvedValue({questionId:'q1',problemVersion:1,language:'java',mode:'ACM',fileName:'Main.java',templateVersion:'java8-acm-io-v3',code:'new IO skeleton',instructions:''})
    vi.mocked(readDraft).mockImplementation(async key=>({key,code:key.endsWith(':java8-v2')?'stale legacy code':'current draft',templateVersion:'java8-acm-io-v3',revision:1,updatedAt:0}))
    const {wrapper}=await render()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('current draft')
    expect(readDraft).not.toHaveBeenCalledWith('guest:q1:1:java:ACM:java8-v2')
  })
  it('recovers edited legacy code if an older browser saved the untouched new default',async()=>{
    vi.mocked(getCapabilities).mockResolvedValue({...caps,mode:'ACM'})
    vi.mocked(getTemplate).mockResolvedValue({questionId:'q1',problemVersion:1,language:'java',mode:'ACM',fileName:'Main.java',templateVersion:'java8-acm-io-v3',code:'new IO skeleton',instructions:''})
    vi.mocked(readDraft).mockImplementation(async key=>({key,code:key.endsWith(':java8-v2')?'valuable old solution':'new IO skeleton',templateVersion:'java8-v2',revision:2,updatedAt:0}))
    const {wrapper}=await render()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('valuable old solution')
  })
  it('refreshes untouched private templates while preserving edited private code',async()=>{
    const {wrapper,client}=await render(true)
    const update=(source:string)=>client.setQueryData(['private-problem','p1'],{id:'p1',title:'私人题',difficulty:'EASY',tags:[],status:'READY',currentVersion:{id:'pv1',version:1,content:'描述',samples:[],availableModes:['ACM','FUNCTION'],templates:[{language:'java',mode:'ACM',fileName:'Main.java',code:source},{language:'java',mode:'FUNCTION',fileName:'Solution.java',code:'class Solution {}'}]}})
    update('new untouched starter');await flushPromises()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('new untouched starter')
    await wrapper.get('textarea').setValue('my private implementation')
    update('updated IO starter');await flushPromises()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('my private implementation')
  })
  it('keeps edited IO-version code when reverting to the minimal template',async()=>{
    vi.mocked(getCapabilities).mockResolvedValue({...caps,mode:'ACM'})
    vi.mocked(getTemplate).mockResolvedValue({questionId:'q1',problemVersion:1,language:'java',mode:'ACM',fileName:'Main.java',templateVersion:'java8-acm-basic-v4',code:'minimal starter',instructions:''})
    vi.mocked(readDraft).mockImplementation(async key=>key.endsWith(':java8-acm-io-v3')?{key,code:'my edited IO solution',templateVersion:'java8-acm-io-v3',revision:4,updatedAt:0}:undefined)
    const {wrapper}=await render()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('my edited IO solution')
    wrapper.getComponent(Editor).vm.$emit('requestSave');await flushPromises()
    expect(writeDraft).toHaveBeenCalledWith(expect.objectContaining({key:'guest:q1:1:java:ACM:java8-acm-basic-v4',code:'my edited IO solution'}))
  })
  it('skips untouched IO defaults to recover the original edited v2 draft',async()=>{
    vi.mocked(getCapabilities).mockResolvedValue({...caps,mode:'ACM'})
    vi.mocked(getTemplate).mockResolvedValue({questionId:'q1',problemVersion:1,language:'java',mode:'ACM',fileName:'Main.java',templateVersion:'java8-acm-basic-v4',code:'minimal starter',instructions:''})
    vi.mocked(readDraft).mockImplementation(async key=>key.endsWith(':java8-acm-io-v3')?{key,code:retiredAcmIoTemplates.java,templateVersion:'java8-acm-io-v3',revision:1,updatedAt:0}:key.endsWith(':java8-v2')?{key,code:'my original solution',templateVersion:'java8-v2',revision:7,updatedAt:0}:undefined)
    const {wrapper}=await render()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('my original solution')
  })
})

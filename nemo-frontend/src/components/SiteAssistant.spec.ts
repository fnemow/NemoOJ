import { afterEach,beforeEach,describe,expect,it,vi } from 'vitest'
import { flushPromises,mount } from '@vue/test-utils'
import { ref } from 'vue'
import SiteAssistant from './SiteAssistant.vue'
import { assistantVisible,assistantTab,openSiteAssistant,closeSiteAssistant } from '@/composables/useSiteAssistant'
import { askSiteAssistant,getHelpGuides,getSiteHelpAnswer } from '@/api/siteAssistant'

const session=ref<{id:string;userRole:string}|null>({id:'user-a',userRole:'user'})
vi.mock('@/composables/useSession',()=>({useSession:()=>({user:session})}))
vi.mock('@/api/siteAssistant',()=>({askSiteAssistant:vi.fn(),getHelpGuides:vi.fn(),getSiteHelpAnswer:vi.fn()}))
const guide={key:'submit_flow',title:'提交题目',text:'选择语言后点击提交',links:[{label:'题库',to:'/problems'}]}
const answer={kind:'HELP' as const,text:'在本次提交点击刷新结果',links:[]}
function render(){return mount(SiteAssistant,{global:{stubs:{RouterLink:{props:['to'],template:'<a><slot/></a>'},FeedbackPage:{template:'<div>人工反馈表单</div>'}}}})}
beforeEach(()=>{vi.resetAllMocks();session.value={id:'user-a',userRole:'user'};assistantVisible.value=false;assistantTab.value='help';vi.mocked(getHelpGuides).mockResolvedValue([guide])})
afterEach(()=>{closeSiteAssistant();vi.useRealTimers()})

describe('isolated website assistant',()=>{
  it('opens a small dialog and only sends manually typed text',async()=>{
    const wrapper=render();await wrapper.get('.assistant-launcher').trigger('click');await flushPromises()
    vi.mocked(askSiteAssistant).mockResolvedValue({status:'SUCCEEDED',answer})
    await wrapper.get('textarea').setValue('在哪里刷新结果');await wrapper.get('form').trigger('submit');await flushPromises()
    expect(askSiteAssistant).toHaveBeenCalledTimes(1)
    const call=vi.mocked(askSiteAssistant).mock.calls[0]!
    expect(call[0]).toBe('在哪里刷新结果');expect(call[1]).toEqual([])
    expect(JSON.stringify(call)).not.toContain('submissionId');expect(JSON.stringify(call)).not.toContain('code')
    expect(wrapper.text()).toContain(answer.text);wrapper.unmount()
  })
  it('shows a code-analysis redirect without starting analysis itself',async()=>{
    const wrapper=render();openSiteAssistant();await flushPromises()
    vi.mocked(askSiteAssistant).mockResolvedValue({status:'SUCCEEDED',answer:{kind:'CODE_REDIRECT',text:'请使用独立 AI 代码分析',links:[{label:'提交记录',to:'/submissions'}]}})
    await wrapper.get('textarea').setValue('帮我优化代码');await wrapper.get('form').trigger('submit');await flushPromises()
    expect(wrapper.text()).toContain('独立 AI 代码分析');expect(getSiteHelpAnswer).not.toHaveBeenCalled();wrapper.unmount()
  })
  it('uses guides for visitors without sending a model request',async()=>{
    session.value=null;const wrapper=render();openSiteAssistant();await flushPromises()
    expect(wrapper.find('textarea').exists()).toBe(false)
    await wrapper.get('.quick-guides button').trigger('click')
    expect(wrapper.text()).toContain(guide.text);expect(askSiteAssistant).not.toHaveBeenCalled();wrapper.unmount()
  })
  it('hides the assistant for administrators',()=>{
    session.value={id:'admin',userRole:'admin'};const wrapper=render()
    expect(wrapper.find('.assistant-launcher').exists()).toBe(false);wrapper.unmount()
  })
  it('clears conversation and ignores late answers after an account switch',async()=>{
    let resolve:(value:{status:string;answer:typeof answer})=>void=()=>{}
    vi.mocked(askSiteAssistant).mockImplementation(()=>new Promise(r=>{resolve=r}))
    const wrapper=render();openSiteAssistant();await flushPromises()
    await wrapper.get('textarea').setValue('用户A的问题');await wrapper.get('form').trigger('submit')
    session.value={id:'user-b',userRole:'user'};await flushPromises()
    resolve({status:'SUCCEEDED',answer});await flushPromises();openSiteAssistant();await flushPromises()
    expect(wrapper.text()).not.toContain('用户A的问题');expect(wrapper.text()).not.toContain(answer.text);wrapper.unmount()
  })
  it('keeps human feedback available inside the popup',async()=>{
    const wrapper=render();openSiteAssistant('feedback',3);await flushPromises()
    expect(wrapper.text()).toContain('人工反馈表单');wrapper.unmount()
  })
  it('polls only the dedicated website answer endpoint and displays queue progress',async()=>{
    vi.useFakeTimers()
    vi.mocked(askSiteAssistant).mockResolvedValue({taskId:'help-task',status:'QUEUED'})
    vi.mocked(getSiteHelpAnswer).mockResolvedValueOnce({status:'QUEUED'}).mockResolvedValueOnce({status:'SUCCEEDED',answer})
    const wrapper=render();openSiteAssistant();await flushPromises()
    await wrapper.get('textarea').setValue('怎么刷新结果');await wrapper.get('form').trigger('submit');await flushPromises()
    expect(wrapper.text()).toContain('正在排队')
    await vi.advanceTimersByTimeAsync(1500);await flushPromises()
    expect(getSiteHelpAnswer).toHaveBeenCalledWith('help-task',expect.any(AbortSignal))
    expect(wrapper.text()).toContain(answer.text);wrapper.unmount()
  })
})

import { mount,flushPromises } from '@vue/test-utils'
import { describe,it,expect,vi,beforeEach } from 'vitest'
import TryRunPanel from './TryRunPanel.vue'
import { request } from '@/api/client'
import type { Problem } from '@/api/types'
vi.mock('@/api/client',()=>({request:vi.fn()}))
const problem={id:'42',problemVersion:1,samples:[{input:'3\n1 2 3',output:'6'}],functionSamples:[{input:'{"value":3}',output:'3'}]} as Problem
const global={stubs:{ElButton:{props:['disabled'],template:'<button :disabled="disabled"><slot/></button>'},ElDrawer:{props:['modelValue'],template:'<div v-if="modelValue"><slot/></div>'},ElSelect:true,ElOption:true,ElRadioGroup:true,ElRadioButton:true}}
beforeEach(()=>vi.clearAllMocks())
describe('try run requests',()=>{
  it('uses only the selected public example without creating a submission',async()=>{
    vi.mocked(request).mockResolvedValue({verdict:'Completed',output:'3',executedTime:1,executedMemory:1024})
    const wrapper=mount(TryRunPanel,{props:{problem,language:'java',mode:'FUNCTION',code:'class Solution {}'},global})
    await wrapper.get('button').trigger('click');await flushPromises()
    expect(wrapper.text()).toContain('{"value":3}')
    const run=wrapper.findAll('button').find(b=>b.text()==='运行当前代码')!;await run.trigger('click');await flushPromises()
    expect(request).toHaveBeenCalledWith('/question/try_run',expect.objectContaining({mutation:true,body:{questionId:'42',problemVersion:1,language:'java',executionMode:'FUNCTION',code:'class Solution {}',sampleIndex:0}}))
    expect(wrapper.text()).toContain('运行完成');expect(wrapper.text()).toContain('1 ms');wrapper.unmount()
  })
  it('disables execution when the site or language is unavailable',async()=>{
    const wrapper=mount(TryRunPanel,{props:{problem,language:'cpp',mode:'ACM',code:'int main(){}',disabled:true},global})
    expect(wrapper.get('button').attributes('disabled')).toBeDefined();expect(request).not.toHaveBeenCalled();wrapper.unmount()
  })
})

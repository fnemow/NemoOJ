import { describe,expect,it,vi } from 'vitest'
import { registerMonacoLanguageSupport } from './monacoLanguageSupport'

describe('Monaco language provider registration',()=>{
  it('registers themes and one provider per language only once',()=>{
    const registerCompletionItemProvider=vi.fn()
    const defineTheme=vi.fn()
    const fakeMonaco={
      Range:class {},
      languages:{
        registerCompletionItemProvider,
        CompletionItemInsertTextRule:{InsertAsSnippet:4},
        CompletionItemKind:{Keyword:1,Snippet:2,Function:3,Method:4,Variable:5,Class:6,Constant:7}
      },
      editor:{defineTheme}
    }
    expect(registerMonacoLanguageSupport(fakeMonaco as never)).toBe(true)
    expect(registerMonacoLanguageSupport(fakeMonaco as never)).toBe(false)
    expect(registerCompletionItemProvider).toHaveBeenCalledTimes(3)
    expect(registerCompletionItemProvider.mock.calls.map(call=>call[0])).toEqual(['java','python','cpp'])
    expect(defineTheme).toHaveBeenCalledTimes(2)
  })
})

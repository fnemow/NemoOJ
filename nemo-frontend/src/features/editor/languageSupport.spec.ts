import { describe,expect,it } from 'vitest'
import type { ProblemSpec } from '@/api/types'
import { collectSemanticSpans,extractDocumentSymbols,getLocalCompletions,isCodePosition,maskNonCode,memberReceiverAt } from './languageSupport'

const problemSpec:ProblemSpec={
  mode:'function',className:'Solution',methodName:'twoSum',
  parameters:[{name:'nums',type:'int[]'},{name:'target',type:'int'}],returnType:'int[]',resultSource:'return',
  comparator:{type:'EXACT'},harnessVersion:1
}

describe('editor local language support',()=>{
  it('masks comments and strings while preserving Monaco offsets',()=>{
    const source='int 😀value = 1; // hiddenCall()\nString text = "secret";'
    const masked=maskNonCode(source,'java')
    expect(masked).toHaveLength(source.length)
    expect(masked).toContain('int 😀value = 1;')
    expect(masked).not.toContain('hiddenCall')
    expect(masked).not.toContain('secret')
    expect(masked.split('\n')).toHaveLength(2)
    expect(isCodePosition(source,'java',source.indexOf('hiddenCall')+2)).toBe(false)
  })

  it('offers runtime-compatible snippets for all three languages',()=>{
    for(const language of ['java','python','cpp'] as const){
      const items=getLocalCompletions({language,mode:'ACM',problemSpec:null,readOnly:false},'')
      expect(items.some(item=>item.label==='main'&&item.snippet)).toBe(true)
      expect(items.some(item=>item.label==='fori'&&item.insertText.includes('${1:'))).toBe(true)
    }
  })

  it('injects the core-code method signature and parameters without duplicates',()=>{
    const items=getLocalCompletions({language:'java',mode:'FUNCTION',problemSpec,readOnly:false},'int target = 0;')
    expect(items.find(item=>item.label==='twoSum')?.insertText).toBe('twoSum(${1:nums}, ${2:target})')
    expect(items.filter(item=>item.label==='target')).toHaveLength(1)
    expect(items.some(item=>item.label==='Solution')).toBe(true)
  })

  it('extracts current-file symbols and ignores comments and strings',()=>{
    const symbols=extractDocumentSymbols('def solve(items):\n    total = 0\n    # def hidden(nope):\n    text = "def fake(arg):"\n','python')
    expect(symbols).toEqual(expect.arrayContaining([{name:'solve',kind:'function'},{name:'items',kind:'variable'},{name:'total',kind:'variable'}]))
    expect(symbols.some(item=>item.name==='hidden'||item.name==='fake'||item.name==='arg')).toBe(false)
  })

  it('infers common container members for Java, Python and C++',()=>{
    const java=getLocalCompletions({language:'java',mode:'ACM',problemSpec:null,readOnly:false},'List<Integer> values = new ArrayList<>();\nvalues.','values')
    const python=getLocalCompletions({language:'python',mode:'ACM',problemSpec:null,readOnly:false},'items = []\nitems.','items')
    const cpp=getLocalCompletions({language:'cpp',mode:'ACM',problemSpec:null,readOnly:false},'std::vector<int> values;\nvalues.','values')
    expect(java.map(item=>item.label)).toEqual(expect.arrayContaining(['add','size']))
    expect(python.map(item=>item.label)).toEqual(expect.arrayContaining(['append','sort']))
    expect(cpp.map(item=>item.label)).toEqual(expect.arrayContaining(['push_back','begin']))
  })

  it('adds semantic spans only for code identifiers',()=>{
    const source='int solve(int value) {\n  int result = value;\n  // solve(result)\n  return result;\n}'
    const spans=collectSemanticSpans(source,'cpp')
    const names=spans.map(span=>source.slice(span.start,span.start+span.length))
    expect(names.filter(name=>name==='solve')).toHaveLength(1)
    expect(names).toEqual(expect.arrayContaining(['value','result']))
    expect(spans.some(span=>span.kind==='function')).toBe(true)
    expect(spans.some(span=>span.kind==='parameter')).toBe(true)
  })

  it('does not offer insertable completions for read-only models',()=>{
    expect(getLocalCompletions({language:'java',mode:'ACM',problemSpec:null,readOnly:true},'')).toEqual([])
  })

  it('offers declared node fields with C++ pointer access',()=>{
    const spec:ProblemSpec={...problemSpec,parameters:[{name:'start',type:'node<ColoredVertex>'}],dataStructures:[{name:'ColoredVertex',fields:[{name:'value',type:'int'},{name:'parent',type:'ref'}]}]}
    const source='start->'
    expect(memberReceiverAt(source,source.length)).toBe('start')
    expect(getLocalCompletions({language:'cpp',mode:'FUNCTION',problemSpec:spec,readOnly:false},source,'start').map(x=>x.label)).toEqual(['value','parent'])
  })
})

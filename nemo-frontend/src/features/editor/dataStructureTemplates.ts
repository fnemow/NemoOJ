import type { LanguageId } from '@/api/types'
import catalog from './dataStructureTemplates.json'
import { maskNonCode } from './languageSupport'

export const dataStructureTemplates = catalog

export interface TemplateInsertion { offset:number; text:string }

export function planTemplateInsertion(source:string,language:LanguageId,id:string):TemplateInsertion|null {
  const template=catalog.find(item=>item.id===id)
  if(!template)return null
  const clean=maskNonCode(source,language)
  if(new RegExp(`\\b(?:class|struct)\\s+${id}\\b`).test(clean))return null
  let offset=0
  if(language==='java') {
    for(const match of clean.matchAll(/\b(?:package|import)\s+[^;]+;/g))offset=match.index+match[0].length
  } else if(language==='python') {
    // Keep module docstrings, encoding declarations and future imports before inserted classes.
    for(const match of clean.matchAll(/^(?:from\s+[^\n]+\s+import|import)\s+[^\n]+/gm)) {
      let end=match.index+match[0].length
      if(match[0].includes('(')&&!match[0].includes(')')) { const closing=clean.indexOf(')',end);if(closing>=0)end=closing+1 }
      while(source[end-1]==='\\'&&end<source.length) { const next=source.indexOf('\n',end+1);end=next<0?source.length:next }
      offset=end
    }
    // A Python module docstring without imports also stays first.
    if(offset===0) {
      const header=source.match(/^(?:#[^\n]*(?:\n|$)|\s)*(?:"""[\s\S]*?"""|'''[\s\S]*?''')/)
      if(header)offset=header[0].length
      else {const comments=source.match(/^(?:#[^\n]*(?:\n|$)|\s)*/);offset=comments?.[0].length??0}
    }
  }
  const includes=language==='cpp'?'#include <vector>\n#include <stdexcept>\n#include <cstddef>\n':''
  return {offset,text:`${offset>0?'\n\n':''}${includes}${template[language]}\n\n`}
}

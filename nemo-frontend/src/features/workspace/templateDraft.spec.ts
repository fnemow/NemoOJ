import { describe, expect, it } from 'vitest'
import { previousTemplateVersions,restoreTemplateDraft } from './templateDraft'
import { retiredAcmIoTemplates } from './retiredAcmIoTemplates'

describe('hidden imports template upgrade', () => {
  const java = 'class Solution {\n    public int solve(int x) {\n        \n    }\n}\n'
  const cpp = 'class Solution {\npublic:\n    int solve(int x) {\n        \n    }\n};\n'
  it('refreshes untouched Java and C++ templates', () => {
    expect(restoreTemplateDraft('import java.util.*;\n' + java, java, 'java', 'FUNCTION')).toBe(java)
    expect(restoreTemplateDraft('#include <vector>\n#include <string>\n\nusing namespace std;\n\n' + cpp, cpp, 'cpp', 'FUNCTION')).toBe(cpp)
  })
  it('preserves edited code, explicit imports, line endings and other modes', () => {
    for (const saved of ['import java.util.*;\n' + java.replace('        \n', '        return x;\n'),
      'import java.math.*;\n' + java, ('import java.util.*;\n' + java).replaceAll('\n', '\r\n')]) {
      expect(restoreTemplateDraft(saved, java, 'java', 'FUNCTION')).toBe(saved)
    }
    expect(restoreTemplateDraft('import java.util.*;\n' + java, java, 'java', 'ACM')).toBe('import java.util.*;\n' + java)
    expect(restoreTemplateDraft('import math\nclass Solution: pass', 'class Solution: pass', 'python', 'FUNCTION')).toBe('import math\nclass Solution: pass')
  })
  it('updates only exact untouched ACM skeletons, keeping custom source byte for byte',()=>{
    const old={java:'public class Main {\n    public static void main(String[] args) {\n    }\n}\n',python:"import sys\n\ndef main():\n    pass\n\nif __name__ == '__main__':\n    main()\n",cpp:'#include <iostream>\n\nusing namespace std;\n\nint main() {\n    return 0;\n}\n'}
    for(const language of ['java','python','cpp'] as const){
      expect(restoreTemplateDraft(old[language],'new IO skeleton',language,'ACM')).toBe('new IO skeleton')
      const edited=old[language]+'// user edits'
      expect(restoreTemplateDraft(edited,'new IO skeleton',language,'ACM')).toBe(edited)
      expect(restoreTemplateDraft(old[language].replaceAll('\n','\r\n'),'new IO skeleton',language,'ACM')).toBe(old[language].replaceAll('\n','\r\n'))
    }
  })
  it('looks up the previous version only for the ACM IO upgrade',()=>{
    expect(previousTemplateVersions('java8-acm-io-v3','ACM')).toEqual(['java8-v2'])
    expect(previousTemplateVersions('cpp17-acm-io-v3','ACM')).toEqual(['cpp17-v2'])
    expect(previousTemplateVersions('python312-acm-io-v3','ACM')).toEqual(['python312-v2'])
    expect(previousTemplateVersions('java8-v2','ACM')).toEqual([])
    expect(previousTemplateVersions('java8-acm-io-v3','FUNCTION')).toEqual([])
    expect(previousTemplateVersions('java8-acm-basic-v4','ACM')).toEqual(['java8-acm-io-v3','java8-v2'])
  })
  it('removes only untouched retired IO defaults, preserving inserted algorithms',()=>{
    for(const language of ['java','python','cpp'] as const){
      const saved=retiredAcmIoTemplates[language]
      expect(restoreTemplateDraft(saved,'minimal starter',language,'ACM')).toBe('minimal starter')
      expect(restoreTemplateDraft(saved+'// my solution','minimal starter',language,'ACM')).toBe(saved+'// my solution')
    }
  })
})

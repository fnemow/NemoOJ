import type { ExecutionMode, LanguageId } from '@/api/types'
import { retiredAcmIoTemplates } from './retiredAcmIoTemplates'

const legacyAcmTemplates:Record<LanguageId,string>={
  java:'public class Main {\n    public static void main(String[] args) {\n    }\n}\n',
  python:"import sys\n\ndef main():\n    pass\n\nif __name__ == '__main__':\n    main()\n",
  cpp:'#include <iostream>\n\nusing namespace std;\n\nint main() {\n    return 0;\n}\n'
}

export function previousTemplateVersions(version:string,mode:ExecutionMode):string[]{
  if(mode!=='ACM')return []
  const basic=/^(.+)-acm-basic-v4$/.exec(version)
  if(basic)return [`${basic[1]}-acm-io-v3`,`${basic[1]}-v2`]
  const match=/^(.+)-acm-io-v3$/.exec(version)
  return match?[`${match[1]}-v2`]:[]
}

/** Upgrade only exact untouched defaults. Edited source is always preserved. */
export function restoreTemplateDraft(saved: string, template: string, language: LanguageId, mode: ExecutionMode) {
  if(mode==='ACM')return saved===legacyAcmTemplates[language]||saved===retiredAcmIoTemplates[language]?template:saved
  const prefix = language === 'java' ? 'import java.util.*;\n'
    : language === 'cpp' ? '#include <vector>\n#include <string>\n\nusing namespace std;\n\n' : ''
  return prefix && saved === prefix + template ? template : saved
}

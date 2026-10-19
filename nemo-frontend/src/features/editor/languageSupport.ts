import type { ExecutionMode,LanguageId,ProblemSpec,ValueType } from '@/api/types'

export interface EditorLanguageContext {
  language: LanguageId
  mode: ExecutionMode
  problemSpec: ProblemSpec|null
  readOnly: boolean
}

export type LocalCompletionKind='keyword'|'snippet'|'function'|'method'|'variable'|'class'|'constant'
export type SemanticKind='type'|'function'|'parameter'|'variable'|'constant'

export interface LocalCompletion {
  label: string
  insertText: string
  detail: string
  kind: LocalCompletionKind
  snippet?: boolean
  sortText: string
}

export interface DocumentSymbol { name:string;kind:Exclude<LocalCompletionKind,'keyword'|'snippet'|'method'> }
export interface SemanticSpan { start:number;length:number;kind:SemanticKind }

const KEYWORDS:Record<LanguageId,string[]>={
  java:['public','private','protected','static','final','class','interface','extends','implements','new','return','if','else','for','while','switch','case','break','continue','try','catch','throw','throws','null','true','false'],
  python:['def','class','return','if','elif','else','for','while','in','break','continue','try','except','raise','with','as','import','from','lambda','None','True','False','and','or','not'],
  cpp:['auto','bool','break','case','class','const','continue','default','do','double','else','enum','false','float','for','if','int','long','namespace','nullptr','private','public','return','static','struct','switch','template','true','using','void','while']
}

const CONTROL_WORDS=new Set(['if','for','while','switch','catch','return','sizeof','new','throw','def','class','print','input','range','len'])

const STATIC_COMPLETIONS:Record<LanguageId,LocalCompletion[]>={
  java:[
    snippet('main','public static void main(String[] args) {\n\t${1:// TODO}\n}','Java 8 程序入口','00'),
    snippet('fori','for (int ${1:i} = 0; ${1:i} < ${2:n}; ${1:i}++) {\n\t${3}\n}','索引循环','01'),
    snippet('foreach','for (${1:int} ${2:value} : ${3:values}) {\n\t${4}\n}','增强 for 循环','02'),
    snippet('readline','BufferedReader br = new BufferedReader(new InputStreamReader(System.in));\nString ${1:line} = br.readLine();','BufferedReader 输入','03'),
    snippet('scanner','Scanner ${1:scanner} = new Scanner(System.in);','Scanner 输入','04'),
    snippet('sout','System.out.println(${1});','标准输出','05'),
    plain('ArrayList','ArrayList<${1:Integer}>','Java 8 集合类型','class','10',true),
    plain('HashMap','HashMap<${1:Integer}, ${2:Integer}>','Java 8 映射类型','class','11',true),
    plain('Arrays.sort','Arrays.sort(${1:values});','数组排序','function','12',true),
    plain('Collections.sort','Collections.sort(${1:values});','集合排序','function','13',true),
    plain('Math.max','Math.max(${1:a}, ${2:b})','取最大值','function','14',true)
  ],
  python:[
    snippet('main',"if __name__ == '__main__':\n    ${1:main()}",'Python 程序入口','00'),
    snippet('def','def ${1:solve}(${2}):\n    ${3:pass}','定义函数','01'),
    snippet('for','for ${1:item} in ${2:items}:\n    ${3:pass}','for 循环','02'),
    snippet('fori','for ${1:i} in range(${2:n}):\n    ${3:pass}','索引循环','03'),
    snippet('while','while ${1:condition}:\n    ${2:pass}','while 循环','04'),
    snippet('readints','${1:values} = list(map(int, input().split()))','读取整数列表','05'),
    plain('enumerate','enumerate(${1:items})','带下标迭代','function','10',true),
    plain('sorted','sorted(${1:items})','返回排序结果','function','11',true),
    plain('collections.deque','from collections import deque\n${1:queue} = deque()','双端队列','class','12',true),
    plain('heapq','import heapq','最小堆模块','class','13'),
    plain('bisect_left','from bisect import bisect_left','二分查找','function','14')
  ],
  cpp:[
    snippet('main','int main() {\n    ${1:return 0;}\n}','C++17 程序入口','00'),
    snippet('fastio','std::ios::sync_with_stdio(false);\nstd::cin.tie(nullptr);','快速输入输出','01'),
    snippet('fori','for (int ${1:i} = 0; ${1:i} < ${2:n}; ++${1:i}) {\n    ${3}\n}','索引循环','02'),
    snippet('foreach','for (const auto& ${1:value} : ${2:values}) {\n    ${3}\n}','范围循环','03'),
    plain('std::vector','std::vector<${1:int}>','C++17 动态数组','class','10',true),
    plain('std::string','std::string','C++17 字符串','class','11'),
    plain('std::unordered_map','std::unordered_map<${1:int}, ${2:int}>','哈希表','class','12',true),
    plain('std::priority_queue','std::priority_queue<${1:int}>','优先队列','class','13',true),
    plain('std::sort','std::sort(${1:values}.begin(), ${1:values}.end());','范围排序','function','14',true),
    plain('std::lower_bound','std::lower_bound(${1:values}.begin(), ${1:values}.end(), ${2:target})','二分查找','function','15',true)
  ]
}

const MEMBER_COMPLETIONS:Record<LanguageId,Record<string,LocalCompletion[]>>={
  java:{
    list:members([['add','add(${1:value})'],['get','get(${1:index})'],['set','set(${1:index}, ${2:value})'],['size','size()'],['isEmpty','isEmpty()'],['contains','contains(${1:value})'],['clear','clear()']]),
    map:members([['put','put(${1:key}, ${2:value})'],['get','get(${1:key})'],['getOrDefault','getOrDefault(${1:key}, ${2:defaultValue})'],['containsKey','containsKey(${1:key})'],['keySet','keySet()'],['values','values()'],['size','size()']]),
    string:members([['length','length()'],['charAt','charAt(${1:index})'],['substring','substring(${1:start}, ${2:end})'],['indexOf','indexOf(${1:value})'],['equals','equals(${1:other})'],['toCharArray','toCharArray()']])
  },
  python:{
    list:members([['append','append(${1:value})'],['extend','extend(${1:items})'],['pop','pop(${1})'],['sort','sort()'],['reverse','reverse()'],['index','index(${1:value})'],['count','count(${1:value})']]),
    map:members([['get','get(${1:key}, ${2:default})'],['items','items()'],['keys','keys()'],['values','values()'],['setdefault','setdefault(${1:key}, ${2:default})'],['pop','pop(${1:key})']]),
    set:members([['add','add(${1:value})'],['discard','discard(${1:value})'],['remove','remove(${1:value})'],['union','union(${1:other})'],['intersection','intersection(${1:other})']]),
    string:members([['split','split(${1})'],['strip','strip()'],['join','join(${1:items})'],['find','find(${1:value})'],['replace','replace(${1:old}, ${2:new})'],['startswith','startswith(${1:prefix})']])
  },
  cpp:{
    list:members([['push_back','push_back(${1:value})'],['pop_back','pop_back()'],['size','size()'],['empty','empty()'],['front','front()'],['back','back()'],['begin','begin()'],['end','end()'],['clear','clear()']]),
    map:members([['find','find(${1:key})'],['count','count(${1:key})'],['at','at(${1:key})'],['size','size()'],['empty','empty()'],['erase','erase(${1:key})']]),
    set:members([['insert','insert(${1:value})'],['find','find(${1:value})'],['count','count(${1:value})'],['erase','erase(${1:value})'],['size','size()']]),
    string:members([['size','size()'],['substr','substr(${1:start}, ${2:length})'],['find','find(${1:value})'],['push_back','push_back(${1:char})'],['empty','empty()'],['clear','clear()']]),
    queue:members([['push','push(${1:value})'],['pop','pop()'],['front','front()'],['top','top()'],['empty','empty()'],['size','size()']])
  }
}

function snippet(label:string,insertText:string,detail:string,sortText:string):LocalCompletion{return {label,insertText,detail,kind:'snippet',snippet:true,sortText}}
function plain(label:string,insertText:string,detail:string,kind:LocalCompletionKind,sortText:string,isSnippet=false):LocalCompletion{return {label,insertText,detail,kind,snippet:isSnippet,sortText}}
function members(entries:Array<[string,string]>):LocalCompletion[]{return entries.map(([label,insertText],index)=>plain(label,insertText,'常用成员方法','method',`20${String(index).padStart(2,'0')}`,insertText.includes('${')))}

export function maskNonCode(source:string,language:LanguageId):string{
  const out=source.split('');let index=0
  const hide=(start:number,end:number)=>{for(let i=start;i<end;i++)if(out[i]!=="\n"&&out[i]!=="\r")out[i]='\0'}
  while(index<source.length){
    const char=source[index],next=source[index+1]
    if(language==='python'&&char==='#'){
      const start=index;while(index<source.length&&source[index]!=="\n")index++;hide(start,index);continue
    }
    if(language!=='python'&&char==='/'&&next==='/'){
      const start=index;index+=2;while(index<source.length&&source[index]!=="\n")index++;hide(start,index);continue
    }
    if(language!=='python'&&char==='/'&&next==='*'){
      const start=index;index+=2;while(index<source.length&&!(source[index]==='*'&&source[index+1]==='/'))index++;index=Math.min(source.length,index+2);hide(start,index);continue
    }
    if((char==='"'||char==="'")&&language==='python'&&source.slice(index,index+3)===char.repeat(3)){
      const start=index;index+=3;while(index<source.length&&source.slice(index,index+3)!==char.repeat(3))index++;index=Math.min(source.length,index+3);hide(start,index);continue
    }
    if(char==='"'||char==="'"){
      const start=index,quote=char;index++
      while(index<source.length){if(source[index]==='\\'){index+=2;continue}if(source[index]===quote){index++;break}index++}
      hide(start,index);continue
    }
    index++
  }
  return out.join('')
}

export function isCodePosition(source:string,language:LanguageId,offset:number):boolean{
  if(offset<=0)return true
  return maskNonCode(source,language)[Math.min(offset-1,source.length-1)]!=='\0'
}

export function memberReceiverAt(source:string,offset:number):string|null{
  return source.slice(Math.max(0,offset-160),offset).match(/([A-Za-z_]\w*)(?:\.|->)\w*$/)?.[1]??null
}

export function extractDocumentSymbols(source:string,language:LanguageId,limit=200):DocumentSymbol[]{
  const clean=maskNonCode(source,language),symbols:DocumentSymbol[]=[];const seen=new Set<string>()
  const add=(name:string|undefined,kind:DocumentSymbol['kind'])=>{if(!name||seen.has(name)||KEYWORDS[language].includes(name)||symbols.length>=limit)return;seen.add(name);symbols.push({name,kind})}
  if(language==='python'){
    each(clean,/\bclass\s+([A-Za-z_]\w*)/g,m=>add(m[1],'class'))
    each(clean,/\bdef\s+([A-Za-z_]\w*)\s*\(([^)]*)\)/g,m=>{add(m[1],'function');parameterNames(m[2]??'').forEach(name=>add(name,'variable'))})
    each(clean,/\b([A-Za-z_]\w*)\s*=\s*(?!=)/g,m=>add(m[1],isConstant(m[1]??'')?'constant':'variable'))
  }else{
    each(clean,/\b(?:class|interface|enum|struct)\s+([A-Za-z_]\w*)/g,m=>add(m[1],'class'))
    each(clean,/\b(?:[A-Za-z_]\w*(?:\s*<[^;{}()]+>)?(?:\s*[*&])?|void)\s+([A-Za-z_]\w*)\s*\(([^;{}()]*)\)\s*(?:\{|throws\b|;)/g,m=>{if(!CONTROL_WORDS.has(m[1]??''))add(m[1],'function');parameterNames(m[2]??'').forEach(name=>add(name,'variable'))})
    each(clean,/\b(?:final\s+|const\s+)?(?:[A-Za-z_]\w*(?:\s*<[^;={}()]+>)?(?:\s*[*&])?|int|long|double|float|boolean|bool|char|string|String)\s+([A-Za-z_]\w*)\s*(?=[=;,):])/g,m=>add(m[1],isConstant(m[1]??'')?'constant':'variable'))
  }
  return symbols
}

export function getLocalCompletions(context:EditorLanguageContext,source:string,receiver:string|null=null):LocalCompletion[]{
  if(context.readOnly)return []
  if(receiver){
    const type=context.mode==='FUNCTION'?context.problemSpec?.parameters.find(p=>p.name===receiver)?.type:undefined
    const node=type?.startsWith('node<')?context.problemSpec?.dataStructures?.find(d=>d.name===type.slice(5,-1)):undefined
    if(node)return node.fields.map((field,index)=>plain(field.name,field.name,`平台节点字段 · ${field.type}`,'variable',`04${index}`))
    const inferred=inferMemberType(context,source,receiver)
    return inferred?[...(MEMBER_COMPLETIONS[context.language][inferred]??[])]:[]
  }
  const items:LocalCompletion[]=[...KEYWORDS[context.language].map((word,index)=>plain(word,word,'语言关键字','keyword',`30${String(index).padStart(2,'0')}`)),...STATIC_COMPLETIONS[context.language]]
  if(context.mode==='FUNCTION'&&context.problemSpec){
    context.problemSpec.dataStructures?.forEach(node=>items.push(plain(node.name,node.name,'平台提供的节点类','class','06')))
    items.push(plain('Solution','Solution','题目要求的类名','class','06'))
    items.push(plain(context.problemSpec.methodName,methodCall(context.problemSpec),'题目要求实现的方法','function','05',true))
    context.problemSpec.parameters.forEach((parameter,index)=>items.push(plain(parameter.name,parameter.name,`题目参数 · ${displayType(context.language,parameter.type)}`,'variable',`07${String(index).padStart(2,'0')}`)))
  }
  extractDocumentSymbols(source,context.language).forEach((symbol,index)=>items.push(plain(symbol.name,symbol.name,'当前文件符号',symbol.kind,`40${String(index).padStart(3,'0')}`)))
  const unique=new Map<string,LocalCompletion>();for(const item of items)if(!unique.has(item.label))unique.set(item.label,item)
  return [...unique.values()].sort((a,b)=>a.sortText.localeCompare(b.sortText)||a.label.localeCompare(b.label))
}

export function collectSemanticSpans(source:string,language:LanguageId,limit=1000):SemanticSpan[]{
  const clean=maskNonCode(source,language),symbols=extractDocumentSymbols(source,language,300);const kinds=new Map<string,SemanticKind>()
  for(const symbol of symbols)kinds.set(symbol.name,symbol.kind==='class'?'type':symbol.kind==='function'?'function':symbol.kind==='constant'?'constant':'variable')
  if(language==='python')each(clean,/\bdef\s+[A-Za-z_]\w*\s*\(([^)]*)\)/g,m=>parameterNames(m[1]??'').forEach(name=>kinds.set(name,'parameter')))
  else each(clean,/\b[A-Za-z_]\w*(?:\s*<[^;{}()]+>)?(?:\s*[*&])?\s+[A-Za-z_]\w*\s*\(([^;{}()]*)\)/g,m=>parameterNames(m[1]??'').forEach(name=>kinds.set(name,'parameter')))
  each(clean,/\b([A-Za-z_]\w*)\s*(?=\()/g,m=>{const name=m[1];if(name&&!CONTROL_WORDS.has(name)&&!KEYWORDS[language].includes(name)&&!kinds.has(name))kinds.set(name,'function')})
  const spans:SemanticSpan[]=[];each(clean,/\b[A-Za-z_]\w*\b/g,m=>{const name=m[0],kind=kinds.get(name);if(kind&&spans.length<limit&&m.index!==undefined)spans.push({start:m.index,length:name.length,kind})})
  return spans
}

function inferMemberType(context:EditorLanguageContext,source:string,name:string):string|null{
  const clean=maskNonCode(source,context.language),escaped=name.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')
  if(context.problemSpec){const parameter=context.problemSpec.parameters.find(item=>item.name===name);if(parameter){if(parameter.type==='string')return 'string';if(parameter.type.includes('[]')||parameter.type.startsWith('list<'))return 'list'}}
  if(context.language==='python'){
    if(new RegExp(`\\b${escaped}\\s*=\\s*(?:\\[|list\\s*\\()`).test(clean))return 'list'
    if(new RegExp(`\\b${escaped}\\s*=\\s*(?:\\{|dict\\s*\\()`).test(clean))return 'map'
    if(new RegExp(`\\b${escaped}\\s*=\\s*set\\s*\\(`).test(clean))return 'set'
    if(new RegExp(`\\b${escaped}\\s*=\\s*(?:str\\s*\\(|["'])`).test(source))return 'string'
  }else if(context.language==='java'){
    if(new RegExp(`\\b(?:List|ArrayList|LinkedList|Collection)(?:\\s*<[^;=]+>)?\\s+${escaped}\\b`).test(clean))return 'list'
    if(new RegExp(`\\b(?:Map|HashMap|TreeMap)(?:\\s*<[^;=]+>)?\\s+${escaped}\\b`).test(clean))return 'map'
    if(new RegExp(`\\bString\\s+${escaped}\\b`).test(clean))return 'string'
  }else{
    if(new RegExp(`\\b(?:std::)?vector\\s*<[^;=]+>\\s+${escaped}\\b`).test(clean))return 'list'
    if(new RegExp(`\\b(?:std::)?(?:unordered_)?map\\s*<[^;=]+>\\s+${escaped}\\b`).test(clean))return 'map'
    if(new RegExp(`\\b(?:std::)?(?:unordered_)?set\\s*<[^;=]+>\\s+${escaped}\\b`).test(clean))return 'set'
    if(new RegExp(`\\b(?:std::)?string\\s+${escaped}\\b`).test(clean))return 'string'
    if(new RegExp(`\\b(?:std::)?(?:priority_)?queue\\s*<[^;=]+>\\s+${escaped}\\b`).test(clean)||new RegExp(`\\b(?:std::)?stack\\s*<[^;=]+>\\s+${escaped}\\b`).test(clean))return 'queue'
  }
  return null
}

function methodCall(spec:ProblemSpec):string{return `${spec.methodName}(${spec.parameters.map((parameter,index)=>`\${${index+1}:${parameter.name}}`).join(', ')})`}
function displayType(language:LanguageId,type:ValueType):string{if(language==='python')return type.includes('[]')||type.startsWith('list<')?'list':type==='string'?'str':type==='boolean'?'bool':type;return type}
function isConstant(name:string):boolean{return name.length>1&&name===name.toUpperCase()&&/[A-Z]/.test(name)}
function parameterNames(value:string):string[]{return value.split(',').map(part=>part.trim()).filter(Boolean).map(part=>part.replace(/=.*/,'').trim().match(/([A-Za-z_]\w*)\s*$/)?.[1]).filter((name):name is string=>Boolean(name)&&name!=='self')}
function each(value:string,pattern:RegExp,visit:(match:RegExpExecArray)=>void){let match:RegExpExecArray|null;while((match=pattern.exec(value))!==null){visit(match);if(match[0]==='')pattern.lastIndex++}}

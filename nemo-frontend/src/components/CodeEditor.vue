<script setup lang="ts">
import { nextTick,onBeforeUnmount,onMounted,ref,watch } from 'vue'
import type { ExecutionMode,LanguageId,ProblemSpec } from '@/api/types'
import { collectSemanticSpans,type EditorLanguageContext } from '@/features/editor/languageSupport'
import { bindEditorModel,DARK_THEME,LIGHT_THEME,registerMonacoLanguageSupport,unbindEditorModel } from '@/features/editor/monacoLanguageSupport'
import type * as Monaco from 'monaco-editor/esm/vs/editor/editor.api'
import { dataStructureTemplates,planTemplateInsertion } from '@/features/editor/dataStructureTemplates'

const props=defineProps<{
  modelValue:string
  language:LanguageId
  documentKey:string
  theme:'vs'|'vs-dark'
  fontSize:number
  mode:ExecutionMode
  problemSpec?:ProblemSpec|null|undefined
  readOnly?:boolean
  expanded?:boolean
}>()
const emit=defineEmits<{ 'update:modelValue':[value:string];requestSubmit:[];requestSave:[];requestRestore:[] }>()
const host=ref<HTMLElement>()
const mobileHost=ref<HTMLTextAreaElement>()
const mobile=ref(false)
const selectedTemplate=ref('RBNode')
const templateMessage=ref('辅助类用于解法内部；题目输入类型以函数签名为准。')
type MonacoApi=typeof Monaco
let monaco:MonacoApi
let editor:Monaco.editor.IStandaloneCodeEditor|undefined
let model:Monaco.editor.ITextModel|undefined
let decorations:Monaco.editor.IEditorDecorationsCollection|undefined
let observer:ResizeObserver|undefined
let semanticTimer:number|undefined
let internal=false
let expandedContext:Monaco.editor.IContextKey<boolean>|undefined
function insertDataStructure(){
  if(props.readOnly||props.mode!=='ACM')return
  const source=editor?.getValue()??props.modelValue
  const insertion=planTemplateInsertion(source,props.language,selectedTemplate.value)
  if(!insertion){templateMessage.value='该类已存在或模板不可用，未修改代码。';return}
  if(editor&&model){
    const position=model.getPositionAt(insertion.offset)
    editor.pushUndoStop()
    editor.executeEdits('data-structure-template',[{range:new monaco.Range(position.lineNumber,position.column,position.lineNumber,position.column),text:insertion.text,forceMoveMarkers:true}])
    editor.pushUndoStop()
    editor.revealLineInCenter(position.lineNumber)
    editor.focus()
  }else emit('update:modelValue',source.slice(0,insertion.offset)+insertion.text+source.slice(insertion.offset))
  templateMessage.value=dataStructureTemplates.find(item=>item.id===selectedTemplate.value)?.description??''
}
function revealLine(line:number){
  if(!Number.isInteger(line)||line<1)return
  if(editor&&model){const value=Math.min(line,model.getLineCount());editor.setPosition({lineNumber:value,column:1});editor.revealLineInCenter(value);editor.focus()}
  else if(mobileHost.value){const lines=props.modelValue.split('\n');const value=Math.min(line,lines.length);const start=lines.slice(0,value-1).reduce((n,s)=>n+s.length+1,0);mobileHost.value.focus();mobileHost.value.setSelectionRange(start,start+(lines[value-1]?.length??0));mobileHost.value.scrollTop=Math.max(0,(value-4)*22)}
}
defineExpose({revealLine})

function editorContext():EditorLanguageContext{return {language:props.language,mode:props.mode,problemSpec:props.problemSpec??null,readOnly:Boolean(props.readOnly)}}
function modelUri(){return monaco.Uri.parse(`inmemory://nemooj/${encodeURIComponent(props.documentKey)}`)}
function editorTheme(){return props.theme==='vs-dark'?DARK_THEME:LIGHT_THEME}

function scheduleSemanticHighlight(){
  window.clearTimeout(semanticTimer)
  semanticTimer=window.setTimeout(refreshSemanticHighlight,120)
}

function refreshSemanticHighlight(){
  if(!model||!decorations)return
  const value=model.getValue()
  decorations.set(collectSemanticSpans(value,props.language).map(span=>{
    const start=model!.getPositionAt(span.start)
    const end=model!.getPositionAt(span.start+span.length)
    return {range:new monaco.Range(start.lineNumber,start.column,end.lineNumber,end.column),options:{inlineClassName:`nemooj-sem-${span.kind}`}}
  }))
}

function replaceModel(){
  if(!editor)return
  const oldModel=model
  if(oldModel)unbindEditorModel(oldModel)
  model=monaco.editor.createModel(props.modelValue,props.language,modelUri())
  bindEditorModel(model,editorContext())
  editor.setModel(model)
  decorations?.clear()
  scheduleSemanticHighlight()
  oldModel?.dispose()
}

onMounted(async()=>{
  mobile.value=matchMedia('(max-width: 767px)').matches
  if(mobile.value)return
  const [monacoModule,workerModule]=await Promise.all([
    import('monaco-editor/esm/vs/editor/edcore.main'),
    import('monaco-editor/esm/vs/editor/editor.worker?worker')
  ])
  monaco=monacoModule
  const EditorWorker=workerModule.default
  ;(self as unknown as {MonacoEnvironment?:unknown}).MonacoEnvironment={getWorker:()=>new EditorWorker()}
  await Promise.all([
    import('monaco-editor/esm/vs/basic-languages/java/java.contribution'),
    import('monaco-editor/esm/vs/basic-languages/python/python.contribution'),
    import('monaco-editor/esm/vs/basic-languages/cpp/cpp.contribution')
  ])
  registerMonacoLanguageSupport(monaco)
  await nextTick()
  if(!host.value)return
  model=monaco.editor.createModel(props.modelValue,props.language,modelUri())
  bindEditorModel(model,editorContext())
  editor=monaco.editor.create(host.value,{
    model,theme:editorTheme(),fontSize:props.fontSize,lineHeight:Math.max(22,Math.round(props.fontSize*1.6)),tabSize:4,insertSpaces:true,
    minimap:{enabled:false},wordWrap:'off',automaticLayout:false,readOnly:props.readOnly,scrollBeyondLastLine:false,
    padding:{top:12},bracketPairColorization:{enabled:true},autoClosingBrackets:'always',autoClosingQuotes:'always',
    autoIndent:'full',formatOnType:false,quickSuggestions:{other:true,comments:false,strings:false},
    suggestOnTriggerCharacters:true,wordBasedSuggestions:'currentDocument',snippetSuggestions:'top',tabCompletion:'on',
    acceptSuggestionOnEnter:'on',parameterHints:{enabled:true,cycle:true},suggest:{showKeywords:true,showSnippets:true,showMethods:true,showFunctions:true,showVariables:true,showClasses:true,showConstants:true}
  })
  decorations=editor.createDecorationsCollection()
  editor.onDidChangeModelContent(()=>{if(!internal)emit('update:modelValue',editor?.getValue()??'');scheduleSemanticHighlight()})
  editor.addCommand(monaco.KeyMod.CtrlCmd|monaco.KeyCode.Enter,()=>emit('requestSubmit'))
  editor.addCommand(monaco.KeyMod.CtrlCmd|monaco.KeyCode.KeyS,()=>emit('requestSave'))
  expandedContext=editor.createContextKey('nemooj.editorExpanded',Boolean(props.expanded))
  editor.addCommand(monaco.KeyCode.Escape,()=>emit('requestRestore'),'nemooj.editorExpanded && !suggestWidgetVisible && !parameterHintsVisible && !findWidgetVisible && !renameInputVisible')
  observer=new ResizeObserver(()=>editor?.layout())
  observer.observe(host.value)
  refreshSemanticHighlight()
})

watch(()=>props.modelValue,value=>{if(editor&&editor.getValue()!==value){internal=true;editor.setValue(value);internal=false;scheduleSemanticHighlight()}})
watch(()=>props.documentKey,replaceModel)
watch(()=>props.theme,()=>{if(monaco)monaco.editor.setTheme(editorTheme())})
watch(()=>props.fontSize,value=>editor?.updateOptions({fontSize:value,lineHeight:Math.max(22,Math.round(value*1.6))}))
watch(()=>props.expanded,value=>expandedContext?.set(Boolean(value)))
watch(()=>props.readOnly,value=>{editor?.updateOptions({readOnly:value});if(model)bindEditorModel(model,editorContext())})
watch(()=>[props.language,props.mode,props.problemSpec] as const,([language],oldValue)=>{
  if(model){if(oldValue&&language!==oldValue[0])monaco.editor.setModelLanguage(model,language);bindEditorModel(model,editorContext());scheduleSemanticHighlight()}
},{deep:true})

onBeforeUnmount(()=>{
  window.clearTimeout(semanticTimer)
  observer?.disconnect()
  decorations?.clear()
  if(model)unbindEditorModel(model)
  editor?.dispose()
  model?.dispose()
})
</script>

<template>
  <div class="code-editor-shell" :style="{ '--editor-font-size': `${fontSize}px`, '--editor-line-height': `${Math.max(22,Math.round(fontSize*1.6))}px` }">
  <div v-if="!readOnly&&mode==='ACM'" class="structure-toolbar">
    <label>数据结构 <select v-model="selectedTemplate" aria-label="选择数据结构模板"><option v-for="item in dataStructureTemplates" :key="item.id" :value="item.id">{{ item.label }} · {{ item.id }}</option></select></label>
    <button type="button" @click="insertDataStructure">插入模板</button>
    <span role="status" :title="templateMessage">{{ templateMessage }}</span>
  </div>
  <textarea v-if="mobile" ref="mobileHost" class="mobile-editor mono" :value="modelValue" :readonly="readOnly" spellcheck="false" autocomplete="off" autocapitalize="off" autocorrect="off" aria-label="代码编辑器" @input="emit('update:modelValue',($event.target as HTMLTextAreaElement).value)"/>
  <div v-else ref="host" class="editor"/>
  </div>
</template>

<style>
.code-editor-shell{height:100%;min-height:0;display:flex;flex-direction:column}
.structure-toolbar{display:flex;align-items:center;gap:10px;padding:6px 12px;background:var(--oj-surface);border-bottom:1px solid var(--oj-border);font-size:12px;flex-wrap:wrap}
.structure-toolbar label{display:flex;align-items:center;gap:6px;white-space:nowrap}
.structure-toolbar select,.structure-toolbar button{background:var(--oj-surface);color:var(--oj-text);border:1px solid var(--oj-border);border-radius:6px;padding:4px 8px;font:inherit}
.structure-toolbar button{cursor:pointer}.structure-toolbar span{color:var(--oj-text-muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;min-width:120px}
.editor{flex:1;min-height:0}
.code-editor-shell .mobile-editor{flex:1;min-height:260px}
.mobile-editor{width:100%;height:100%;min-height:360px;resize:vertical;border:0;padding:14px;background:var(--oj-surface);color:var(--oj-text);line-height:var(--editor-line-height,22px);font-size:var(--editor-font-size,14px)}
.nemooj-sem-type{color:#56b6c2!important;font-weight:600}
.nemooj-sem-function{color:#dcdcaa!important}
.nemooj-sem-parameter{color:#e5c07b!important;font-style:italic}
.nemooj-sem-variable{color:#9cdcfe!important}
.nemooj-sem-constant{color:#c586c0!important;font-weight:600}
.vs .nemooj-sem-type{color:#007c91!important}
.vs .nemooj-sem-function{color:#8a6116!important}
.vs .nemooj-sem-parameter{color:#986801!important}
.vs .nemooj-sem-variable{color:#005cc5!important}
.vs .nemooj-sem-constant{color:#6f42c1!important}
</style>

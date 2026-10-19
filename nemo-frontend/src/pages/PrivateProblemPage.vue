<script setup lang="ts">
import RefreshButton from '@/components/RefreshButton.vue'
import EditorExpandButton from '@/components/EditorExpandButton.vue'
import ExecutionModeSelect from '@/components/ExecutionModeSelect.vue'
import { useEditorExpansion } from '@/features/workspace/useEditorExpansion'
import { computed,ref,toRef,watch } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage } from '@/app/messages'
import { getPrivateProblem,getPrivateProblemSubmissions,submitPrivateProblem } from '@/api/ai'
import { getSubmission } from '@/api/submission'
import type { ExecutionMode,LanguageId } from '@/api/types'
import CodeEditor from '@/components/CodeEditor.vue'
import SafeMarkdown from '@/components/SafeMarkdown.vue'
import CustomStructureInfo from '@/components/CustomStructureInfo.vue'
import JudgeStatusBadge from '@/components/JudgeStatusBadge.vue'
import { readableError } from '@/api/errors'
import { usePreferencesStore } from '@/stores/preferences'
import { formatMemory } from '@/utils/format'

const route=useRoute(),prefs=usePreferencesStore(),id=computed(()=>String(route.params.id)),language=ref<LanguageId>('java'),mode=ref<ExecutionMode>('ACM'),code=ref(''),busy=ref(false),submissionId=ref('')
const {editorExpanded,editorFontSize,toggleEditor}=useEditorExpansion(toRef(prefs,'fontSize'),id)
const languages:LanguageId[]=['java','python','cpp']
const problem=useQuery({queryKey:computed(()=>['private-problem',id.value]),queryFn:({signal})=>getPrivateProblem(id.value,signal),refetchInterval:q=>q.state.data?.status==='GENERATING'?2000:false})
const version=computed(()=>problem.data.value?.currentVersion)
const templates=computed(()=>version.value?.templates??[])
const samples=computed(()=>mode.value==='FUNCTION'?version.value?.functionSamples??version.value?.samples??[]:version.value?.samples??[])
const availableModes=computed(()=>version.value?.availableModes.filter(item=>templates.value.some(t=>t.language===language.value&&t.mode===item))??[])
watch(availableModes,modes=>{if(!modes.includes(mode.value)&&modes[0])mode.value=modes[0]},{immediate:true})
const currentTemplate=computed(()=>templates.value.find(v=>v.language===language.value&&v.mode===mode.value))
let activeTemplateKey='',initialTemplateCode=''
watch([currentTemplate,()=>version.value?.id,language,mode,id],([template,ver,lang,currentMode,problemId])=>{
  if(!template)return
  const key=`${problemId}-${ver}-${lang}-${currentMode}`
  if(key!==activeTemplateKey||code.value===initialTemplateCode)code.value=template.code
  activeTemplateKey=key;initialTemplateCode=template.code
},{immediate:true})
const recent=useQuery({queryKey:computed(()=>['private-problem-submissions',id.value]),queryFn:({signal})=>getPrivateProblemSubmissions(id.value,1,signal)})
const result=useQuery({queryKey:computed(()=>['submission',submissionId.value]),queryFn:({signal})=>getSubmission(submissionId.value,signal),enabled:computed(()=>Boolean(submissionId.value)),refetchInterval:q=>q.state.data&&(q.state.data.status===2||q.state.data.status===3)?false:1500})
async function submit(){if(!version.value||!currentTemplate.value||busy.value)return;busy.value=true;try{submissionId.value=await submitPrivateProblem(id.value,{problemVersion:version.value.id,language:language.value,executionMode:mode.value,code:code.value,clientRequestId:crypto.randomUUID()});await recent.refetch();ElMessage.success({ message: '私人题已提交' })}catch(e){ElMessage.error({ message: readableError(e) })}finally{busy.value=false}}
</script>
<template><div class="private-workspace" :class="{ 'editor-expanded': editorExpanded }"><div class="private-head"><div><RouterLink to="/study-plans?tab=private">← 返回私人题目</RouterLink><h1>{{problem.data.value?.title??'私人题'}}</h1><p><el-tag type="warning">AI 生成 · 仅自己可见</el-tag><span>{{problem.data.value?.difficulty}}</span><span>{{problem.data.value?.tags?.join(' · ')}}</span></p></div><RefreshButton @click="problem.refetch()" :loading="problem.isFetching.value">刷新</RefreshButton></div>
  <el-alert v-if="problem.data.value?.status==='GENERATING'" title="题目正在生成并通过多语言沙箱验证…" type="info" :closable="false"/>
  <el-alert v-else-if="problem.data.value?.status==='REJECTED'" :title="problem.data.value.rejectReason||'题目未通过校验'" type="error" :closable="false"/>
  <div v-else-if="version" class="private-split"><section class="statement surface"><SafeMarkdown :source="version.content"/><CustomStructureInfo v-if="mode==='FUNCTION'" :spec="version.problemSpec"/><section v-for="(sample,index) in samples" :key="index"><h3>示例 {{index+1}}</h3><pre>输入：{{sample.input}}</pre><pre>输出：{{sample.output}}</pre></section></section><section class="editor surface"><div class="toolbar"><el-select v-model="language" aria-label="编程语言"><el-option v-for="item in languages" :key="item" :label="item" :value="item" :disabled="!templates.some(t=>t.language===item&&t.mode===mode)"/></el-select><ExecutionModeSelect v-model="mode" :available-modes="availableModes"/><span class="toolbar-spacer" aria-hidden="true"/><EditorExpandButton :expanded="editorExpanded" @toggle="toggleEditor"/><el-button type="primary" :loading="busy" :disabled="!currentTemplate" @click="submit">提交</el-button></div><CodeEditor v-model="code" :language="language" :mode="mode" :problem-spec="version.problemSpec" :document-key="`private-${id}-${version.id}-${language}-${mode}`" :theme="prefs.editorTheme" :font-size="editorFontSize" :expanded="editorExpanded" @request-restore="editorExpanded=false"/><div class="result"><div class="result-head"><strong>本次判题</strong><RefreshButton v-if="submissionId" size="small" :loading="result.isFetching.value" @click="result.refetch()">刷新结果</RefreshButton></div><div v-if="result.data.value" class="result-data"><JudgeStatusBadge :status="result.data.value.status" :verdict="result.data.value.verdict||result.data.value.judgeInfo?.message"/><span>耗时 {{result.data.value.judgeInfo?.executedTime??'—'}} ms</span><span>内存 {{formatMemory(result.data.value.judgeInfo?.executedMemory)}}</span></div><p v-else class="muted">提交后自动监视判题结果。</p></div><div class="history"><h3>最近提交</h3><RouterLink v-for="entry in recent.data.value?.records??[]" :key="entry.id" :to="`/submissions/${entry.id}`">#{{entry.id}} · {{entry.verdict||entry.judgeInfo?.message||'判题中'}}</RouterLink></div></section></div>
  <div v-else-if="problem.isError.value" class="surface error">{{readableError(problem.error.value)}}</div></div></template>
<style scoped>.private-workspace{min-height:100dvh;padding:24px;background:var(--oj-bg);color:var(--oj-text)}.private-head{display:flex;justify-content:space-between;align-items:flex-start;margin:0 auto 18px;max-width:1500px}.private-head h1{margin:14px 0 8px}.private-head p{display:flex;gap:12px;align-items:center;color:var(--oj-text-muted)}.private-head a{color:var(--oj-accent)}.private-split{max-width:1500px;margin:auto;display:grid;grid-template-columns:minmax(320px,42%) minmax(0,58%);gap:16px}.surface{background:var(--oj-surface);border:1px solid var(--oj-border);border-radius:10px}.statement{padding:24px;max-height:calc(100dvh - 145px);overflow:auto}.statement pre{white-space:pre-wrap;background:#0b121d;padding:12px;border-radius:7px}.editor{min-height:calc(100dvh - 120px);display:grid;grid-template-rows:54px minmax(280px,1fr) auto auto;overflow:hidden}.toolbar{display:flex;align-items:center;gap:10px;padding:8px 12px;background:#101927}.toolbar .el-select{width:130px}.toolbar .toolbar-spacer{flex:1;min-width:0}.result,.history{padding:14px;border-top:1px solid var(--oj-border)}.result-head,.result-data{display:flex;justify-content:space-between;align-items:center;gap:12px}.muted{color:var(--oj-text-muted);font-size:13px}.history{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.history h3{width:100%;margin:0}.history a{font-size:12px;color:var(--oj-accent)}.error{max-width:900px;margin:30px auto;padding:24px;color:var(--oj-danger)}@media(max-width:900px){.private-workspace{padding:12px}.private-split{grid-template-columns:1fr}.statement{max-height:36dvh}.editor{min-height:65dvh}}@media(max-width:600px){.toolbar{flex-wrap:wrap;height:auto}.editor{grid-template-rows:auto minmax(360px,1fr) auto auto}.private-head p{align-items:flex-start;flex-direction:column;gap:5px}}
.private-workspace.editor-expanded .private-head{display:none}
.private-workspace.editor-expanded{height:100dvh;min-height:0;padding:0;overflow:hidden}
.editor-expanded .private-split{position:fixed;inset:0 0 0 var(--oj-sidebar-width);max-width:none;z-index:15;display:block;background:var(--oj-bg)}
.editor-expanded .statement,.editor-expanded .result,.editor-expanded .history{display:none}
.editor-expanded .editor{height:100%;min-height:0;border:0;border-radius:0;grid-template-rows:54px minmax(0,1fr)}
@media(max-width:900px){.private-workspace.editor-expanded{height:calc(100dvh - 64px)}.editor-expanded .private-split{left:0;top:64px}}
@media(max-width:600px){.editor-expanded .editor{grid-template-rows:auto minmax(0,1fr)}.editor-expanded .toolbar-spacer{display:none}.editor-expanded .editor :deep(.mobile-editor){min-height:0;resize:none}}
</style>

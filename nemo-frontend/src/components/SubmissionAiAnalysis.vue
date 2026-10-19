<script setup lang="ts">
import RefreshButton from '@/components/RefreshButton.vue'
import { computed, ref, watch } from 'vue'
import { useMutation, useQuery } from '@tanstack/vue-query'
import { getAiEntitlements, getAiTask, getAnalysis, requestAnalysis } from '@/api/ai'
import { ApiError, readableError } from '@/api/errors'
import LoadingSpinner from './LoadingSpinner.vue'

const props = defineProps<{ userId: string; submissionId: string; status: number; verdict?: string | null | undefined }>()
const emit=defineEmits<{navigateLine:[line:number]}>()
const level=ref<'hint'|'approach'|'detail'>('hint')
function navigate(line:number){if(Number.isInteger(line)&&line>0){open.value=false;emit('navigateLine',line)}}
const open = ref(false)
const finished = computed(() => props.status === 2 || props.status === 3)
const accepted = computed(() => props.verdict?.toLowerCase() === 'accepted')
const storageKey = computed(() => `nemooj.ai.analysis.task.${props.userId}.${props.submissionId}`)
const taskId = ref<string | null>(null)
watch(storageKey, key => {
  try { taskId.value = localStorage.getItem(key) } catch { taskId.value = null }
  open.value = false
  level.value='hint'
}, { immediate: true })
const entitlements = useQuery({
  queryKey: computed(() => ['ai-entitlements', props.userId]),
  queryFn: ({ signal }) => getAiEntitlements(signal), retry: false
})
const analysis = useQuery({
  queryKey: computed(() => ['ai-analysis', props.userId, props.submissionId]),
  queryFn: async ({ signal }) => {
    try { return await getAnalysis(props.submissionId, signal) }
    catch (error) { if (error instanceof ApiError && error.code === 40400) return null; throw error }
  },
  enabled: finished, retry: false
})
const task = useQuery({
  queryKey: computed(() => ['ai-task', props.userId, taskId.value]),
  queryFn: ({ signal }) => getAiTask(taskId.value!, signal),
  enabled: computed(() => !!taskId.value), retry: false,
  refetchInterval: query => query.state.error || ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(query.state.data?.status ?? '') ? false : 2000
})
const busy = computed(() => ['QUEUED', 'RUNNING', 'WAITING_REVIEW'].includes(task.data.value?.status ?? ''))
const exhausted = computed(() => entitlements.data.value?.tier === 'FREE' && (entitlements.data.value.remainingToday ?? 0) <= 0)
const taskLabels: Record<string, string> = { QUEUED: '排队中', RUNNING: '正在分析代码', WAITING_REVIEW: '等待审核', SUCCEEDED: '分析完成', FAILED: '分析失败', CANCELLED: '已取消' }
const taskLabel = computed(() => taskLabels[task.data.value?.status ?? ''] ?? '')
const readingReport = computed(() => task.data.value?.status === 'SUCCEEDED' && !analysis.data.value && analysis.isFetching.value)
const showProgress = computed(() => mutation.isPending.value || busy.value || readingReport.value)
const progressLabel = computed(() => mutation.isPending.value ? '正在创建分析任务' : readingReport.value ? '正在读取分析报告' : taskLabel.value)
const progressHint = computed(() => task.data.value?.message || (task.data.value?.status === 'QUEUED' ? '任务已进入队列，完成后会自动更新。' : '请稍候，结果会自动刷新。'))
const mutation = useMutation({
  mutationFn: (input: { submissionId: string; key: string }) => requestAnalysis(input.submissionId, crypto.randomUUID()),
  onSuccess: (result, input) => {
    if (input.key !== storageKey.value) return
    taskId.value = result.taskId ?? null
    try { if (taskId.value) localStorage.setItem(input.key, taskId.value) } catch { /* Storage is optional. */ }
    if (result.status === 'SUCCEEDED' || result.analysisId) void analysis.refetch()
    void entitlements.refetch()
  }
})
watch(() => task.data.value?.status, status => {
  if (status === 'SUCCEEDED') void analysis.refetch()
  if (status && ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(status)) void entitlements.refetch()
})
function start() { mutation.mutate({ submissionId: props.submissionId, key: storageKey.value }) }
function show() {
  open.value = true
  if (!analysis.data.value && !busy.value && task.data.value?.status !== 'SUCCEEDED' && !task.isError.value && !analysis.isError.value && !exhausted.value && entitlements.data.value?.analysisEnabled && !mutation.isPending.value) start()
}
</script>

<template>
  <section class="ai-entry" aria-label="AI 代码分析">
    <div><strong>AI 代码分析</strong><p>{{ finished ? '分析这次提交的代码：错误定位、改进建议与复杂度评估。' : '判题完成后，可获取错误分析或优化建议。' }}</p></div>
    <el-button type="primary" :disabled="!finished || entitlements.isPending.value || analysis.isPending.value" @click="show">{{ analysis.data.value ? '查看 AI 分析' : busy ? '查看分析进度' : accepted ? 'AI 优化建议' : 'AI 错误分析' }}</el-button>
  </section>
  <el-drawer v-model="open" title="AI 代码分析" size="min(620px, 100vw)" append-to-body>
    <div class="analysis-panel">
      <div class="analysis-meta">
        <span>提交 #{{ submissionId }} · {{ accepted ? '优化建议' : '错误分析与改进' }}</span>
        <span v-if="entitlements.data.value" class="analysis-quota">{{ entitlements.data.value.vipActive ? 'VIP · 每日分析不限次' : `今日剩余 ${entitlements.data.value.remainingToday ?? 0}/${entitlements.data.value.dailyLimit ?? 5} 次` }}</span>
      </div>
      <p v-if="entitlements.isError.value" role="alert">{{ readableError(entitlements.error.value) }} <el-button @click="entitlements.refetch()">重试读取额度</el-button></p>
      <p v-else-if="!entitlements.data.value?.analysisEnabled && !analysis.data.value">AI 分析暂不可用，请稍后重试。</p>
      <p v-if="mutation.isError.value" role="alert">{{ readableError(mutation.error.value) }}</p>
      <div v-if="showProgress" class="analysis-progress" role="status" aria-live="polite" aria-busy="true">
        <LoadingSpinner />
        <div><strong>{{ progressLabel }}</strong><p>{{ progressHint }}</p></div>
      </div>
      <p v-else-if="task.data.value" class="analysis-task-state" :class="task.data.value.status.toLowerCase()" role="status">{{ taskLabel }}<span v-if="task.data.value.message"> · {{ task.data.value.message }}</span></p>
      <p v-if="task.isError.value" role="alert">进度读取失败：{{ readableError(task.error.value) }}</p>
      <RefreshButton v-if="taskId || analysis.isError.value" :loading="task.isFetching.value || analysis.isFetching.value" @click="taskId && task.refetch(); analysis.refetch()">刷新分析结果</RefreshButton>
      <div v-if="analysis.data.value" class="analysis-report">
        <el-radio-group v-model="level" aria-label="分析详细程度"><el-radio-button value="hint">提示</el-radio-button><el-radio-button value="approach">思路</el-radio-button><el-radio-button value="detail">详细报告</el-radio-button></el-radio-group>
        <section class="analysis-section cause">
          <h3>{{ accepted ? '代码评估' : '错误原因' }}</h3>
          <p>{{ analysis.data.value.report.likelyCause }}</p>
        </section>
        <section v-if="analysis.data.value.report.locations.length" class="analysis-section locations">
          <h3>相关位置</h3>
          <div v-for="(item,index) in analysis.data.value.report.locations" :key="index" class="location-item">
            <button v-if="item.line" class="location-line" @click="navigate(item.line)" :aria-label="`查看提交代码第 ${item.line} 行`">第 {{ item.line }} 行 ↗</button>
            <strong>{{ item.symbol }}</strong>
            <p v-if="level!=='hint'">{{ item.explanation }}</p>
          </div>
        </section>
        <section class="analysis-section suggestions">
          <h3>改进建议</h3>
          <article v-for="(item,index) in (level==='hint'?analysis.data.value.report.suggestions.slice(0,1):analysis.data.value.report.suggestions)" :key="index" class="suggestion-card">
            <strong>{{ item.title }}</strong><p v-if="level!=='hint'">{{ item.detail }}</p><small v-if="level==='detail'">{{ item.impact }}</small>
          </article>
        </section>
        <section v-if="level==='detail'" class="analysis-section complexity">
          <h3>时间与空间复杂度</h3>
          <div class="complexity-values"><span>时间 <strong>{{ analysis.data.value.report.complexity.time }}</strong></span><span>空间 <strong>{{ analysis.data.value.report.complexity.space }}</strong></span></div>
          <p>{{ analysis.data.value.report.complexity.explanation }}</p>
          <small>复杂度为模型估计，请结合实现与数据范围核对。</small>
        </section>
        <p v-if="analysis.data.value.report.uncertainty" class="uncertainty">{{ analysis.data.value.report.uncertainty }}</p>
      </div>
      <p v-else-if="analysis.isError.value" role="alert">报告读取失败：{{ readableError(analysis.error.value) }}</p>
      <p v-else-if="exhausted && !busy">今日分析额度已用完，已有报告仍可查看。</p>
      <el-button v-if="!analysis.data.value && !busy && !mutation.isPending.value && entitlements.data.value?.analysisEnabled && !exhausted && !analysis.isError.value && !task.isError.value && task.data.value?.status !== 'SUCCEEDED'" type="primary" @click="start">{{ mutation.isError.value || task.data.value?.status === 'FAILED' ? '重试分析' : '分析本次提交' }}</el-button>
    </div>
  </el-drawer>
</template>

<style scoped>
.ai-entry { display:flex; align-items:center; justify-content:space-between; gap:12px; margin:14px 0; padding:12px; border:1px solid var(--oj-border); border-radius:8px; background:var(--oj-surface); }
.ai-entry strong { font-size:14px; }
.ai-entry p { font-size:12px; color:var(--oj-text-muted); margin:6px 0 0; }
.analysis-panel { line-height:1.7; overflow-wrap:anywhere; }
.analysis-panel p { white-space:pre-wrap; }
.analysis-meta { display:flex; flex-wrap:wrap; align-items:center; gap:8px 16px; color:var(--oj-text-muted); font-size:12px; }
.analysis-quota { color:var(--oj-primary); }
.analysis-progress { display:flex; align-items:center; gap:14px; margin:18px 0; padding:15px 16px; border:1px solid rgba(122,162,247,.34); border-radius:10px; background:rgba(122,162,247,.08); color:var(--oj-primary); }
.analysis-progress :deep(.loading-spinner) { width:22px; height:22px; border-width:3px; }
.analysis-progress strong { color:var(--oj-text); }
.analysis-progress p { margin:2px 0 0; color:var(--oj-text-muted); font-size:12px; }
.analysis-task-state { margin:18px 0; font-weight:600; }
.analysis-task-state.succeeded { color:var(--oj-success); }
.analysis-task-state.failed,.analysis-task-state.cancelled { color:var(--oj-danger); }
.analysis-report { display:grid; gap:14px; margin-top:20px; }
.analysis-section { --section-color:var(--oj-primary); padding:16px 18px; border:1px solid var(--oj-border); border-left:3px solid var(--section-color); border-radius:9px; background:var(--oj-surface); }
.analysis-section h3 { display:flex; align-items:center; gap:9px; margin:0 0 10px; color:var(--section-color); font-size:15px; }
.analysis-section h3::before { content:""; width:7px; height:7px; flex:none; border-radius:50%; background:currentColor; }
.analysis-section p { margin:0; color:var(--oj-text); }
.cause { --section-color:#f2aa82; background:linear-gradient(135deg,rgba(242,170,130,.075),var(--oj-surface) 55%); }
.locations { --section-color:#c6a9f3; background:linear-gradient(135deg,rgba(198,169,243,.075),var(--oj-surface) 55%); }
.suggestions { --section-color:#86d2a5; background:linear-gradient(135deg,rgba(134,210,165,.07),var(--oj-surface) 55%); }
.complexity { --section-color:#86c9df; background:linear-gradient(135deg,rgba(134,201,223,.075),var(--oj-surface) 55%); }
.location-item + .location-item { margin-top:12px; padding-top:12px; border-top:1px solid var(--oj-border); }
.location-item strong { color:var(--oj-text); }
.location-line { display:inline-block; margin-right:9px; color:var(--section-color); font-size:12px; font-weight:700; }
.location-line { cursor:pointer;border:1px solid currentColor;border-radius:4px;background:transparent;padding:2px 6px; }
.location-item p { margin-top:4px; color:var(--oj-text-muted); }
.suggestion-card { padding:12px 14px; border:1px solid rgba(134,210,165,.25); border-radius:8px; background:rgba(134,210,165,.045); }
.suggestion-card + .suggestion-card { margin-top:10px; }
.suggestion-card strong { color:#a9e4bc; }
.suggestion-card p { margin:7px 0; }
.suggestion-card small { color:var(--oj-text-muted); }
.complexity-values { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:10px; }
.complexity-values span { padding:5px 9px; border:1px solid rgba(134,201,223,.25); border-radius:6px; background:rgba(134,201,223,.07); color:var(--oj-text-muted); font-size:12px; }
.complexity-values strong { margin-left:5px; color:#a9ddeb; }
.uncertainty { margin:0; padding:12px 14px; border:1px solid rgba(229,181,103,.3); border-radius:8px; color:var(--oj-warning); background:rgba(229,181,103,.07); }
@media(max-width:480px) { .ai-entry { align-items:flex-start; flex-direction:column; } .analysis-section { padding:14px; } }
</style>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { ElMessageBox } from 'element-plus';import { ElMessage } from '@/app/messages'
import AsyncState from '@/components/AsyncState.vue'
import JudgeStatusBadge from '@/components/JudgeStatusBadge.vue'
import { getSubmission, watchSubmissionProgress } from '@/api/submission'
import { getCapabilities, getTemplate } from '@/api/question'
import SubmissionAiAnalysis from '@/components/SubmissionAiAnalysis.vue'
import SubmissionResourceComparison from '@/components/SubmissionResourceComparison.vue'
import { readableError } from '@/api/errors'
import { formatMemory, formatShanghai } from '@/utils/format'
import { useSession } from '@/composables/useSession'
import { backupDraft, draftKey, readDraft, writeDraft } from '@/features/workspace/drafts'
import SubmittedSource from '@/components/SubmittedSource.vue'
import SubmissionDiagnostics from '@/components/SubmissionDiagnostics.vue'

const route = useRoute()
const router = useRouter()
const session = useSession()
const id = computed(() => String(route.params.submissionId))
const streamConnected = ref(false)
const query = useQuery({
  queryKey: computed(() => ['submission', session.user.value?.id, id.value]),
  queryFn: ({ signal }) => getSubmission(id.value, signal),
  enabled: computed(() => !!session.user.value),
  retry: false,
  refetchInterval: q => {
    if (q.state.error) return false
    const d = q.state.data
    if (d && (d.status === 2 || d.status === 3)) return false
    return streamConnected.value ? 5000 : 1500
  }
})
let stopProgress: (() => void) | undefined
watch([id, () => session.user.value?.id], ([submissionId, userId]) => {
  stopProgress?.()
  stopProgress = undefined
  if (!userId) return
  stopProgress = watchSubmissionProgress(submissionId, progress => {
    void query.refetch()
    if (progress.terminal) {
      stopProgress?.()
      stopProgress = undefined
    }
  }, connected => { streamConnected.value = connected })
}, { immediate: true })
onBeforeUnmount(() => stopProgress?.())
const copying = ref(false)
const highlightedLine = ref(0)
const sourceViewer = ref<InstanceType<typeof SubmittedSource>>()
async function revealSubmittedLine(line: number) {
  const count = query.data.value?.code?.split('\n').length ?? 0
  if (!Number.isInteger(line) || line < 1 || line > count) { ElMessage.info({ message: '行号超出提交源码范围' }); return }
  highlightedLine.value = line
  await nextTick()
  await sourceViewer.value?.revealLine()
}
watch(id, () => { highlightedLine.value = 0 })
const sourceCopying = ref(false)
async function copySource() {
  if (!query.data.value?.code) return
  sourceCopying.value = true
  try { await navigator.clipboard.writeText(query.data.value.code); ElMessage.success({ message: '源码已复制' }) }
  catch { ElMessage.error({ message: '复制失败，请选中源码手动复制' }) }
  finally { sourceCopying.value = false }
}
watch([()=>route.query.line,()=>query.data.value?.code],([line,code])=>{if(code&&line)void revealSubmittedLine(Number(line));else highlightedLine.value=0},{immediate:true})
async function copyToEditor() {
  const sub = query.data.value
  if (!sub?.code) return
  copying.value = true
  try {
    const caps = await getCapabilities(sub.questionId)
    const mode = sub.executionMode ?? caps.mode
    const available = caps.languages.some(v => v.id === sub.language && v.enabled && (v.modes?.includes(mode) ?? mode === caps.mode))
    if (!available) throw new Error('该题目当前不再支持此语言或模式')
    const template = await getTemplate(sub.questionId, sub.language, caps.problemVersion, mode)
    const key = draftKey(session.user.value?.id ?? 'guest', template.questionId, template.problemVersion, sub.language, mode, template.templateVersion)
    const old = await readDraft(key)
    if (old?.code && old.code !== sub.code) {
      await ElMessageBox.confirm('目标编辑器已有草稿。继续会覆盖当前草稿，并保留一份恢复副本。', '覆盖草稿', { type: 'warning' })
      await backupDraft(key, old.code)
    }
    await writeDraft({ key, code: sub.code, templateVersion: template.templateVersion })
    localStorage.setItem(`nemooj.mode.${template.questionId}`, mode)
    ElMessage.success({ message: '已复制到编辑器' })
    await router.push(`/problems/${sub.problemNo ?? sub.questionId}`)
  } catch (error) {
    if (error !== 'cancel') ElMessage.error({ message: readableError(error) })
  } finally { copying.value = false }
}

</script>

<template>
  <div class="page">
    <div class="page-head"><div><p class="crumb"><RouterLink to="/submissions">我的提交</RouterLink> / #{{ id }}</p><h1 class="page-title">提交详情</h1></div></div>
    <AsyncState class="surface" :loading="query.isPending.value" :error="query.isError.value ? readableError(query.error.value) : undefined" @retry="query.refetch()">
      <div v-if="query.data.value" class="submission-content surface">
        <section class="summary">
          <div><span>题目</span><RouterLink :to="`/problems/${query.data.value.problemNo ?? query.data.value.questionId}`"><strong>#{{ query.data.value.problemNo ?? '—' }} {{ query.data.value.questionTitle || '' }}</strong></RouterLink></div>
          <div><span>判题结果</span><JudgeStatusBadge :status="query.data.value.status" :verdict="query.data.value.verdict || query.data.value.judgeInfo?.message" /></div>
          <div><span>语言</span><strong>{{ query.data.value.runtime?.displayName || query.data.value.language }}</strong></div>
          <div><span>提交模式</span><strong>{{ query.data.value.executionMode === 'FUNCTION' ? '核心代码' : 'ACM' }}</strong></div>
          <div><span>耗时</span><strong>{{ query.data.value.judgeInfo?.executedTime ?? '—' }} ms</strong></div>
          <div><span>内存</span><strong>{{ formatMemory(query.data.value.judgeInfo?.executedMemory) }}</strong></div>
          <div><span>题目版本</span><strong>{{ query.data.value.problemVersion ?? '未记录' }}</strong></div>
          <div><span>提交时间</span><strong>{{ formatShanghai(query.data.value.createTime) }}</strong></div>
        </section>
        <section class="details">
          <div><h2>运行环境</h2><p>{{ query.data.value.runtime?.runtimeId || '未记录' }}</p><p v-if="query.data.value.effectiveLimits">时间 {{ query.data.value.effectiveLimits.timeLimitMs }} ms · 内存 {{ formatMemory(query.data.value.effectiveLimits.memoryLimitKB) }} · 输出 {{ Math.round(query.data.value.effectiveLimits.outputLimitBytes / 1024) }} KiB</p></div>
        </section>
        <div v-if="query.data.value.diagnostics" class="diagnostics-section"><SubmissionDiagnostics :message="query.data.value.diagnostics.message" :phase="query.data.value.diagnostics.phase" :truncated="query.data.value.diagnostics.truncated" :mode="query.data.value.executionMode" :line-count="query.data.value.code?.split('\n').length ?? 0" @navigate-line="revealSubmittedLine" /></div>
        <section v-if="session.user.value?.userRole === 'user'" class="ai-panel">
          <SubmissionAiAnalysis :key="`${session.user.value.id}-${id}`" :user-id="session.user.value.id" :submission-id="id" :status="query.data.value.status" :verdict="query.data.value.verdict || query.data.value.judgeInfo?.message" @navigate-line="revealSubmittedLine" />
          <SubmissionResourceComparison :submission="query.data.value" />
        </section>
        <section class="source">
          <div class="source-head"><h2>提交源码<span v-if="highlightedLine"> · 第 {{ highlightedLine }} 行</span></h2><div v-if="query.data.value.code" class="source-actions"><el-button :loading="sourceCopying" @click="copySource">复制源码</el-button><el-button v-if="query.data.value.problemSource !== 'PRIVATE'" :loading="copying" @click="copyToEditor">复制到编辑器</el-button></div></div>
          <SubmittedSource v-if="query.data.value.code" :key="id" ref="sourceViewer" :code="query.data.value.code" :language="query.data.value.language" :highlighted-line="highlightedLine" />
          <p v-else class="muted">源码不可用或当前账号无权查看。</p>
        </section>
      </div>
    </AsyncState>
  </div>
</template>

<style scoped>
.crumb{color:var(--oj-text-muted);margin:0 0 8px}.summary{padding:24px;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:22px;border-bottom:1px solid var(--oj-border)}.summary>div{display:flex;flex-direction:column;gap:7px;min-width:0}.summary strong{overflow-wrap:anywhere}.summary span{font-size:12px;color:var(--oj-text-muted)}
.details{padding:18px 24px;border-bottom:1px solid var(--oj-border)}.details h2,.source h2{font-size:15px;margin:0 0 10px}.details p{color:var(--oj-text-muted);font-size:13px;margin:6px 0;line-height:1.7}.diagnostics-section{padding:22px 24px 0;min-width:0}.source{padding:24px;min-width:0}.source-head{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:14px}.source-head h2{margin:0}.source-head h2 span{color:var(--oj-primary);font-size:12px;font-weight:400}.source-actions{display:flex;gap:8px}.source-actions :deep(.el-button+.el-button){margin-left:0}.ai-panel{padding:22px 24px;border-bottom:1px solid var(--oj-border)}
@media(max-width:767px){.summary{grid-template-columns:repeat(2,minmax(0,1fr));padding:18px;gap:18px}.details{padding:16px 18px}.diagnostics-section{padding:18px 14px 0}.source{padding:18px 14px}.source-actions :deep(.el-button){padding:8px 10px}.ai-panel{padding:18px 14px}}
</style>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElTooltip } from 'element-plus'
import { getSubmissionActivity } from '@/api/submission'
import { useSession } from '@/composables/useSession'
import { activityLevel, activityTooltip, calendarDays, shanghaiDate, shiftMonth } from '@/features/submissions/activity'

const session = useSession()
const today = ref(shanghaiDate()), month = ref(today.value.slice(0,7))
const enabled = computed(() => session.user.value?.userRole === 'user')
const query = useQuery({queryKey:computed(() => ['submission-activity',session.user.value?.id,month.value]),
  queryFn:({signal}) => getSubmissionActivity(month.value,signal),enabled,retry:false,staleTime:30_000,
  refetchOnMount:'always',refetchOnWindowFocus:'always'})
const currentMonth = computed(() => (query.data.value?.today ?? today.value).slice(0,7))
const counts = computed(() => new Map(query.data.value?.days.map(day => [day.date,day]) ?? []))
const cells = computed(() => calendarDays(month.value))
const title = computed(() => `${Number(month.value.slice(0,4))}年${Number(month.value.slice(5))}月`)
const total = computed(() => query.data.value?.days.reduce((sum,day) => sum+day.submissions,0) ?? 0)
function tooltip(date: string) {
  if (!enabled.value) return `${date}：登录后查看个人提交记录`
  if (query.isError.value) return `${date}：提交记录暂不可用`
  if (!query.data.value) return `${date}：正在加载提交记录`
  if (date > query.data.value.today) return `${date}：未来日期`
  return activityTooltip(counts.value.get(date) ?? {date,submissions:0,problems:0})
}
function updateToday() {
  const previous = today.value.slice(0,7); today.value = shanghaiDate()
  if (month.value === previous) month.value = today.value.slice(0,7)
}
watch(() => session.user.value?.id, () => { today.value=shanghaiDate(); month.value=today.value.slice(0,7) })
onMounted(() => window.addEventListener('focus',updateToday))
onBeforeUnmount(() => window.removeEventListener('focus',updateToday))
</script>

<template>
  <section class="submission-calendar" aria-label="个人提交日历">
    <div class="calendar-info">
      <span class="calendar-label">提交日历</span>
      <div class="month-controls">
        <button type="button" aria-label="上一个月" :disabled="month <= '2000-01'" @click="month=shiftMonth(month,-1)">‹</button>
        <strong>{{title}}</strong>
        <button type="button" aria-label="下一个月" :disabled="month >= currentMonth" @click="month=shiftMonth(month,1)">›</button>
      </div>
      <p v-if="!enabled">登录后查看个人提交</p>
      <p v-else-if="query.isPending.value" role="status">正在加载…</p>
      <p v-else-if="query.isError.value" role="status">加载失败 <button type="button" class="retry" :disabled="query.isFetching.value" @click="query.refetch()">重试</button></p>
      <p v-else><b>{{total}}</b> 次提交 · 北京时间</p>
    </div>
    <div class="calendar-grid">
      <span v-for="day in ['一','二','三','四','五','六','日']" :key="day" class="weekday">{{day}}</span>
      <template v-for="(date,index) in cells" :key="date ?? `padding-${index}`">
        <el-tooltip v-if="date" :content="tooltip(date)" placement="top" :show-after="100">
          <button type="button" class="calendar-day" :class="[`level-${activityLevel(query.isError.value ? 0 : counts.get(date)?.submissions ?? 0)}`,{'future':date > (query.data.value?.today ?? today)}]"
            :aria-label="tooltip(date)" :data-date="date" />
        </el-tooltip>
        <span v-else class="calendar-padding" aria-hidden="true" />
      </template>
    </div>
  </section>
</template>

<style scoped>
.submission-calendar{display:flex;align-items:center;justify-content:flex-end;gap:20px;flex:none;min-height:128px;color:var(--oj-text)}
.calendar-info{min-width:165px}.calendar-label{font-size:12px;color:var(--oj-text-muted)}
.month-controls{display:flex;align-items:center;gap:8px;margin-top:7px}.month-controls strong{font-size:13px;font-weight:600;min-width:86px;text-align:center}
.month-controls button{width:22px;height:22px;display:grid;place-items:center;border:1px solid var(--oj-border);border-radius:5px;background:var(--oj-surface);color:var(--oj-text);font-size:18px;cursor:pointer}
.month-controls button:disabled{opacity:.35;cursor:default}.calendar-info p{margin:9px 0;font-size:11px;color:var(--oj-text-muted)}.calendar-info b{color:var(--oj-primary);font-size:13px}
.retry{border:0;background:none;color:var(--oj-primary);padding:0 3px;cursor:pointer;font-size:11px}
.calendar-grid{display:grid;grid-template-columns:repeat(7,14px);gap:5px;align-content:center}.weekday{text-align:center;font-size:9px;color:var(--oj-text-muted);line-height:14px}
.calendar-day,.calendar-padding{width:14px;height:14px;border-radius:3px}.calendar-day{padding:0;border:1px solid rgba(255,255,255,.08);cursor:pointer;transition:filter .15s;min-width:0}.calendar-day:hover{filter:brightness(1.2)}.calendar-day.future{opacity:.4}
.level-0{background:#393d45}.level-1{background:#a5caff}.level-2{background:#6aa8ff}.level-3{background:#397fe5}.level-4{background:#214d9b}
@media(max-width:767px){.submission-calendar{width:100%;justify-content:space-between;padding-top:4px;gap:12px}.calendar-info{min-width:0}}
</style>

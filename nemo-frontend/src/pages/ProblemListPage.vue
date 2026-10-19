<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import AsyncState from '@/components/AsyncState.vue'
import ProblemListIcon from '@/components/ProblemListIcon.vue'
import ProblemTag from '@/components/ProblemTag.vue'
import ProblemTagFilter from '@/components/ProblemTagFilter.vue'
import SubmissionCalendar from '@/components/SubmissionCalendar.vue'
import { normalizeTagFilter, tagFilterOptions } from '@/features/problems/tagFilter'
import { listProblems } from '@/api/question'
import { readableError } from '@/api/errors'
import { filingMode } from '@/app/siteMode'

const route = useRoute()
const router = useRouter()
let debounce: number | undefined

function qstr(v: unknown) { return typeof v === 'string' ? v : '' }
function qnum(v: unknown, fallback: number) { const n = Number(v); return Number.isFinite(n) && n > 0 ? n : fallback }
const filter = reactive({
  title: qstr(route.query.title),
  tag: normalizeTagFilter(route.query.tag),
  page: qnum(route.query.page, 1),
  pageSize: (qnum(route.query.pageSize, 20) === 10 ? 10 : 20) as 10 | 20,
  sort: qstr(route.query.sort) || 'number'
})
const queryInput = computed(() => {
  const number = /^\d+$/.test(filter.title.trim()) ? Number(filter.title.trim()) : undefined
  return {
    title: number === undefined ? (filter.title || undefined) : undefined,
    problemNo: number,
    tags: filter.tag.length > 0 ? filter.tag : undefined,
    current: filter.page,
    pageSize: filter.pageSize,
    sortField: filter.sort === 'title' ? 'title' : filter.sort === 'number' ? 'problemNo' : 'createTime',
    sortOrder: filter.sort === 'newest' ? 'descend' : 'ascend'
  }
})
const query = useQuery({
  queryKey: computed(() => ['public', 'problems', queryInput.value]),
  queryFn: ({ signal }) => listProblems(queryInput.value, signal),
  placeholderData: prev => prev
})
const availableTags = computed(() => tagFilterOptions(query.data.value?.records.flatMap(row => row.tagList), filter.tag))
function sync(reset = false) {
  if (reset) filter.page = 1
  router.replace({ query: { ...(filter.title && { title: filter.title }), ...(filter.tag.length > 0 && { tag: filter.tag }), page: String(filter.page), pageSize: String(filter.pageSize), sort: filter.sort } })
}
watch(() => filter.title, () => { window.clearTimeout(debounce); debounce = window.setTimeout(() => sync(true), 300) })
watch(() => [filter.tag, filter.sort, filter.pageSize], () => sync(true))
onBeforeUnmount(() => window.clearTimeout(debounce))
function rateValue(accepted: number, submitted: number) { return submitted > 0 ? Math.min(100, Math.max(0, Math.round(accepted / submitted * 100))) : null }
function rateTone(value: number | null) { return value === null ? 'empty' : value >= 50 ? 'good' : value > 0 ? 'partial' : 'zero' }
function openProblem(row: { id: string; problemNo?: number | null }) { router.push(`/problems/${row.problemNo ?? row.id}`) }
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div><h1 class="page-title">题库</h1><p class="page-lead">{{filingMode?'浏览算法题目、难度与标签':'选择一道题目，开始编写并提交代码'}}</p></div>
      <SubmissionCalendar />
    </div>
    <section class="filters surface">
      <ProblemTagFilter v-model="filter.tag" :options="availableTags" />
      <el-select v-model="filter.sort" aria-label="排序">
        <template #prefix><ProblemListIcon name="sort" /></template>
        <el-option label="题号正序" value="number"/><el-option label="最新发布" value="newest"/><el-option label="最早发布" value="oldest"/><el-option label="标题" value="title"/>
      </el-select>
      <el-input v-model="filter.title" class="search-box" clearable placeholder="搜索题号或标题" aria-label="搜索题号或标题">
        <template #prefix><svg class="search-icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6.5"/><path d="m16 16 5 5"/></svg></template>
      </el-input>
    </section>
    <section class="surface table-wrap">
      <AsyncState :loading="query.isPending.value" :error="query.isError.value ? readableError(query.error.value) : undefined" :empty="query.data.value?.records.length === 0" empty-text="没有找到符合条件的题目" @retry="query.refetch()">
        <el-table :data="query.data.value?.records" stripe @row-click="openProblem">
          <el-table-column prop="problemNo" width="100"><template #header><span class="column-heading"><ProblemListIcon name="number" />题号</span></template><template #default="{ row }"><span class="mono id">{{ row.problemNo ?? '—' }}</span></template></el-table-column>
          <el-table-column min-width="150"><template #header><span class="column-heading"><ProblemListIcon name="problem" />题目</span></template><template #default="{ row }"><div class="problem-title">{{ row.title }}</div><div class="row-tags"><ProblemTag v-for="t in row.tagList" :key="t" :tag="t"/></div></template></el-table-column>
          <el-table-column prop="submitNum" width="120" align="center"><template #header><span class="column-heading align-center"><ProblemListIcon name="submission" />提交</span></template></el-table-column>
          <el-table-column min-width="195" align="right">
            <template #header><span class="column-heading align-right"><ProblemListIcon name="rate" />通过率</span></template>
            <template #default="{ row }">
              <div class="rate-cell" :class="rateTone(rateValue(row.acceptedNum, row.submitNum))">
                <span v-if="row.submitNum > 0" class="rate-track" role="progressbar" :aria-label="`${row.title}通过率`" :aria-valuenow="rateValue(row.acceptedNum, row.submitNum) ?? undefined" aria-valuemin="0" aria-valuemax="100"><span class="rate-fill" :style="{ width: `${rateValue(row.acceptedNum, row.submitNum)}%` }" /></span>
                <span v-else class="rate-track" aria-hidden="true" />
                <span class="rate-value">{{ rateValue(row.acceptedNum, row.submitNum) === null ? '—' : `${rateValue(row.acceptedNum, row.submitNum)}%` }}</span>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </AsyncState>
    </section>
    <div v-if="query.data.value" class="pager"><el-pagination v-model:current-page="filter.page" v-model:page-size="filter.pageSize" :page-sizes="[10, 20]" layout="total, sizes, prev, pager, next" :total="query.data.value.total" @current-change="sync()"/></div>
  </div>
</template>

<style scoped>
.search-box { width:min(360px,42vw); }
.page-head{align-items:center}
.search-icon { width:16px; height:16px; fill:none; stroke:var(--oj-primary); stroke-width:2; stroke-linecap:round; }
.filters { padding:14px; display:flex; justify-content:flex-end; align-items:center; gap:12px; margin-bottom:16px; }
.filters .el-select { width:160px; }
.filters :deep(.list-icon) { color:var(--oj-text-muted); }
.table-wrap { overflow:hidden; }
.table-wrap :deep(.el-table th:first-child .cell),
.table-wrap :deep(.el-table td:first-child .cell) { padding-left:24px; }
.table-wrap :deep(.el-table th:last-child .cell),
.table-wrap :deep(.el-table td:last-child .cell) { padding-right:24px; }
.column-heading { display:inline-flex; align-items:center; gap:6px; color:var(--oj-text-muted); }
.column-heading :deep(.list-icon) { color:var(--oj-primary); opacity:.85; }
.column-heading.align-right { justify-content:flex-end; width:100%; }
.column-heading.align-center { justify-content:center; width:100%; }
.problem-title { font-size:15px; font-weight:600; margin-bottom:6px; }
.row-tags { display:flex; flex-wrap:wrap; gap:5px; }
.id { font-size:14px; color:var(--oj-primary); font-weight:700; }
.rate-cell { display:flex; align-items:center; justify-content:flex-end; gap:10px; --rate-color:var(--oj-success); }
.rate-cell.partial { --rate-color:var(--oj-warning); }
.rate-cell.zero { --rate-color:var(--oj-danger); }
.rate-track { width:88px; height:8px; flex:none; overflow:hidden; border-radius:999px; background:var(--oj-border); }
.rate-fill { display:block; height:100%; border-radius:inherit; background:var(--rate-color); transition:width .25s ease; }
.rate-value { min-width:36px; color:var(--rate-color); font-variant-numeric:tabular-nums; }
.rate-cell.empty .rate-value { color:var(--oj-text-muted); }
.pager { display:flex; justify-content:flex-end; margin-top:18px; }
:deep(.el-table__row) { cursor:pointer; }
@media(max-width:767px) { .page-head { align-items:stretch; } .search-box { width:100%; } .filters { justify-content:stretch; flex-wrap:wrap; } .filters .el-select { width:100%; } .table-wrap { overflow-x:auto; } .table-wrap :deep(.el-table) { min-width:760px; } .pager { overflow:auto; justify-content:flex-start; } }
</style>

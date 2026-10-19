<script setup lang="ts">
import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { listMySubmissions } from '@/api/submission'
import type { Submission } from '@/api/types'
import { comparable,resourceDelta } from '@/features/submissions/resourceComparison'
const props=defineProps<{submission:Submission}>()
const history=useQuery({queryKey:computed(()=>['resource-history',props.submission.userId,props.submission.problemNo,props.submission.id]),
  queryFn:({signal})=>listMySubmissions({current:1,pageSize:20,problemNo:props.submission.problemNo??undefined,sortField:'createTime',sortOrder:'descend'},signal),
  enabled:computed(()=>props.submission.status===2&&!!props.submission.problemNo),retry:false})
const previous=computed(()=>history.data.value?.records.find(s=>s.id!==props.submission.id&&new Date(s.createTime).getTime()<new Date(props.submission.createTime).getTime()&&comparable(props.submission,s)))
</script>
<template>
  <section v-if="previous" class="resource-comparison"><h3>与上次成功提交比较</h3><p><RouterLink :to="`/submissions/${previous.id}`">查看上次提交</RouterLink> · 相同题目版本、语言、模式、运行环境与资源限制</p><p>耗时变化 {{resourceDelta(submission.judgeInfo?.executedTime,previous.judgeInfo?.executedTime)}} ms · 内存变化 {{resourceDelta(submission.judgeInfo?.executedMemory==null?null:submission.judgeInfo.executedMemory/1024,previous.judgeInfo?.executedMemory==null?null:previous.judgeInfo.executedMemory/1024)}} MiB</p><small>只比较最近 20 次提交中的匹配记录。单次结果会受负载影响，不能据此证明算法更优；复杂度仍需结合代码分析。</small></section>
</template>
<style scoped>.resource-comparison{padding:14px;margin:14px 0;border:1px solid var(--oj-border);border-left:3px solid #86c9df;border-radius:8px;font-size:13px}.resource-comparison h3{margin:0;color:#86c9df;font-size:14px}.resource-comparison small{color:var(--oj-text-muted);line-height:1.6}</style>

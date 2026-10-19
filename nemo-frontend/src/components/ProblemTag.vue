<script setup lang="ts">
import { computed } from 'vue'
import { problemTagTone } from '@/features/problems/tagPalette'

const props = defineProps<{ tag: string }>()
const tone = computed(() => problemTagTone(props.tag))
</script>

<template>
  <el-tag class="problem-tag" :class="`tone-${tone}`" size="small" effect="plain">{{ tag }}</el-tag>
</template>

<style scoped>
.problem-tag{font-weight:550;transition:filter .16s ease,transform .16s ease}
.problem-tag:hover{filter:brightness(1.12);transform:translateY(-1px)}
.tone-success{--el-tag-bg-color:rgba(52,211,153,.12);--el-tag-border-color:rgba(52,211,153,.42);--el-tag-text-color:#86efac}
.tone-warning{--el-tag-bg-color:rgba(250,204,21,.12);--el-tag-border-color:rgba(250,204,21,.4);--el-tag-text-color:#fde68a}
.tone-danger{--el-tag-bg-color:rgba(248,113,113,.12);--el-tag-border-color:rgba(248,113,113,.42);--el-tag-text-color:#fca5a5}
.tone-cyan{--el-tag-bg-color:rgba(34,211,238,.11);--el-tag-border-color:rgba(34,211,238,.38);--el-tag-text-color:#67e8f9}
.tone-violet{--el-tag-bg-color:rgba(139,92,246,.13);--el-tag-border-color:rgba(167,139,250,.4);--el-tag-text-color:#c4b5fd}
.tone-rose{--el-tag-bg-color:rgba(244,63,94,.11);--el-tag-border-color:rgba(251,113,133,.38);--el-tag-text-color:#fda4af}
.tone-orange{--el-tag-bg-color:rgba(249,115,22,.12);--el-tag-border-color:rgba(251,146,60,.4);--el-tag-text-color:#fdba74}
.tone-teal{--el-tag-bg-color:rgba(20,184,166,.12);--el-tag-border-color:rgba(45,212,191,.4);--el-tag-text-color:#5eead4}
.tone-indigo{--el-tag-bg-color:rgba(99,102,241,.13);--el-tag-border-color:rgba(129,140,248,.42);--el-tag-text-color:#a5b4fc}
.tone-purple{--el-tag-bg-color:rgba(168,85,247,.12);--el-tag-border-color:rgba(192,132,252,.4);--el-tag-text-color:#d8b4fe}
.tone-sky{--el-tag-bg-color:rgba(14,165,233,.12);--el-tag-border-color:rgba(56,189,248,.4);--el-tag-text-color:#7dd3fc}
.tone-emerald{--el-tag-bg-color:rgba(16,185,129,.12);--el-tag-border-color:rgba(52,211,153,.4);--el-tag-text-color:#6ee7b7}
.tone-lime{--el-tag-bg-color:rgba(132,204,22,.11);--el-tag-border-color:rgba(163,230,53,.38);--el-tag-text-color:#bef264}
.tone-slate{--el-tag-bg-color:rgba(148,163,184,.11);--el-tag-border-color:rgba(148,163,184,.36);--el-tag-text-color:#cbd5e1}
</style>

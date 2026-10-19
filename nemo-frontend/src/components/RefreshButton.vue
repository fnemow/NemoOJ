<script setup lang="ts">
import { ElButton } from 'element-plus'

defineOptions({ inheritAttrs: false })
defineProps<{ loading?: boolean }>()
</script>

<template>
  <ElButton v-bind="$attrs" class="refresh-action" type="primary" :loading="Boolean(loading)" :aria-busy="Boolean(loading)">
    <svg v-if="!loading" class="refresh-icon" viewBox="0 0 20 20" aria-hidden="true" focusable="false"><path d="M15.7 7A6 6 0 1 0 16 12M16 3v4h-4" /></svg>
    <span><slot>刷新</slot></span>
  </ElButton>
</template>

<style scoped>
.refresh-action{font-weight:600;gap:6px;transition:background-color .18s,border-color .18s,box-shadow .18s}
.refresh-icon{width:15px;height:15px;flex:none;margin-right:6px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round;transition:transform .18s}
.refresh-action:not(.is-loading):not(.is-disabled):hover .refresh-icon{transform:rotate(30deg)}
.refresh-action:focus-visible{outline:2px solid #b8ceff;outline-offset:3px}
@media(prefers-reduced-motion:reduce){.refresh-action,.refresh-icon{transition:none}}
</style>

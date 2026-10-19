<script setup lang="ts">
defineProps<{ expanded: boolean }>()
defineEmits<{ toggle: [] }>()
</script>

<template>
  <el-button class="editor-expand" :aria-pressed="expanded" :aria-label="expanded ? '退出编辑器全屏' : '编辑器全屏'" :title="expanded ? '退出全屏（Esc）' : '放大编辑器，保留导航栏'" @click="$emit('toggle')">
    <svg viewBox="0 0 24 24" aria-hidden="true"><path v-if="expanded" d="M9 3v6H3m12-6v6h6M3 15h6v6m12-6h-6v6"/><path v-else d="M9 3H3v6m12-6h6v6M3 15v6h6m12-6v6h-6"/></svg>
    <span>{{ expanded ? '退出全屏' : '全屏' }}</span>
  </el-button>
</template>

<style scoped>
.editor-expand svg{width:16px;height:16px;margin-right:6px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}
</style>

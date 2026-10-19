<script setup lang="ts">
defineOptions({ inheritAttrs: false })
defineProps<{ loading?: boolean; error?: string | undefined; empty?: boolean; emptyText?: string }>()
defineEmits<{ retry: [] }>()
</script>
<template>
  <div v-if="loading" class="loading" aria-busy="true"><el-skeleton :rows="6" animated /></div>
  <div v-else-if="error" class="error-state" role="alert"><p>{{ error }}</p><el-button @click="$emit('retry')">重试</el-button></div>
  <div v-else-if="empty" class="empty-state"><p>{{ emptyText || '暂无数据' }}</p><slot name="empty" /></div>
  <slot v-else />
</template>
<style scoped>.loading{padding:28px}</style>

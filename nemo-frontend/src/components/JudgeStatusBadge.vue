<script setup lang="ts">
import { computed } from 'vue'
import { normalizeStatus } from '@/features/submissions/status'
import LoadingSpinner from './LoadingSpinner.vue'

const props = defineProps<{ status: number; verdict?: string | null | undefined }>()
const value = computed(() => normalizeStatus(props.status, props.verdict))
</script>

<template>
  <span class="badge" :class="value.kind" :aria-busy="!value.terminal">
    <LoadingSpinner v-if="!value.terminal" />
    <span v-else class="dot" aria-hidden="true" />
    {{ value.text }}
  </span>
</template>

<style scoped>
.badge { display: inline-flex; align-items: center; gap: 7px; font-weight: 600; white-space: nowrap; }
.dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.wait { color: var(--oj-warning); }
.run { color: var(--oj-primary); }
.ok { color: var(--oj-success); }
.fail { color: var(--oj-danger); }
</style>

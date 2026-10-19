<script setup lang="ts">
defineProps<{ name: 'number' | 'problem' | 'submission' | 'rate' | 'tag' | 'sort' }>()
</script>

<template>
  <svg class="list-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <template v-if="name === 'number'"><path d="M9 3 6 21M18 3l-3 18M4 9h17M3 15h17" /></template>
    <template v-else-if="name === 'problem'"><path d="M8 4h11a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z" /><path d="M3 8h3M3 12h3M3 16h3M10 9h7M10 13h7M10 17h4" /></template>
    <template v-else-if="name === 'submission'"><path d="M12 16V3m0 0L7 8m5-5 5 5M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" /></template>
    <template v-else-if="name === 'rate'"><path d="M4 18V11M10 18V6M16 18v-8M22 18V3M2 21h21" /></template>
    <template v-else-if="name === 'tag'"><path d="M3 4h9l9 9-8 8-10-10V4Z" /><circle cx="8" cy="9" r="1" /></template>
    <template v-else><path d="M7 4v16m0-16L4 7m3-3 3 3M17 20V4m0 16-3-3m3 3 3-3" /></template>
  </svg>
</template>

<style scoped>
.list-icon { width: 15px; height: 15px; flex: none; }
</style>

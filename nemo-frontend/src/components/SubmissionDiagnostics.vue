<script setup lang="ts">
import { computed } from 'vue'
import { diagnosticEntries, formatDiagnostic, isUserDiagnosticLocation } from '@/features/submissions/diagnostics'
const props = defineProps<{ message: string; phase: 'compile' | 'run' | 'system'; truncated: boolean; mode: string | null; lineCount: number }>()
const emit = defineEmits<{ navigateLine: [line: number] }>()
const entries = computed(() => props.phase === 'compile' ? diagnosticEntries(props.message) : [])
const title = computed(() => props.phase === 'compile' ? '编译错误详情' : props.phase === 'run' ? '运行错误详情' : '系统诊断')
const errors = computed(() => entries.value.filter(e => e.kind === 'error').length)
const notes = computed(() => entries.value.length - errors.value)
</script>

<template>
  <section class="diagnostic-panel" aria-label="判题诊断">
    <div class="diagnostic-head"><h2><span aria-hidden="true" class="error-icon">!</span>{{ title }}</h2><span v-if="entries.length" class="diagnostic-count">{{ errors }} 个错误<span v-if="notes"> · {{ notes }} 条提示</span></span></div>
    <p v-if="entries.some(e => isUserDiagnosticLocation(e, mode, lineCount))" class="diagnostic-hint">点击行号可定位到下方提交源码</p>
    <div v-if="entries.length" class="diagnostic-list">
      <article v-for="(entry, index) in entries" :key="index" class="diagnostic-entry" :class="entry.kind">
        <div class="entry-meta"><span class="severity">{{ entry.kind === 'error' ? '错误' : entry.kind === 'warning' ? '警告' : '提示' }}</span><button v-if="isUserDiagnosticLocation(entry, mode, lineCount)" type="button" class="line-link" @click="emit('navigateLine', entry.line!)">第 {{ entry.line }} 行<span v-if="entry.column">，第 {{ entry.column }} 列</span><span aria-hidden="true"> ↗</span></button><span v-else class="internal-location">{{ entry.file ? '编译器诊断' : '题目接口校验' }}</span></div>
        <p class="error-message">{{ entry.message }}</p>
        <pre v-if="entry.context" class="compiler-text">{{ entry.context }}</pre>
      </article>
    </div>
    <details v-if="entries.length" class="raw-output"><summary>查看完整编译输出</summary><pre class="compiler-text">{{ formatDiagnostic(message) }}</pre></details>
    <pre v-else class="compiler-text raw-fallback">{{ formatDiagnostic(message) }}</pre>
    <p v-if="truncated" class="truncation">诊断信息过长，部分输出已截断。</p>
  </section>
</template>

<style scoped>
.diagnostic-panel{min-width:0;border:1px solid var(--oj-border);border-radius:10px;overflow:hidden;background:#202126}.diagnostic-head{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:16px 18px;background:rgba(240,120,120,.055)}.diagnostic-head h2{display:flex;align-items:center;gap:10px;font-size:15px;margin:0}.error-icon{display:inline-grid;place-items:center;width:20px;height:20px;border:1px solid #f07878;border-radius:50%;color:#f3a0a0;font-size:13px}.diagnostic-count,.diagnostic-hint{font-size:12px;color:var(--oj-text-muted)}.diagnostic-hint{margin:12px 18px 0}
.diagnostic-list{padding:14px 18px;display:grid;gap:12px}.diagnostic-entry{min-width:0;padding:14px 16px;border-radius:7px;border-left:3px solid #f07878;background:rgba(240,120,120,.04)}.diagnostic-entry.note{border-color:#7aa2f7;background:rgba(122,162,247,.035)}.diagnostic-entry.warning{border-color:#e5b567}.entry-meta{display:flex;align-items:center;gap:12px;font-size:12px}.severity{color:#f5a3a3}.note .severity{color:#a5bfff}.warning .severity{color:#e5b567}.line-link{padding:0;border:0;background:transparent;color:#a5bfff;font:inherit;cursor:pointer;text-decoration:underline;text-underline-offset:3px}.line-link:focus-visible{outline:2px solid #7aa2f7;outline-offset:4px}.internal-location{color:var(--oj-text-muted)}
.error-message{font-size:14px;line-height:1.7;margin:9px 0;overflow-wrap:anywhere;color:#f0e2e2}.note .error-message{color:#d2dcf1}.compiler-text{margin:10px 0 0;overflow:auto;color:#d7dbe4;font:13px/1.7 Consolas,"SFMono-Regular","Liberation Mono","Microsoft YaHei",monospace;tab-size:4;white-space:pre;font-variant-ligatures:none;max-height:300px}.raw-output{border-top:1px solid var(--oj-border);padding:12px 18px}.raw-output summary{cursor:pointer;color:var(--oj-text-muted);font-size:12px}.raw-output .compiler-text{padding:12px;background:#191a1e;border-radius:6px}.raw-fallback{padding:0 18px 18px;margin:0;max-height:420px}.truncation{color:#e5b567;font-size:12px;padding:0 18px}
@media(max-width:767px){.diagnostic-head{align-items:flex-start;flex-direction:column}.diagnostic-list{padding:12px}.diagnostic-entry{padding:12px}.compiler-text{font-size:12px}}
</style>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { LanguageId } from '@/api/types'
import { presentSource } from '@/features/submissions/sourcePresentation'

const props = defineProps<{ code: string; language: LanguageId; highlightedLine: number }>()
const lines = computed(() => presentSource(props.code, props.language))
const wrapped = ref(false)
const host = ref<HTMLElement>()
async function revealLine() {
  await nextTick()
  host.value?.querySelector<HTMLElement>('.is-highlighted')?.scrollIntoView?.({ block: 'center', behavior: 'instant' })
}
watch(() => props.highlightedLine, revealLine, { immediate: true })
defineExpose({ revealLine })
</script>

<template>
  <div class="source-viewer">
    <div class="reader-toolbar"><span>{{ language === 'cpp' ? 'C++' : language === 'java' ? 'Java' : 'Python' }}<span class="line-count">{{ lines.length }} 行</span></span><button type="button" :aria-pressed="wrapped" @click="wrapped = !wrapped">{{ wrapped ? '取消换行' : '自动换行' }}</button></div>
    <pre ref="host" class="source-scroll" :class="{ wrapped }" tabindex="0" aria-label="提交源码，只读"><code><span v-for="line in lines" :key="line.number" class="code-line" :class="{ 'is-highlighted': line.number === highlightedLine }" :data-line="line.number"><span class="line-text"><span v-for="(token, index) in line.tokens" :key="index" :class="token.kind ? `syntax-${token.kind}` : undefined">{{ token.text }}</span>{{ line.number < lines.length ? '\n' : '' }}</span></span></code></pre>
  </div>
</template>

<style scoped>
.source-viewer{border:1px solid var(--oj-border);border-radius:10px;overflow:hidden;background:#1e1e1e}
.reader-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 16px;background:#24262b;border-bottom:1px solid var(--oj-border);color:#d8dce5;font-size:12px}.line-count{color:#a0a6b2;margin-left:16px}
.reader-toolbar button{background:transparent;color:#b8c8ec;border:1px solid #464c59;border-radius:6px;padding:4px 10px;font:inherit;cursor:pointer}.reader-toolbar button:focus-visible{outline:2px solid var(--oj-primary)}
.source-scroll{margin:0;padding:14px 0;overflow:auto;max-height:640px;color:#e0e3e9;font:14px/1.75 Consolas,"SFMono-Regular","Liberation Mono","Microsoft YaHei",monospace;tab-size:4;font-variant-ligatures:none;scroll-margin:30px}
.source-scroll code{display:block;width:max-content;min-width:100%;font:inherit}.code-line{display:flex;min-height:1.75em;position:relative}.code-line:before{content:attr(data-line);position:sticky;left:0;flex:0 0 44px;box-sizing:border-box;text-align:right;padding:0 12px 0 6px;background:#1e1e1e;color:#858c99;user-select:none;font-size:12px}.line-text{padding-right:24px;white-space:pre;min-width:0}
.code-line.is-highlighted{background:rgba(122,162,247,.13);box-shadow:inset 3px 0 #7aa2f7}.code-line.is-highlighted:before{background:#293346;color:#b5cdff}
.wrapped code{width:100%}.wrapped .line-text{white-space:pre-wrap;overflow-wrap:anywhere;flex:1}
.syntax-keyword{color:#c792ea}.syntax-type{color:#67cfca}.syntax-function{color:#e6cf8b}.syntax-number{color:#b5cea8}.syntax-string{color:#e6ad8c}.syntax-comment{color:#87a77b}
@media(max-width:767px){.source-scroll{font-size:13px;max-height:520px}.line-text{padding-right:12px}}
</style>


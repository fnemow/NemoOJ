<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { ElPopover } from 'element-plus'
import ProblemListIcon from './ProblemListIcon.vue'
import ProblemTag from './ProblemTag.vue'
import { difficultyTags, normalizeTagFilter } from '@/features/problems/tagFilter'

const props = defineProps<{ modelValue: string[]; options: string[]; hint?: string; max?: number }>()
const emit = defineEmits<{ 'update:modelValue': [tags: string[]] }>()
const expanded = ref(false)
const trigger = ref<HTMLButtonElement>()
const panel = ref<HTMLDivElement>()
const groups = computed(() => [
  { title: '难度', tags: props.options.filter(tag => difficultyTags.some(value => value === tag)) },
  { title: '算法与数据结构', tags: props.options.filter(tag => !difficultyTags.some(value => value === tag)) }
])
const summary = computed(() => props.modelValue.length === 0 ? '选择标签'
  : props.modelValue.length === 1 ? props.modelValue[0] : `已选 ${props.modelValue.length} 个标签`)

function toggle(tag: string) {
  if (!props.modelValue.includes(tag) && props.max && props.modelValue.length >= props.max) return
  emit('update:modelValue', props.modelValue.includes(tag)
    ? props.modelValue.filter(value => value !== tag) : normalizeTagFilter([...props.modelValue, tag]))
}
async function close() {
  expanded.value = false
  await nextTick()
  trigger.value?.focus()
}
function focusChoice() { panel.value?.querySelector<HTMLButtonElement>('.tag-choice')?.focus() }
</script>

<template>
  <div class="tag-filter">
    <ElPopover v-model:visible="expanded" trigger="click" placement="bottom-end" :width="520" :teleported="false"
      @after-enter="focusChoice">
      <template #reference>
        <button ref="trigger" class="tag-trigger" :class="{ active: modelValue.length > 0, expanded }" type="button"
          aria-label="筛选题目标签" :aria-expanded="expanded" @keydown.esc.stop.prevent="close">
          <ProblemListIcon name="tag" />
          <span class="tag-summary">{{ summary }}</span>
          <svg class="chevron" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="m4 6 4 4 4-4" /></svg>
        </button>
      </template>
      <div ref="panel" class="tag-panel" @keydown.esc.stop.prevent="close">
        <div class="panel-head"><span>标签筛选</span><span class="selection-count">已选 {{ modelValue.length }} 项</span></div>
        <p class="panel-hint">{{ hint ?? '可多选，题目需同时包含所选标签' }}</p>
        <div class="tag-groups">
          <section v-for="group in groups" :key="group.title" class="tag-group" role="group" :aria-label="group.title">
            <h3>{{ group.title }}</h3>
            <div class="tag-grid">
              <button v-for="tag in group.tags" :key="tag" class="tag-choice" :class="{ selected: modelValue.includes(tag) }"
                type="button" :disabled="Boolean(max && modelValue.length >= max && !modelValue.includes(tag))" :aria-label="tag" :aria-pressed="modelValue.includes(tag)" @click="toggle(tag)">
                <ProblemTag :tag="tag" />
                <svg v-if="modelValue.includes(tag)" class="selection-check" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="m2 6 2.5 2.5L10 3" /></svg>
              </button>
            </div>
          </section>
        </div>
        <div class="panel-footer">
          <button type="button" class="clear-button" :disabled="modelValue.length === 0" @click="emit('update:modelValue', [])">清空选择</button>
          <button type="button" class="done-button" @click="close">完成</button>
        </div>
      </div>
    </ElPopover>
  </div>
</template>

<style scoped>
.tag-filter { width:220px; min-width:0; }
.tag-filter :deep(.el-popover.el-popper) { padding:18px; max-width:calc(100vw - 32px); background:var(--oj-surface); border-color:var(--oj-border); border-radius:10px; }
.tag-trigger { display:flex; align-items:center; gap:8px; width:100%; height:34px; padding:0 11px; color:var(--oj-text-muted); background:var(--el-fill-color-blank); border:1px solid var(--oj-border); border-radius:8px; cursor:pointer; font:inherit; font-size:13px; transition:border-color .15s ease; }
.tag-trigger:hover,.tag-trigger.expanded,.tag-trigger.active { border-color:var(--oj-primary); }
.tag-trigger.active { color:var(--oj-text); }
.tag-summary { flex:1; text-align:left; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.chevron { width:14px; height:14px; flex:none; transition:transform .15s ease; }
.expanded .chevron { transform:rotate(180deg); }
.panel-head { display:flex; align-items:center; justify-content:space-between; font-size:14px; font-weight:600; color:var(--oj-text); }
.selection-count { color:var(--oj-primary); font-size:12px; font-weight:400; }
.panel-hint { margin:8px 0 18px; font-size:12px; color:var(--oj-text-muted); }
.tag-groups { max-height:min(360px,50vh); overflow-y:auto; padding:2px; margin:-2px; }
.tag-group + .tag-group { margin-top:18px; }
.tag-group h3 { margin:0 0 10px; color:var(--oj-text-muted); font-size:12px; font-weight:500; }
.tag-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:10px; }
.tag-choice { position:relative; padding:0; border:0; background:transparent; border-radius:4px; cursor:pointer; }
.tag-choice :deep(.problem-tag) { width:100%; min-height:36px; padding:0 10px; border-radius:4px; font-size:12px; }
.tag-choice :deep(.problem-tag:hover) { transform:none; }
.tag-choice.selected { outline:2px solid var(--oj-primary); outline-offset:1px; }
.selection-check { position:absolute; top:2px; right:2px; width:10px; height:10px; color:var(--oj-primary); }
.panel-footer { display:flex; justify-content:space-between; align-items:center; gap:12px; border-top:1px solid var(--oj-border); margin-top:18px; padding-top:14px; }
.panel-footer button { font:inherit; font-size:12px; border-radius:6px; cursor:pointer; }
.clear-button { padding:6px 0; border:0; background:transparent; color:var(--oj-text-muted); }
.clear-button:not(:disabled):hover { color:var(--oj-primary); }
.clear-button:disabled { opacity:.45; cursor:default; }
.done-button { padding:6px 18px; border:1px solid var(--oj-primary); background:rgba(122,162,247,.12); color:var(--oj-primary); }
button:focus-visible { outline:2px solid var(--oj-primary); outline-offset:3px; }
@media(max-width:767px) { .tag-filter { width:100%; } }
@media(max-width:480px) { .tag-grid { grid-template-columns:repeat(3,minmax(0,1fr)); gap:9px; } }
</style>

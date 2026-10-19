<script setup lang="ts">
import type { ExecutionMode } from '@/api/types'
const props=defineProps<{ modelValue: ExecutionMode; availableModes: ExecutionMode[] }>()
const emit=defineEmits<{ 'update:modelValue': [value: ExecutionMode] }>()
const modes: ExecutionMode[]=['ACM','FUNCTION']
function change(value: ExecutionMode){
  if(props.availableModes.includes(value))emit('update:modelValue',value)
}
</script>

<template>
  <el-select class="execution-mode" :model-value="modelValue" aria-label="判题模式" :disabled="availableModes.length<2" :title="availableModes.length<2 ? '该题目或语言仅支持当前模式' : '切换判题模式'" @change="change">
    <el-option v-for="mode in modes" :key="mode" :label="mode==='FUNCTION' ? '核心代码' : 'ACM'" :value="mode" :disabled="!availableModes.includes(mode)"/>
  </el-select>
</template>

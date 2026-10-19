<script setup lang="ts">
import type { ProblemSpec } from '@/api/types'
defineProps<{spec?:ProblemSpec|null|undefined}>()
</script>
<template>
  <section v-if="spec?.dataStructures?.length" class="custom-structures">
    <h3>平台节点定义</h3>
    <p>以下类由平台提供，核心代码中直接使用，无需重复定义。Java/C++ 可用无参构造后赋字段，Python 可按字段名构造。</p>
    <details v-for="node in spec.dataStructures" :key="node.name" open>
      <summary>{{node.name}}</summary>
      <ul><li v-for="field in node.fields" :key="field.name"><code>{{field.name}}</code><span>{{field.type==='ref'?`${node.name} 引用，可为空`:field.type==='ref[]'?`${node.name} 引用列表`:field.type}}</span></li></ul>
    </details>
    <p>测试输入用 <code>{"root":0,"nodes":[…]}</code> 表示节点关系，引用为节点编号，<code>-1</code> 为空引用，空结构为 <code>null</code>。最多 200 个节点；父指针、环和共享引用会保留。节点结果按字段声明顺序广度遍历编号，根编号为 0。</p>
  </section>
</template>
<style scoped>
.custom-structures{margin:18px 0;padding:14px;border:1px solid var(--oj-border);border-radius:10px;background:var(--oj-surface)}h3{margin:0 0 10px;font-size:15px}p{font-size:12px;line-height:1.8;color:var(--oj-text-muted)}details{margin:8px 0}summary{cursor:pointer;font-size:13px;font-weight:600}ul{padding:0;list-style:none}li{display:flex;gap:12px;padding:4px 0;font-size:12px}code{color:var(--oj-accent);overflow-wrap:anywhere}span{color:var(--oj-text-muted)}
</style>

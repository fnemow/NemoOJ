<script setup lang="ts">
import { computed } from 'vue'
import type { PrivateBatch, PrivateBatchItem } from '@/api/ai'
import { difficultyLabel, terminalTask, trainingStage } from '@/features/plans/training'
import ProblemTag from './ProblemTag.vue'

const props=defineProps<{batch:PrivateBatch;items:PrivateBatchItem[];canRetry:boolean;busy:boolean}>()
defineEmits<{retry:[item:PrivateBatchItem]}>()
const readyCount=computed(()=>props.batch.items.filter(i=>i.problemStatus==='READY').length)
const createdDate=computed(()=>{
  const date=new Date(props.batch.createTime)
  return Number.isNaN(date.getTime())?'':new Intl.DateTimeFormat('zh-CN',{month:'long',day:'numeric'}).format(date)
})
function cardState(item:PrivateBatchItem){
  return item.problemStatus==='READY'?'ready':terminalTask(item.status)?'failed':'pending'
}
function title(item:PrivateBatchItem){
  if(item.title.trim()&&item.title.trim()!=='AI 题目生成中')return item.title
  return `${difficultyLabel[item.difficulty]}训练题${cardState(item)==='failed'?'待重试':'生成中'}`
}
</script>

<template>
  <section class="surface private-batch">
    <header class="batch-header">
      <div class="batch-heading">
        <div class="batch-eyebrow">私人训练题组 <span v-if="createdDate">· <time :datetime="batch.createTime" :title="batch.createTime">{{createdDate}}</time></span></div>
        <h2 class="batch-goal" :title="batch.goal">{{batch.goal}}</h2>
        <details v-if="batch.goal.length>60" class="goal-details"><summary>查看完整训练需求</summary><p>{{batch.goal}}</p></details>
      </div>
      <div class="batch-summary">
        <span class="ready-count" :class="{'all-ready':readyCount===batch.items.length}"><strong>{{readyCount}}</strong><span>/ {{batch.items.length}} 道可练习</span></span>
        <span v-if="batch.status==='PARTIAL_FAILED'" class="batch-hint">未通过的题目可单独重试</span>
      </div>
    </header>

    <div class="batch-grid">
      <article v-for="item in items" :key="item.difficulty" class="private-card" :class="[`difficulty-${item.difficulty.toLowerCase()}`,`state-${cardState(item)}`]">
        <div class="card-topline">
          <ProblemTag :tag="difficultyLabel[item.difficulty]"/>
          <span v-if="item.completed" class="completed"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4 4L19 6"/></svg>已打卡</span>
        </div>
        <h3 :title="title(item)">{{title(item)}}</h3>
        <div class="card-body">
          <p class="stage" :title="trainingStage(item)">
            <svg v-if="cardState(item)==='ready'" class="status-icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/></svg>
            <span v-else-if="cardState(item)==='pending'" class="spinner" aria-label="处理中"/>
            <svg v-else class="status-icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v6m0 4h.01"/></svg>
            <span>{{trainingStage(item)}}</span>
          </p>
          <div v-if="item.validatedCombinations" class="validation">
            <span>兼容性验证</span><span class="validation-count">{{item.validatedCombinations}} / 6</span>
            <div class="validation-segments" aria-hidden="true"><span v-for="n in 6" :key="n" :class="{verified:n<=item.validatedCombinations}"/></div>
          </div>
          <details v-if="item.message&&cardState(item)!=='ready'" class="failure-details">
            <summary>{{cardState(item)==='failed'?'查看未通过原因':'查看处理详情'}}</summary>
            <p>{{item.message}}</p>
          </details>
        </div>
        <footer class="card-footer">
          <RouterLink v-if="item.problemStatus==='READY'" class="practice" :to="`/private-problems/${item.privateProblemId}`"><span>开始练习</span><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14m-6-6 6 6-6 6"/></svg></RouterLink>
          <el-button v-else-if="['FAILED','CANCELLED'].includes(item.status)" class="retry-button" :loading="busy" :disabled="!canRetry||busy" @click="$emit('retry',item)">重试这道题</el-button>
          <span v-else class="waiting-note">题目就绪后即可练习</span>
        </footer>
      </article>
    </div>
  </section>
</template>

<style scoped>
.private-batch{padding:22px;margin-bottom:20px;min-width:0;container-type:inline-size}
.batch-header{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;margin-bottom:20px}
.batch-heading{min-width:0;flex:1}
.batch-eyebrow{color:var(--oj-text-muted);font-size:11px;letter-spacing:.04em;margin-bottom:8px}
.batch-eyebrow span{margin-left:6px}
.batch-goal{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere;margin:0;font-size:18px;line-height:1.5}
.batch-summary{display:flex;flex-direction:column;align-items:flex-end;gap:8px;flex:none}
.ready-count{display:flex;align-items:baseline;gap:5px;border:1px solid var(--oj-border);background:rgba(255,255,255,.035);padding:7px 12px;border-radius:8px;font-size:12px;color:var(--oj-text-muted);white-space:nowrap}
.ready-count strong{font-size:19px;line-height:1;color:var(--oj-text)}
.ready-count.all-ready strong{color:var(--oj-success)}
.batch-hint{font-size:11px;color:var(--oj-text-muted)}
.batch-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}
.private-card{position:relative;display:flex;flex-direction:column;padding:18px 18px 0;border:1px solid var(--oj-border);border-radius:10px;min-width:0;background:linear-gradient(160deg,rgba(255,255,255,.025),rgba(0,0,0,.08));overflow:hidden}
.private-card::before{position:absolute;inset:0 0 auto;height:2px;background:linear-gradient(90deg,var(--difficulty-color),transparent 70%);content:"";opacity:.55}
.difficulty-easy{--difficulty-color:var(--oj-success)}.difficulty-medium{--difficulty-color:var(--oj-warning)}.difficulty-hard{--difficulty-color:var(--oj-danger)}
.card-topline{display:flex;align-items:center;justify-content:space-between;gap:8px;min-height:24px}
.completed{display:inline-flex;align-items:center;gap:4px;font-size:11px;color:var(--oj-success)}
svg{width:16px;height:16px;flex:none;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}
.private-card h3{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere;min-height:48px;margin:14px 0 12px;font-size:15px;line-height:24px;font-weight:600}
.card-body{flex:1;min-height:76px}
.stage{display:flex;align-items:center;gap:7px;margin:0 0 14px;font-size:12px;line-height:1.6;color:var(--oj-text-muted)}
.state-ready .status-icon{color:var(--oj-success)}.state-failed .status-icon{color:var(--oj-warning)}
.validation{display:grid;grid-template-columns:1fr auto;gap:8px;font-size:11px;color:var(--oj-text-muted)}
.validation-count{font-variant-numeric:tabular-nums;color:var(--oj-text)}
.validation-segments{display:grid;grid-template-columns:repeat(6,1fr);gap:4px;grid-column:1/-1}
.validation-segments span{height:3px;border-radius:3px;background:var(--oj-border)}.validation-segments .verified{background:var(--oj-primary);opacity:.7}
.failure-details,.goal-details{margin:10px 0 0;font-size:12px;color:var(--oj-text-muted)}
summary{cursor:pointer;width:fit-content;line-height:1.7;transition:color .15s}
summary:hover{color:var(--oj-text)}
.failure-details p{margin:10px 0 0;padding:10px 12px;max-height:160px;overflow:auto;border-left:2px solid var(--oj-warning);border-radius:0 6px 6px 0;background:rgba(0,0,0,.12);font-size:12px;line-height:1.8;overflow-wrap:anywhere;white-space:pre-wrap}
.goal-details p{margin:8px 0 0;line-height:1.8;overflow-wrap:anywhere;white-space:pre-wrap}
.card-footer{display:flex;align-items:center;min-height:62px;padding:14px 0;margin-top:18px;border-top:1px solid var(--oj-border)}
.practice{display:inline-flex;justify-content:center;align-items:center;gap:12px;width:100%;min-height:34px;padding:7px 12px;border:1px solid rgba(122,162,247,.25);border-radius:7px;background:rgba(122,162,247,.08);color:var(--oj-primary);font-size:12px;transition:background .15s,border-color .15s}
.practice:hover{background:rgba(122,162,247,.15);border-color:rgba(122,162,247,.5)}
.retry-button{width:100%;min-height:34px;font-size:12px}
.waiting-note{font-size:12px;color:var(--oj-text-muted)}
.spinner{display:inline-block;width:14px;height:14px;flex:none;border:2px solid var(--oj-border);border-top-color:var(--oj-primary);border-radius:50%;animation:spin .9s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
@media(prefers-reduced-motion:reduce){.spinner{animation:none}}
@media(max-width:1000px){.batch-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@container(max-width:760px){.batch-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@container(max-width:500px){.batch-grid{grid-template-columns:1fr}}
@media(max-width:640px){.private-batch{padding:16px}.batch-grid{grid-template-columns:1fr;gap:12px}.batch-header{flex-direction:column;gap:12px;margin-bottom:16px}.batch-summary{flex-direction:row;align-items:center;flex-wrap:wrap;gap:10px}.batch-goal{font-size:17px}.private-card h3{min-height:0}.card-body{min-height:0}}
</style>

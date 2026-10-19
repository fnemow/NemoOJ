<script setup lang="ts">
import RefreshButton from '@/components/RefreshButton.vue'
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { ElCheckbox, ElCollapse, ElCollapseItem } from 'element-plus'
import { ElMessage } from '@/app/messages'
import * as api from '@/api/ai'
import type { PrivateBatch,PrivateBatchItem } from '@/api/ai'
import { readableError } from '@/api/errors'
import { useSession } from '@/composables/useSession'
import { readPlanTask,writePlanTask } from '@/features/plans/taskStorage'
import { difficultyLabel,terminalTask } from '@/features/plans/training'
import ProblemTagFilter from '@/components/ProblemTagFilter.vue'
import ProblemTag from '@/components/ProblemTag.vue'
import PrivateProblemBatch from '@/components/PrivateProblemBatch.vue'
const route=useRoute(),router=useRouter(),session=useSession()
const tab=computed({get:()=>route.query.tab==='private'?'private':'plans',set:(v:string)=>{void router.push({query:{...route.query,tab:v}})}})
const goal=ref(''),change=ref(''),taskId=ref(''),planBusy=ref(false),privateBusy=ref(false),page=ref(1)
const selectedBatchId=ref(''),legacyPage=ref(1)
const batchPageSize=5,legacyPageSize=5
const form=reactive({level:'基础',tags:[] as string[],days:14,minutesPerDay:45,allowReview:false})
const difficulty=ref(''),stateFilter=ref(''),batchKey=ref(''),retryKeys=new Map<string,string>()
const planKey=ref('')
watch(()=>JSON.stringify(form),()=>{planKey.value=''})
watch([difficulty,stateFilter],()=>{page.value=1;selectedBatchId.value=''})
watch(page,()=>{selectedBatchId.value=''})
watch(()=>session.user.value?.id,id=>{taskId.value=readPlanTask(id);goal.value='';change.value='';form.tags=[];page.value=1;selectedBatchId.value='';legacyPage.value=1;batchKey.value='';retryKeys.clear()},{immediate:true})
watch(goal,()=>{batchKey.value=''})
const loggedIn=computed(()=>Boolean(session.user.value?.id))
const entitlements=useQuery({queryKey:computed(()=>['ai-entitlements',session.user.value?.id]),enabled:loggedIn,queryFn:({signal})=>api.getAiEntitlements(signal),retry:false})
const plans=useQuery({queryKey:computed(()=>['ai-plans',session.user.value?.id]),enabled:loggedIn,queryFn:({signal})=>api.listStudyPlans(signal),retry:false,refetchInterval:q=>q.state.error?false:q.state.data?.some(v=>['GENERATING','ADJUSTING'].includes(v.status)||v.items.some(i=>i.source==='PRIVATE'&&i.title==='AI 题目生成中'))?2500:false})
const topics=useQuery({queryKey:computed(()=>['plan-topics',session.user.value?.id,form.allowReview]),enabled:loggedIn,queryFn:({signal})=>api.getPlanTopics(form.allowReview,signal),retry:false})
const batches=useQuery({queryKey:computed(()=>['private-batches',session.user.value?.id,page.value,batchPageSize,difficulty.value,stateFilter.value]),enabled:loggedIn,queryFn:({signal})=>api.listPrivateBatches(page.value,batchPageSize,signal,difficulty.value,stateFilter.value),retry:false,refetchInterval:q=>q.state.error?false:q.state.data?.records.some(b=>b.items.some(i=>!terminalTask(i.status)))?2500:false})
const legacy=useQuery({queryKey:computed(()=>['legacy-private-problems',session.user.value?.id]),enabled:loggedIn,queryFn:({signal})=>api.listPrivateProblems(signal,true),retry:false})
const selectedBatch=computed(()=>batches.data.value?.records.find(b=>b.id===selectedBatchId.value)??batches.data.value?.records[0])
const batchSelection=computed({get:()=>selectedBatch.value?.id??'',set:(id:string)=>{selectedBatchId.value=id}})
const visibleLegacy=computed(()=>legacy.data.value?.slice((legacyPage.value-1)*legacyPageSize,legacyPage.value*legacyPageSize)??[])
const task=useQuery({queryKey:computed(()=>['ai-task',session.user.value?.id,taskId.value]),queryFn:({signal})=>api.getAiTask(taskId.value,signal),enabled:computed(()=>Boolean(session.user.value?.id&&taskId.value)),retry:false,refetchInterval:q=>q.state.error||terminalTask(q.state.data?.status??'')?false:2000})
const active=computed(()=>plans.data.value?.find(v=>['ACTIVE','GENERATING','ADJUSTING','NEEDS_REVIEW'].includes(v.status)))
const history=computed(()=>plans.data.value?.filter(v=>v.id!==active.value?.id)??[])
const isVip=computed(()=>Boolean(entitlements.data.value?.vipActive))
const options=computed(()=>topics.data.value?.map(t=>t.tag)??[])
const selected=computed(()=>topics.data.value?.filter(t=>form.tags.includes(t.tag))??[])
const days=computed(()=>Array.from({length:active.value?.days??0},(_,i)=>i+1))
const expandedDays=ref<string[]>(['1'])
watch(()=>active.value?.id,()=>{expandedDays.value=[String(active.value?.items.find(i=>i.status==='TODO')?.dayNumber??1)]})
const visible=(b:PrivateBatch)=>b.items.filter(i=>(!difficulty.value||i.difficulty===difficulty.value)&&(!stateFilter.value||i.problemStatus===stateFilter.value))
function setTask(id:string,owner:string|undefined){if(!owner||session.user.value?.id!==owner)return;taskId.value=id;writePlanTask(owner,id)}
async function create(){if(!form.tags.length){ElMessage.warning({message:'请选择算法主题'});return}const owner=session.user.value?.id;planBusy.value=true;planKey.value ||=crypto.randomUUID();try{const r=await api.createStudyPlan({...form,inputVersion:'CATALOG_V2',sourceMode:'CATALOG',idempotencyKey:planKey.value});setTask(r.taskId,owner);if(owner!==session.user.value?.id)return;planKey.value='';await plans.refetch()}catch(e){ElMessage.error({message:readableError(e)})}finally{planBusy.value=false}}
async function adjust(){if(!active.value||!change.value.trim())return;const owner=session.user.value?.id;planBusy.value=true;try{const r=await api.adjustStudyPlan(active.value.id,{changeRequest:change.value.trim(),expectedVersion:active.value.planVersion,idempotencyKey:crypto.randomUUID()});setTask(r.taskId,owner);change.value='';await plans.refetch()}catch(e){ElMessage.error({message:readableError(e)})}finally{planBusy.value=false}}
async function archive(){if(!active.value)return;planBusy.value=true;try{await api.archiveStudyPlan(active.value.id,active.value.planVersion);await plans.refetch()}catch(e){ElMessage.error({message:readableError(e)})}finally{planBusy.value=false}}
async function generate(){if(!goal.value.trim()){ElMessage.warning({message:'请描述私人训练目标'});return}const owner=session.user.value?.id;privateBusy.value=true;batchKey.value ||= crypto.randomUUID();try{await api.createPrivateBatch(goal.value.trim(),batchKey.value);if(owner!==session.user.value?.id)return;page.value=1;selectedBatchId.value='';batchKey.value='';ElMessage.success({message:'三道训练题已加入队列，通过验证后分别交付'});await nextTick();await batches.refetch({cancelRefetch:false})}catch(e){ElMessage.error({message:readableError(e)})}finally{privateBusy.value=false}}
async function retry(b:PrivateBatch,i:PrivateBatchItem){const slot=`${b.id}:${i.difficulty}`,key=retryKeys.get(slot)??crypto.randomUUID();retryKeys.set(slot,key);privateBusy.value=true;try{await api.retryPrivateBatchItem(b.id,i.difficulty,b.rowVersion,key);retryKeys.delete(slot);await batches.refetch()}catch(e){ElMessage.error({message:readableError(e)});await batches.refetch()}finally{privateBusy.value=false}}
</script>
<template>
  <div class="page training-page">
    <div class="page-head"><div><h1 class="page-title">AI 训练</h1><p class="page-lead">从题库安排刷题计划，或按自己的目标生成私人训练题。</p></div><el-tag :type="isVip?'warning':'info'">{{isVip?'VIP':'普通用户'}}</el-tag></div>
    <el-tabs v-model="tab">
      <el-tab-pane label="刷题计划" name="plans">
        <el-alert v-if="task.data.value" :type="task.data.value.status==='FAILED'?'error':task.data.value.status==='SUCCEEDED'?'success':'info'" :closable="false" class="task-alert" :title="task.data.value.message??(task.data.value.status==='SUCCEEDED'?'计划生成完成':task.data.value.status==='FAILED'?'计划生成失败':'计划正在处理中')"/>
        <section class="surface card"><h2>安排题库练习</h2><p class="muted">选择现有算法主题，每天至少一题；多选时分别覆盖各主题。</p>
          <el-form label-position="top"><el-form-item label="算法主题（最多 6 个）"><ProblemTagFilter v-model="form.tags" :options="options" :max="6" hint="题目至少匹配一个主题，整份计划覆盖每个主题"/></el-form-item>
            <p v-if="topics.isError.value" class="muted">{{readableError(topics.error.value)}} <el-button link @click="topics.refetch()">重新加载</el-button></p>
            <div class="topic-counts"><span v-for="t in selected" :key="t.tag"><ProblemTag :tag="t.tag"/> 可用 {{t.available}} 道 · 已完成 {{t.accepted}} 道</span></div>
            <div class="form-grid"><el-form-item label="当前水平"><el-select v-model="form.level"><el-option v-for="level in ['入门','基础','进阶']" :key="level" :label="level" :value="level"/></el-select></el-form-item><el-form-item label="周期（天）"><el-input-number v-model="form.days" :min="7" :max="30"/></el-form-item><el-form-item label="每天用时（分钟）"><el-input-number v-model="form.minutesPerDay" :min="10" :max="240"/></el-form-item></div>
            <el-checkbox v-model="form.allowReview">允许复习已完成题目</el-checkbox><div class="actions"><el-button type="primary" :loading="planBusy" :disabled="!entitlements.data.value?.planEnabled||Boolean(active)" @click="create">创建刷题计划</el-button><span class="muted">需要至少 {{form.days}} 道不同题目，可用数量在创建时重新校验。</span></div><p v-if="!entitlements.data.value?.planEnabled" class="muted">新建和调整需要有效 VIP，且 AI 服务已启用。</p>
          </el-form>
        </section>
        <section v-if="active" class="surface card"><div class="section-title"><div><h2>{{active.title}}</h2><p>{{active.goal}} · {{active.days}} 天 · 每日 {{active.minutesPerDay}} 分钟</p><small v-if="active.sourceMode==='MIXED'" class="muted">历史混合计划，练习与打卡记录仍然保留。</small></div><div class="actions"><el-tag>{{active.status}}</el-tag><RefreshButton size="small" @click="plans.refetch()" :loading="plans.isFetching.value">刷新</RefreshButton><el-button size="small" type="danger" plain :disabled="['GENERATING','ADJUSTING'].includes(active.status)||!isVip" @click="archive">归档</el-button></div></div>
          <el-progress :percentage="active.items.length?Math.round(active.completedCount/active.items.length*100):0" :format="()=>`${active?.completedCount??0}/${active?.items.length??0} 已完成`"/>
          <el-collapse v-model="expandedDays" class="items"><el-collapse-item v-for="day in days" :key="day" :name="String(day)" :title="`第 ${day} 天`"><article v-for="i in active.items.filter(i=>i.dayNumber===day)" :key="i.id" class="plan-item"><div class="item-main"><strong>{{i.title}}</strong><p>{{i.reason}}</p></div><el-tag :type="i.status==='DONE'?'success':i.status==='UNAVAILABLE'?'danger':'info'">{{i.status==='DONE'?'已打卡':i.status==='UNAVAILABLE'?'不可用':'待完成'}}</el-tag><RouterLink v-if="i.status!=='UNAVAILABLE'&&i.source==='PUBLIC'&&i.problemNo" :to="`/problems/${i.problemNo}`">去练习</RouterLink><RouterLink v-else-if="i.privateProblemId&&i.title!=='AI 题目生成中'" :to="`/private-problems/${i.privateProblemId}`">去练习</RouterLink></article></el-collapse-item></el-collapse>
          <div class="adjust"><el-input v-model="change" placeholder="调整未完成任务的顺序或节奏（保留当前主题）" maxlength="1000"/><el-button :disabled="!entitlements.data.value?.planEnabled||active.status!=='ACTIVE'" :loading="planBusy" @click="adjust">调整计划</el-button></div>
        </section>
        <section v-if="history.length" class="surface card"><h2>历史计划</h2><el-collapse><el-collapse-item v-for="p in history" :key="p.id" :name="p.id" :title="`${p.title} · ${p.completedCount}/${p.items.length} 已完成`"><article v-for="i in p.items" :key="i.id" class="plan-item"><span>第 {{i.dayNumber}} 天 · {{i.title}}</span><el-tag>{{i.status==='DONE'?'已打卡':i.status==='UNAVAILABLE'?'不可用':'待完成'}}</el-tag></article></el-collapse-item></el-collapse></section>
      </el-tab-pane>
      <el-tab-pane label="私人题目" name="private">
        <details class="surface card private-create" :open="!batches.isPending.value && batches.data.value?.total===0 && !(difficulty||stateFilter)">
          <summary class="private-create-summary"><div><h2>定制三道训练题</h2><p class="muted">简单、中等、困难各一题，仅你可见。</p></div><span class="create-hint">新建题组 <span aria-hidden="true">＋</span></span></summary>
          <div class="private-create-body"><el-input v-model="goal" type="textarea" :rows="4" maxlength="1000" show-word-limit placeholder="描述希望练习的知识与操作，例如：围绕红黑树性质验证、旋转与插入生成递进练习。"/><div class="actions"><el-button type="primary" :loading="privateBusy" :disabled="!entitlements.data.value?.privateProblemsEnabled" @click="generate">生成三道训练题</el-button><span class="muted">每题完整验证后单独开放，验证失败会在预算内自动修复。</span></div></div>
        </details>
        <div class="private-toolbar"><div class="actions"><el-select v-model="difficulty" placeholder="全部难度" clearable><el-option v-for="(label,key) in difficultyLabel" :key="key" :label="label" :value="key"/></el-select><el-select v-model="stateFilter" placeholder="全部状态" clearable><el-option label="可练习" value="READY"/><el-option label="生成中" value="GENERATING"/><el-option label="未通过" value="REJECTED"/></el-select></div><RefreshButton @click="batches.refetch();legacy.refetch()" :loading="batches.isFetching.value || legacy.isFetching.value">刷新</RefreshButton></div>
        <el-alert v-if="batches.isError.value" :title="readableError(batches.error.value)" type="error" :closable="false"/>
        <el-skeleton v-if="batches.isPending.value" :rows="4" animated/>
        <div v-else-if="batches.data.value?.records.length" class="private-browser">
          <aside class="surface batch-directory" aria-label="私人题组目录">
            <header class="directory-heading"><h2>我的题组</h2><span>{{batches.data.value.total}} 组</span></header>
            <nav class="batch-directory-list" aria-label="选择私人题组">
              <button v-for="b in batches.data.value.records" :key="b.id" type="button" class="batch-directory-item" :class="{selected:b.id===selectedBatch?.id}" :aria-pressed="b.id===selectedBatch?.id" :title="b.goal" @click="selectedBatchId=b.id">
                <strong>{{b.goal}}</strong><span class="directory-meta"><span>{{b.items.filter(i=>i.problemStatus==='READY').length}}/{{b.items.length}} 道可练习</span><time :datetime="b.createTime">{{b.createTime.slice(0,10)}}</time></span>
              </button>
            </nav>
            <el-select v-model="batchSelection" class="mobile-batch-select" aria-label="选择私人题组"><el-option v-for="b in batches.data.value.records" :key="b.id" :value="b.id" :label="b.goal"/></el-select>
            <el-pagination v-model:current-page="page" small :pager-count="5" :page-size="batchPageSize" :total="batches.data.value.total" layout="prev,pager,next"/>
          </aside>
          <div class="private-detail"><PrivateProblemBatch v-if="selectedBatch" :key="selectedBatch.id" :batch="selectedBatch" :items="visible(selectedBatch)" :can-retry="Boolean(entitlements.data.value?.privateProblemsEnabled)" :busy="privateBusy" @retry="item=>retry(selectedBatch!,item)"/></div>
        </div>
        <p v-else-if="!batches.isError.value" class="muted">{{difficulty||stateFilter?'没有符合筛选条件的题组。':'还没有生成批次，描述一个训练目标即可开始。'}}</p>
        <details v-if="legacy.data.value?.length" class="surface card legacy"><summary class="legacy-heading"><h2>历史单题</h2><span class="muted">{{legacy.data.value.length}} 道 · 展开查看</span></summary><article v-for="p in visibleLegacy" :key="p.id" class="plan-item"><div class="item-main"><strong>{{p.title}}</strong><p>{{p.tags.join(' · ')}}</p></div><ProblemTag :tag="difficultyLabel[p.difficulty]"/><RouterLink v-if="p.status==='READY'" :to="`/private-problems/${p.id}`">去练习</RouterLink><span v-else class="muted">{{p.status==='REJECTED'?'未通过验证':'生成中'}}</span></article><el-pagination v-model:current-page="legacyPage" small :pager-count="5" :page-size="legacyPageSize" :total="legacy.data.value.length" layout="prev,pager,next"/></details>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
<style scoped>
.training-page{max-width:1280px}.task-alert{margin-bottom:16px}.card{padding:22px;margin-bottom:18px;min-width:0}.card h2{margin:0;font-size:18px;overflow-wrap:anywhere}.section-title{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:18px}.section-title p,.plan-item p{margin:6px 0 0;color:var(--oj-text-muted);font-size:13px}.form-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin-top:14px}.form-grid :deep(.el-input-number),.form-grid :deep(.el-select){width:100%}.actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.card>.actions,.card .el-form .actions{margin-top:16px}.muted,.stage,.topic-counts{font-size:13px;color:var(--oj-text-muted)}.topic-counts{display:flex;gap:12px;flex-wrap:wrap}.items{margin-top:18px}.plan-item{display:flex;align-items:center;gap:16px;padding:14px 4px;border-bottom:1px solid var(--oj-border);flex-wrap:wrap}.item-main{min-width:0;flex:1;overflow-wrap:anywhere}.plan-item a,.practice{color:var(--oj-accent)}.adjust{display:flex;gap:10px;margin-top:16px}.adjust .el-input{flex:1}.private-toolbar{display:flex;align-items:center;justify-content:space-between;margin:18px 0;gap:12px}.private-toolbar .el-select{width:140px}.legacy{margin-top:20px}@media(max-width:800px){.form-grid{grid-template-columns:1fr 1fr}.adjust{flex-direction:column}.private-toolbar{flex-wrap:wrap}}@media(max-width:520px){.form-grid{grid-template-columns:1fr}.section-title{flex-wrap:wrap}.card{padding:16px}.private-toolbar .el-select{width:130px}}

.private-create-summary{display:flex;align-items:center;justify-content:space-between;gap:16px;list-style:none;cursor:pointer}.private-create-summary::-webkit-details-marker{display:none}.private-create-summary p{margin:6px 0 0;font-size:12px}.create-hint{display:flex;align-items:center;gap:10px;font-size:12px;white-space:nowrap;color:var(--oj-primary)}.create-hint span{font-size:20px;transition:transform .15s}.private-create[open] .create-hint span{transform:rotate(45deg)}.private-create-body{margin-top:18px}.private-create-body .actions{margin-top:16px}
.private-browser{display:grid;grid-template-columns:250px minmax(0,1fr);gap:18px;align-items:start}.batch-directory{padding:16px;min-width:0}.directory-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:14px}.directory-heading h2{margin:0;font-size:15px}.directory-heading>span{font-size:12px;color:var(--oj-text-muted)}.batch-directory-list{display:flex;flex-direction:column;gap:8px;max-height:430px;overflow-y:auto}.batch-directory-item{display:block;width:100%;text-align:left;border:1px solid transparent;border-radius:8px;padding:12px;background:rgba(255,255,255,.025);color:var(--oj-text);cursor:pointer;transition:background .15s,border-color .15s}.batch-directory-item:hover{background:rgba(255,255,255,.05)}.batch-directory-item.selected{border-color:rgba(122,162,247,.4);background:rgba(122,162,247,.08)}.batch-directory-item strong{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere;font-size:13px;line-height:1.6;font-weight:550}.directory-meta{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-top:8px;font-size:10px;color:var(--oj-text-muted)}.directory-meta time{white-space:nowrap}.batch-directory .el-pagination{justify-content:center;margin-top:16px}.private-detail{min-width:0}.private-detail :deep(.private-batch){margin-bottom:0}.mobile-batch-select{display:none}.legacy-heading{display:flex;align-items:center;gap:14px;cursor:pointer}.legacy-heading span{font-size:12px}.legacy .el-pagination{margin-top:16px}
@media(max-width:800px){.private-browser{grid-template-columns:1fr}.batch-directory-list{display:none}.mobile-batch-select{display:block;width:100%}.directory-heading{margin-bottom:12px}.batch-directory .el-pagination{margin-top:12px}.private-create-summary{align-items:flex-start}.create-hint{gap:4px}.private-create-summary h2{font-size:16px}.legacy-heading{flex-wrap:wrap}}
</style>

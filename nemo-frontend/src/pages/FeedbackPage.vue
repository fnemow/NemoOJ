<script setup lang="ts">
import RefreshButton from '@/components/RefreshButton.vue'
import { computed,reactive,ref,watch } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage } from '@/app/messages'
import { addFeedback,getFeedback,myFeedback } from '@/api/feedback'
import { readableError } from '@/api/errors'
import { formatShanghai } from '@/utils/format'
import type { Feedback,FeedbackStatus,FeedbackType } from '@/api/types'

const props=defineProps<{problemNo?:number|undefined}>()
const route=useRoute(),page=ref(1),busy=ref(false),dialog=ref(false),selected=ref<Feedback|null>(null)
const initialNo=props.problemNo??(typeof route.query.problemNo==='string'?Number(route.query.problemNo):undefined)
const form=reactive<{type:FeedbackType;problemNo:string;title:string;content:string}>({type:initialNo?'PROBLEM':'SUGGESTION',problemNo:initialNo?String(initialNo):'',title:'',content:''})
watch(()=>props.problemNo,no=>{if(no){form.problemNo=String(no);form.type='PROBLEM'}})
const query=useQuery({queryKey:['feedback','mine',page],queryFn:()=>myFeedback({current:page.value,pageSize:20})})
const needsProblem=computed(()=>form.type==='PROBLEM')
const typeLabel:Record<FeedbackType,string>={PROBLEM:'题目问题',BUG:'系统故障',SUGGESTION:'功能建议',OTHER:'其他'}
const statusLabel:Record<FeedbackStatus,string>={PENDING:'待处理',PROCESSING:'处理中',RESOLVED:'已解决'}
async function submit(){if(!form.title.trim()||!form.content.trim()){ElMessage.warning({ message: '请填写标题和正文' });return}if(needsProblem.value&&!form.problemNo){ElMessage.warning({ message: '题目问题必须填写题号' });return}busy.value=true;try{await addFeedback({type:form.type,problemNo:form.problemNo?Number(form.problemNo):undefined,title:form.title.trim(),content:form.content.trim()});ElMessage.success({ message: '反馈已提交' });form.title='';form.content='';await query.refetch()}catch(e){ElMessage.error({ message: readableError(e) })}finally{busy.value=false}}
async function open(row:Feedback){try{selected.value=await getFeedback(row.id);dialog.value=true}catch(e){ElMessage.error({ message: readableError(e) })}}
</script>
<template><div class="page feedback-page">
  <div class="page-head"><div><h1 class="page-title">意见反馈</h1><p class="page-lead">告诉我们题目或系统中需要改进的地方，并在这里查看处理结果。</p></div></div>
  <div class="feedback-grid"><section class="surface compose"><h2>提交反馈</h2><el-form label-position="top">
    <el-form-item label="反馈类型"><el-select v-model="form.type"><el-option v-for="(label,value) in typeLabel" :key="value" :label="label" :value="value"/></el-select></el-form-item>
    <el-form-item label="关联题号" :required="needsProblem"><el-input v-model="form.problemNo" placeholder="例如 1" clearable/></el-form-item>
    <el-form-item label="标题"><el-input v-model="form.title" maxlength="120" show-word-limit/></el-form-item>
    <el-form-item label="详细描述"><el-input v-model="form.content" type="textarea" :rows="8" maxlength="4000" show-word-limit/></el-form-item>
    <el-button type="primary" :loading="busy" @click="submit">提交反馈</el-button>
  </el-form></section>
  <section class="surface history"><div class="section-head"><h2>我的反馈</h2><RefreshButton @click="query.refetch()" :loading="query.isFetching.value">刷新</RefreshButton></div><el-table :data="query.data.value?.records" v-loading="query.isPending.value" stripe empty-text="暂无反馈" @row-click="open">
    <el-table-column label="状态" width="100"><template #default="{row}"><el-tag :type="row.status==='RESOLVED'?'success':row.status==='PROCESSING'?'warning':'info'">{{statusLabel[row.status as FeedbackStatus]}}</el-tag></template></el-table-column>
    <el-table-column label="类型" width="100"><template #default="{row}">{{typeLabel[row.type as FeedbackType]}}</template></el-table-column><el-table-column label="标题" prop="title" min-width="180"/><el-table-column label="时间" width="170"><template #default="{row}">{{formatShanghai(row.createTime)}}</template></el-table-column>
  </el-table><el-pagination v-if="query.data.value" v-model:current-page="page" :page-size="20" :total="query.data.value.total" layout="prev,pager,next" @current-change="query.refetch()"/></section></div>
  <el-dialog v-model="dialog" title="反馈详情" width="min(620px,92vw)"><template v-if="selected"><div class="ticket-meta"><el-tag>{{statusLabel[selected.status]}}</el-tag><span>{{typeLabel[selected.type]}}</span><span v-if="selected.problemNo">题号 {{selected.problemNo}}</span><span>{{formatShanghai(selected.createTime)}}</span></div><h3>{{selected.title}}</h3><p class="ticket-content">{{selected.content}}</p><div v-if="selected.adminReply" class="reply"><strong>{{selected.replySource==='AI'?'NemoOJ 帮助助手回复':'管理员回复'}}</strong><p>{{selected.adminReply}}</p></div><p v-else class="reply-pending">{{selected.agentStatus==='ESCALATED'?'已转交管理员处理。':'暂未回复，请耐心等待。'}}</p></template></el-dialog>
</div></template>
<style scoped>.feedback-page{max-width:1280px}.feedback-grid{display:grid;grid-template-columns:minmax(300px,420px) minmax(0,1fr);gap:18px}.compose,.history{padding:22px}.compose h2,.history h2{margin:0 0 20px}.section-head{display:flex;justify-content:space-between}.history :deep(.el-table__row){cursor:pointer}.history .el-pagination{justify-content:flex-end;margin-top:18px}.ticket-meta{display:flex;gap:12px;align-items:center;color:var(--oj-text-muted);font-size:13px;flex-wrap:wrap}.ticket-content,.reply p{white-space:pre-wrap;line-height:1.75}.reply{margin-top:24px;padding:18px;border:1px solid var(--oj-border);border-radius:10px;background:rgba(79,140,255,.06)}@media(max-width:900px){.feedback-grid{grid-template-columns:minmax(0,1fr)}}</style>

<script setup lang="ts">
import { computed,reactive,watch,ref } from 'vue';import { useQuery } from '@tanstack/vue-query';import { getAiEntitlements } from '@/api/ai';import { ElMessage } from '@/app/messages';import { updateProfile } from '@/api/user';import { readableError } from '@/api/errors';import { useSession,sessionKey } from '@/composables/useSession';import { queryClient } from '@/app/state'
const s=useSession(),busy=ref(false),error=ref('');const form=reactive({userName:'',userProfile:''});watch(s.user,u=>{if(u){form.userName=u.userName??'';form.userProfile=u.userProfile??''}},{immediate:true})
const memberEnabled=computed(()=>Boolean(s.user.value?.id&&s.user.value.userRole==='user'))
const membership=useQuery({queryKey:computed(()=>['ai-entitlements',s.user.value?.id]),enabled:memberEnabled,queryFn:({signal})=>getAiEntitlements(signal),retry:false,staleTime:0,refetchOnMount:'always',refetchOnWindowFocus:'always'})
const isVip=computed(()=>memberEnabled.value&&!membership.isError.value&&Boolean(membership.data.value?.vipActive))
const identityLabel=computed(()=>{
  if(s.user.value?.userRole==='admin')return '管理员'
  if(s.user.value?.userRole==='ban')return '已封禁'
  if(membership.isError.value)return '身份暂不可用'
  if(!membership.data.value)return '身份加载中…'
  return isVip.value?'VIP 用户':'普通用户'
})
async function save(){busy.value=true;error.value='';try{await updateProfile({...form});await queryClient.invalidateQueries({queryKey:sessionKey});ElMessage.success({ message: '资料已保存' })}catch(e){error.value=readableError(e)}finally{busy.value=false}}
</script>
<template><div class="page"><div class="page-head"><div><h1 class="page-title">个人中心</h1><p class="page-lead">管理公开昵称与个人简介</p></div></div><section class="profile surface"><div class="identity"><div class="large-avatar">{{(s.user.value?.userName||'U').slice(0,1)}}</div><div><strong>{{s.user.value?.userName||'未命名用户'}}</strong><p class="membership" :class="{'membership-vip':isVip}" role="status">{{identityLabel}}</p><el-button v-if="memberEnabled&&membership.isError.value" class="membership-retry" size="small" text :loading="membership.isFetching.value" @click="membership.refetch()">重试</el-button></div></div><el-form label-position="top"><el-form-item label="昵称"><el-input v-model="form.userName" maxlength="256" show-word-limit/></el-form-item><el-form-item label="个人简介"><el-input v-model="form.userProfile" type="textarea" :rows="5" maxlength="512" show-word-limit/></el-form-item><p v-if="error" class="error">{{error}}</p><el-button type="primary" :loading="busy" @click="save">保存资料</el-button></el-form></section></div></template>
<style scoped>.profile{padding:28px;max-width:760px}.identity{display:flex;align-items:center;gap:16px;padding-bottom:24px;margin-bottom:24px;border-bottom:1px solid var(--oj-border)}.identity p{margin:5px 0 0;color:var(--oj-text-muted)}.identity .membership-vip{display:block;width:fit-content;padding:3px 8px;border:1px solid rgba(229,185,99,.35);border-radius:5px;background:rgba(229,185,99,.1);color:#e5b963;font-size:13px;font-weight:600}.large-avatar{width:54px;height:54px;border-radius:14px;display:grid;place-items:center;background:#1c355b;border:1px solid #2b4f82;color:#dbe8ff;font-size:20px;font-weight:700;flex-shrink:0}.error{color:var(--oj-danger)}</style>

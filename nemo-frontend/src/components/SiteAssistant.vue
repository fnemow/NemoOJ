<script setup lang="ts">
import { computed,defineAsyncComponent,nextTick,onBeforeUnmount,ref,watch } from 'vue'
import { useSession } from '@/composables/useSession'
import { assistantVisible,assistantTab,assistantProblemNo,openSiteAssistant,closeSiteAssistant } from '@/composables/useSiteAssistant'
import { askSiteAssistant,getHelpGuides,getSiteHelpAnswer,type HelpAnswer,type HelpGuide,type HelpTurn } from '@/api/siteAssistant'
import { readableError } from '@/api/errors'
import { filingMode } from '@/app/siteMode'

const FeedbackPage=defineAsyncComponent(()=>import('@/pages/FeedbackPage.vue'))
const session=useSession(),input=ref(''),busy=ref(false),error=ref(''),guides=ref<HelpGuide[]>([])
const messages=ref<Array<HelpTurn&{answer?:HelpAnswer}>>([]),scrollHost=ref<HTMLElement>()
const allowed=computed(()=>!filingMode&&session.user.value?.userRole!=='admin')
const loggedIn=computed(()=>session.user.value?.userRole==='user')
let epoch=0,abort:AbortController|undefined,timer:ReturnType<typeof setTimeout>|undefined
const waiting=ref('')
const quickKeys=['submit_flow','submission_modes','result_refresh','submission_history','editor_draft','ai_analysis']
const quickGuides=computed(()=>guides.value.filter(g=>quickKeys.includes(g.key)))
function stop(){epoch++;abort?.abort();clearTimeout(timer);busy.value=false;waiting.value=''}
function reset(){stop();messages.value=[];input.value='';error.value='';closeSiteAssistant();assistantProblemNo.value=undefined}
watch(()=>session.user.value?.id,reset)
watch(()=>session.user.value?.userRole,role=>{if(role==='admin')reset()})
watch(assistantVisible,async visible=>{
  if(!visible||guides.value.length)return
  try{guides.value=await getHelpGuides()}catch(e){error.value=readableError(e)}
})
async function scroll(){await nextTick();scrollHost.value?.scrollTo?.({top:scrollHost.value.scrollHeight})}
function accept(answer:HelpAnswer){messages.value.push({role:'assistant',text:answer.text,answer});if(messages.value.length>24)messages.value.splice(0,messages.value.length-24);void scroll()}
async function waitAnswer(id:string,token:number,signal:AbortSignal){
  const until=Date.now()+180_000
  while(token===epoch&&!signal.aborted&&Date.now()<until){
    const response=await getSiteHelpAnswer(id,signal)
    if(token!==epoch||signal.aborted)return
    if(response.status==='SUCCEEDED'&&response.answer){accept(response.answer);return}
    if(['FAILED','CANCELLED'].includes(response.status))throw new Error('网站助手暂时无法完成问答，请查看使用指南或提交人工反馈。')
    waiting.value=response.status==='QUEUED'?'正在排队…':'正在整理使用说明…'
    await new Promise<void>(resolve=>{
      const finish=()=>{signal.removeEventListener('abort',cancel);resolve()}
      const cancel=()=>{clearTimeout(timer);finish()}
      timer=setTimeout(finish,1500);signal.addEventListener('abort',cancel,{once:true})
    })
  }
  if(token===epoch&&!signal.aborted)throw new Error('问答等待超时，可查看使用指南；原任务可能仍在处理，请稍后再试。')
}
async function send(){
  const message=input.value.trim();if(!message||busy.value||!loggedIn.value)return
  const history=messages.value.slice(-6).map(({role,text})=>({role,text}))
  const token=++epoch;abort=new AbortController();const signal=abort.signal
  messages.value.push({role:'user',text:message});input.value='';busy.value=true;error.value='';waiting.value='正在发送…';void scroll()
  try{
    const response=await askSiteAssistant(message,history,crypto.randomUUID(),signal)
    if(token!==epoch||signal.aborted)return
    if(response.answer)accept(response.answer)
    else if(response.taskId)await waitAnswer(response.taskId,token,signal)
    else throw new Error('网站助手暂不可用')
  }catch(e){if(token===epoch&&!signal.aborted)error.value=readableError(e)}
  finally{if(token===epoch){busy.value=false;waiting.value=''}}
}
function guide(g:HelpGuide){accept({kind:'HELP',text:`${g.title}：\n${g.text}`,links:g.links})}
function follow(event:MouseEvent,to:string){if(to==='/feedback'){event.preventDefault();assistantTab.value='feedback'}else closeSiteAssistant()}
function keydown(event:KeyboardEvent){if(event.key==='Enter'&&!event.shiftKey&&!event.isComposing){event.preventDefault();void send()}}
onBeforeUnmount(stop)
</script>

<template>
  <template v-if="allowed">
    <button v-if="!assistantVisible" class="assistant-launcher" type="button" aria-label="打开网站 AI 助手" @click="openSiteAssistant()"><span aria-hidden="true">✦</span> AI 助手</button>
    <section v-else class="site-assistant surface" role="dialog" aria-label="网站 AI 助手" @keydown.esc="closeSiteAssistant()">
      <header><div><strong>网站 AI 助手</strong><small>网站答疑与操作流程 · 不分析代码</small></div><button type="button" aria-label="关闭网站助手" @click="closeSiteAssistant()">×</button></header>
      <nav aria-label="助手分页"><button :aria-pressed="assistantTab==='help'" @click="assistantTab='help'">网站问答</button><button :aria-pressed="assistantTab==='feedback'" @click="assistantTab='feedback'">人工反馈</button></nav>
      <div v-if="assistantTab==='help'" class="help-body">
        <div ref="scrollHost" class="assistant-messages" aria-live="polite">
          <p class="assistant-intro">可以问我如何查看题目、提交代码、刷新结果和使用网站功能。代码报错、解题思路或优化建议，请使用提交详情中的 AI 代码分析。</p>
          <div class="quick-guides"><button v-for="g in quickGuides" :key="g.key" type="button" @click="guide(g)">{{g.title}}</button></div>
          <article v-for="(item,index) in messages" :key="index" :class="['help-message',item.role]"><small>{{item.role==='user'?'你':'网站助手'}}</small><p>{{item.text}}</p><div v-if="item.answer?.links?.length" class="help-links"><RouterLink v-for="link in item.answer.links" :key="link.to" :to="link.to" @click="follow($event,link.to)">{{link.label}} →</RouterLink></div></article>
          <p v-if="busy" class="assistant-wait"><span class="assistant-spinner" aria-hidden="true"/>{{waiting}}</p>
          <p v-if="error" role="alert" class="assistant-error">{{error}}</p>
        </div>
        <div v-if="!loggedIn" class="assistant-login">登录后可发送网站问题；上方使用指南无需登录。<RouterLink to="/login" @click="closeSiteAssistant()">去登录</RouterLink></div>
        <form v-else class="assistant-compose" @submit.prevent="send"><textarea v-model="input" maxlength="800" rows="2" placeholder="询问网站使用流程，请勿粘贴代码或密码" aria-label="网站问题" :disabled="busy" @keydown="keydown"/><div><small>{{input.length}}/800 · Enter 发送，Shift+Enter 换行</small><button type="submit" :disabled="busy||!input.trim()">发送</button></div></form>
      </div>
      <div v-else class="manual-feedback"><FeedbackPage v-if="loggedIn" :problem-no="assistantProblemNo"/><p v-else>请先<RouterLink to="/login" @click="closeSiteAssistant()">登录</RouterLink>后提交人工反馈。</p></div>
    </section>
  </template>
</template>

<style scoped>
.assistant-launcher{position:fixed;right:24px;bottom:24px;z-index:90;border:1px solid var(--oj-border);border-radius:24px;background:var(--oj-surface);color:var(--oj-text);padding:12px 18px;box-shadow:0 6px 26px #0004;cursor:pointer}.assistant-launcher span{color:var(--oj-accent);margin-right:6px}
.site-assistant{position:fixed;right:24px;bottom:24px;z-index:100;width:460px;max-width:calc(100vw - 24px);height:min(680px,85dvh);display:flex;flex-direction:column;border:1px solid var(--oj-border);border-radius:16px;box-shadow:0 12px 48px #0006;overflow:hidden;background:var(--oj-surface);color:var(--oj-text)}
header{display:flex;justify-content:space-between;gap:12px;padding:16px;border-bottom:1px solid var(--oj-border)}header div{display:flex;flex-direction:column;gap:5px}header small{color:var(--oj-text-muted);font-size:11px}header button{background:none;border:0;color:inherit;font-size:24px;cursor:pointer}
nav{display:flex;gap:8px;padding:8px 14px;border-bottom:1px solid var(--oj-border)}nav button,.quick-guides button{border:1px solid var(--oj-border);border-radius:8px;padding:6px 10px;background:transparent;color:var(--oj-text-muted);cursor:pointer}nav button[aria-pressed=true]{color:var(--oj-accent);border-color:var(--oj-accent)}
.help-body{flex:1;min-height:0;display:flex;flex-direction:column}.assistant-messages{padding:14px;overflow-y:auto;flex:1;min-height:0}.assistant-intro{font-size:13px;line-height:1.7;color:var(--oj-text-muted);margin:0 0 12px}.quick-guides{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:14px}.quick-guides button{font-size:12px}.help-message{margin-bottom:12px;padding:12px;border:1px solid var(--oj-border);border-radius:10px;overflow-wrap:anywhere}.help-message.user{background:rgba(90,140,255,.09);margin-left:30px}.help-message.assistant{margin-right:15px}.help-message small{font-size:11px;color:var(--oj-text-muted)}.help-message p{margin:6px 0;font-size:13px;line-height:1.75;white-space:pre-wrap}.help-links{display:flex;gap:10px;flex-wrap:wrap}.help-links a,.assistant-login a,.manual-feedback a{font-size:12px;color:var(--oj-accent)}
.assistant-compose{border-top:1px solid var(--oj-border);padding:12px}.assistant-compose textarea{box-sizing:border-box;width:100%;resize:vertical;max-height:100px;background:var(--oj-surface);color:var(--oj-text);border:1px solid var(--oj-border);border-radius:8px;padding:8px;font:inherit;font-size:13px}.assistant-compose>div{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-top:6px}.assistant-compose small{font-size:10px;color:var(--oj-text-muted)}.assistant-compose button{border:0;border-radius:8px;padding:7px 16px;background:var(--oj-primary);color:white;cursor:pointer}.assistant-compose button:disabled{opacity:.5;cursor:default}.assistant-login{padding:12px;border-top:1px solid var(--oj-border);font-size:12px}.assistant-error{color:var(--oj-danger);font-size:12px}.assistant-wait{display:flex;align-items:center;gap:7px;font-size:12px;color:var(--oj-text-muted)}.assistant-spinner{width:12px;height:12px;border:2px solid var(--oj-border);border-top-color:var(--oj-accent);border-radius:50%;animation:assistant-spin .8s linear infinite}
.manual-feedback{flex:1;min-height:0;overflow:auto;padding:12px}.manual-feedback :deep(.page){padding:0}.manual-feedback :deep(.page-head){display:none}.manual-feedback :deep(.feedback-grid){display:block}.manual-feedback :deep(.compose),.manual-feedback :deep(.history){padding:12px;margin-bottom:12px}.manual-feedback :deep(h2){font-size:14px}.manual-feedback :deep(.el-table){font-size:12px}
@keyframes assistant-spin{to{transform:rotate(360deg)}}@media(prefers-reduced-motion:reduce){.assistant-spinner{animation:none}}@media(max-width:600px){.site-assistant{right:12px;bottom:max(12px,env(safe-area-inset-bottom));width:calc(100vw - 24px);height:80dvh}.assistant-launcher{right:12px;bottom:16px}}
</style>

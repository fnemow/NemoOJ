<script setup lang="ts">
import { useRouter } from 'vue-router'
import type { User } from '@/api/types'
import { filingMode, registrationEnabled } from '@/app/siteMode'
import penguinLogo from '@/assets/home-penguin.png'
const props=defineProps<{ user?: User | null | undefined; state: string; compact?: boolean }>()
const emit = defineEmits<{ logout: [] }>()
const router = useRouter()
const isAdmin=()=>props.user?.userRole==='admin'
</script>
<template>
  <aside class="sidebar" :class="{ compact }">
    <div class="sidebar-inner">
      <RouterLink class="brand" :to="isAdmin()?'/admin/problems':'/'" :aria-label="isAdmin()?'nemo的算法小站管理后台':'nemo的算法小站首页'"><span class="brand-mark" aria-hidden="true"><img :src="penguinLogo" alt="" width="30" height="30" /></span><span class="brand-copy"><strong>nemo的算法小站</strong><small>ALGORITHM SPACE</small></span></RouterLink>
      <nav class="nav" aria-label="主导航">
        <template v-if="isAdmin()"><RouterLink to="/admin/problems"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="4" width="16" height="16" rx="2"/><path d="M4 10h16M10 4v16M15 10v10M4 15h16"/></svg><span>管理后台</span></RouterLink></template>
        <template v-else>
          <RouterLink to="/problems"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="4" width="16" height="16" rx="2"/><path d="M4 10h16M10 4v16M15 10v10M4 15h16"/></svg><span>题库</span></RouterLink>
          <RouterLink v-if="user" to="/submissions"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9l-6-6Z"/><path d="M14 3v6h6M8 13h8M8 17h5"/></svg><span>提交记录</span></RouterLink>
          <RouterLink v-if="user" to="/study-plans"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 2.7 5.5 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.8 1-6.1-4.4-4.3 6.1-.9L12 3Z"/></svg><span>AI 训练</span></RouterLink>
        </template>
      </nav>
      <div class="account">
        <span v-if="filingMode" class="session-warning">当前仅开放题库浏览</span>
        <span v-if="state==='unavailable'" class="session-warning">会话服务暂不可用</span>
        <template v-if="user">
          <button class="profile" :aria-label="`${user.userName || user.userAccount || '用户'}的菜单`" @click="router.push(isAdmin()?'/admin/problems':'/me')">
            <span class="avatar">{{ (user.userName || user.userAccount || 'U').slice(0,1) }}</span>
            <span class="profile-copy"><strong>{{ user.userName || user.userAccount || '未命名用户' }}</strong><small>{{ user.userRole==='admin'?'管理员':'个人中心' }}</small></span>
          </button>
          <el-button class="logout" link @click="emit('logout')">退出</el-button>
        </template>
        <template v-else-if="state!=='unknown' && !filingMode">
          <RouterLink class="login" to="/login"><svg viewBox="0 0 20 20" aria-hidden="true" focusable="false"><path d="M11 3h5v14h-5M3 10h9M8 6l4 4-4 4" /></svg><span>登录</span></RouterLink><el-button v-if="registrationEnabled" type="primary" size="small" @click="router.push('/register')">注册</el-button>
        </template>
      </div>
    </div>
  </aside>
</template>
<style scoped>
.sidebar{height:100dvh;min-width:0;position:sticky;top:0;z-index:20;border-right:1px solid var(--oj-border);background:radial-gradient(ellipse at 85% 24%,rgba(255,255,255,.035),transparent 65%),linear-gradient(170deg,#2a2c32,#222429);box-shadow:8px 0 28px rgba(0,0,0,.08)}
.sidebar-inner{height:100%;padding:28px 14px 24px;display:flex;flex-direction:column}
.brand{display:flex;align-items:center;gap:11px;padding:0 6px 28px;color:var(--oj-text)}
.brand-mark,.avatar{flex:none;width:36px;height:36px;border:1px solid #55585f;border-radius:9px;background:linear-gradient(140deg,#3d4047,#25272d);box-shadow:0 3px 15px rgba(0,0,0,.2),inset 0 1px 0 rgba(255,255,255,.06);display:grid;place-items:center;color:#fff;font-weight:800}
.brand-copy,.profile-copy{min-width:0;display:flex;flex-direction:column}
.brand-mark img{display:block;width:30px;height:30px;object-fit:contain}
.brand-copy strong{font-size:14px;letter-spacing:.01em;white-space:nowrap}.brand-copy small{margin-top:4px;font-size:9px;letter-spacing:.06em;color:var(--oj-text-muted)}
.nav{display:flex;flex-direction:column;gap:8px;padding-top:4px}
.nav a{position:relative;min-height:43px;padding:0 13px;display:flex;align-items:center;gap:12px;border:1px solid transparent;border-radius:9px;color:var(--oj-text-muted);font-size:13px;transition:background .2s,color .2s,border-color .2s}
.nav a:hover{background:rgba(255,255,255,.045);color:var(--oj-text);border-color:rgba(255,255,255,.06)}
.nav a.router-link-active{color:#f2f5ff;background:linear-gradient(105deg,#3c424e,#333740);border-color:#55637d;box-shadow:inset 0 1px 0 rgba(255,255,255,.06),0 4px 18px rgba(0,0,0,.1)}
.nav a.router-link-active::before{content:"";position:absolute;left:0;top:11px;bottom:11px;width:3px;border-radius:3px;background:var(--oj-primary)}
.nav svg{width:18px;height:18px;flex:none;color:#99afe0;fill:none;stroke:currentColor;stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round}.nav a.router-link-active svg{color:#bcd2ff}
.account{margin-top:auto;padding:18px 10px 0;border-top:1px solid var(--oj-border);display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.profile{border:0;background:transparent;color:inherit;padding:0;min-width:0;display:flex;align-items:center;gap:10px;text-align:left;cursor:pointer;flex:1}
.avatar{width:34px;height:34px;background:linear-gradient(145deg,#404650,#30343d);border:1px solid #535d70;box-shadow:none;color:#dbe8ff}
.profile-copy{gap:4px}.profile-copy strong{max-width:95px;font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.profile-copy small{font-size:10px;color:var(--oj-text-muted)}
.logout{margin-left:auto;color:#c1c9d9;font-size:12px}.login{display:inline-flex;align-items:center;justify-content:center;gap:8px;min-height:36px;box-sizing:border-box;padding:0 18px;border:1px solid #779af7;border-radius:8px;background:linear-gradient(110deg,#416ce2,#6289f3);box-shadow:inset 0 1px 0 rgba(255,255,255,.14),0 3px 16px rgba(38,59,116,.22);color:#fff;font-size:13px;font-weight:600;transition:filter .18s,border-color .18s,box-shadow .18s}.login:hover{color:#fff;filter:brightness(1.12);border-color:#b2c7ff;box-shadow:0 4px 18px rgba(65,108,226,.28)}.login:active{filter:brightness(.95)}.login:focus-visible{outline:2px solid #b8ceff;outline-offset:3px}.login svg{width:16px;height:16px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}.session-warning{width:100%;color:var(--oj-warning);font-size:11px}
@media(max-width:900px){.sidebar{height:64px;border-right:0;border-bottom:1px solid var(--oj-border)}.sidebar-inner{height:64px;padding:0 14px;flex-direction:row;align-items:center;gap:12px}.brand{padding:0;flex:none}.brand-mark{width:32px;height:32px}.brand-copy small{display:none}.nav{min-width:0;flex:1;padding:0;flex-direction:row;gap:4px;overflow-x:auto;scrollbar-width:thin}.nav a{flex:none;min-height:38px;padding:0 10px;gap:7px;white-space:nowrap}.nav svg{width:16px;height:16px}.account{margin:0;padding:0;border:0;flex:none;flex-wrap:nowrap}.profile-copy,.session-warning{display:none}.profile{flex:none}.logout{display:none}}
@media(max-width:600px){.sidebar-inner{padding:0 10px;gap:8px}.brand-copy{display:none}.nav a{padding:0 9px;font-size:12px}.nav svg{display:none}.nav a.router-link-active::before{top:10px;bottom:10px;width:2px}.account .login{font-size:12px}}
</style>

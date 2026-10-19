<script setup lang="ts">
import { useSession } from '@/composables/useSession'
import { useRouter } from 'vue-router'
const session=useSession(),router=useRouter()
async function leave(){await session.logout();await router.push('/login')}
</script>
<template>
  <div class="admin-shell">
    <aside class="admin-nav">
      <RouterLink to="/admin/problems" class="console-title" aria-label="NemoOJ 管理后台首页">
        <span class="console-mark">N</span><div><strong>NemoOJ 管理后台</strong><small>ADMIN CONSOLE</small></div>
      </RouterLink>
      <nav aria-label="管理导航">
        <RouterLink to="/admin/problems"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="4" width="16" height="16" rx="2" /><path d="M4 10h16M10 4v16M15 10v10M4 15h16" /></svg>题目管理</RouterLink>
        <RouterLink to="/admin/feedback"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 19 4 21v-5a8 8 0 1 1 4 3Z" /><path d="M8 11h.01M12 11h.01M16 11h.01" /></svg>反馈处理</RouterLink>
        <RouterLink to="/admin/ai"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 8 4v5c0 5-8 9-8 9s-8-4-8-9V7l8-4Z" /><path d="m8 12 3 3 5-6" /></svg>AI 巡检</RouterLink>
        <RouterLink to="/admin/users"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="7" r="4" /><path d="M5 21v-3a7 7 0 0 1 14 0v3H5Z" /></svg>用户管理</RouterLink>
      </nav>
      <div class="admin-account"><span class="avatar">{{(session.user.value?.userName||'A').slice(0,1)}}</span><div><strong>{{session.user.value?.userName||session.user.value?.userAccount||'管理员'}}</strong><small>管理员账号</small></div><el-button link @click="leave">退出</el-button></div>
    </aside>
    <main><RouterView /></main>
  </div>
</template>
<style scoped>
.admin-shell{
  --oj-bg:#0d121c;--oj-surface:#171e2b;--oj-surface-soft:#1c2433;--oj-surface-raised:#222c3e;
  --oj-border:#2b3548;--oj-border-strong:#435477;--oj-text:#edf2ff;--oj-text-muted:#98a5bd;
  --oj-primary:#83aaff;--el-fill-color-blank:var(--oj-surface);--el-fill-color-light:#202b40;
  --el-fill-color:#1a2638;--el-fill-color-lighter:#141e2c;--el-fill-color-extra-light:#111a27;
  --el-fill-color-dark:#202e43;--el-fill-color-darker:#293a52;
  --el-text-color-regular:#c5d2e3;--el-text-color-placeholder:#687b94;
  --el-border-color-light:#202d40;--el-border-color-lighter:#1b2636;
  min-height:100dvh;display:grid;grid-template-columns:var(--oj-sidebar-width) minmax(0,1fr);
  color:var(--oj-text);background:radial-gradient(ellipse at 60% 0,rgba(58,85,153,.09),transparent 55%),var(--oj-bg)
}
.admin-nav{position:sticky;top:0;height:100dvh;padding:28px 14px 24px;border-right:1px solid #29334a;background:radial-gradient(ellipse at 85% 24%,rgba(47,76,151,.13),transparent 65%),linear-gradient(170deg,#18202f,#101724);display:flex;flex-direction:column;box-shadow:8px 0 28px rgba(0,0,0,.08)}
.console-title{display:flex;align-items:center;gap:11px;padding:0 10px 28px;color:#f1f5ff}
.console-mark,.avatar{flex:none;width:36px;height:36px;border:1px solid rgba(126,161,255,.5);border-radius:9px;background:linear-gradient(140deg,#697cff,#3984ff);box-shadow:0 3px 15px rgba(57,112,255,.18),inset 0 1px 0 rgba(255,255,255,.15);display:grid;place-items:center;color:#fff;font-weight:800}
.console-title strong{font-size:13px;white-space:nowrap}.console-title div,.admin-account div{min-width:0;display:flex;flex-direction:column}
.console-title small,.admin-account small{margin-top:4px;color:#8f9cb4;font-size:10px}.console-title small{font-size:9px;letter-spacing:.05em}
.admin-nav nav{display:flex;flex-direction:column;gap:8px;padding-top:4px}.admin-nav nav a{min-height:43px;padding:0 13px;border:1px solid transparent;border-radius:9px;display:flex;align-items:center;gap:12px;color:#a8b4ce;font-size:13px;transition:background .2s,color .2s,border-color .2s}
.admin-nav nav a:hover{background:rgba(83,120,206,.1);color:#ecf2ff;border-color:rgba(115,149,225,.12)}
.admin-nav nav a.router-link-active{color:#f3f6ff;background:linear-gradient(105deg,#283d8b,#3d65da);border-color:#456cf0;box-shadow:inset 0 1px 0 rgba(255,255,255,.1),0 4px 18px rgba(35,66,156,.22)}
.admin-nav nav svg{width:18px;height:18px;flex:none;color:#8dafff;fill:none;stroke:currentColor;stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round}.admin-nav nav a.router-link-active svg{color:#bcd2ff}
.admin-account{margin-top:auto;padding:18px 10px 0;border-top:1px solid #2c374d;display:flex;align-items:center;gap:10px}.admin-account strong{max-width:95px;font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.admin-account .avatar{width:34px;height:34px;background:linear-gradient(145deg,#294576,#192b4d);border:1px solid #355587;box-shadow:none}.admin-account .el-button{margin-left:auto;color:#b3c2dd}
.admin-shell>main{min-width:0}
.admin-shell :deep(.surface){background:linear-gradient(125deg,rgba(30,38,53,.95),rgba(19,25,36,.98));border-color:#2a3548;box-shadow:0 10px 28px rgba(0,0,0,.14)}
.admin-shell :deep(.el-button--primary:not(.is-link)){--el-button-bg-color:#4b7bff;--el-button-border-color:#618cff;--el-button-text-color:#fff;--el-button-hover-text-color:#fff;--el-button-hover-border-color:#8babff;background:linear-gradient(110deg,#3e70f5,#5c85ff);box-shadow:inset 0 1px 0 rgba(255,255,255,.12),0 3px 16px rgba(50,94,211,.16)}
.admin-shell :deep(.el-button--primary:not(.is-link):hover){filter:brightness(1.09)}
.admin-shell :deep(.el-table){--el-table-bg-color:#151c28;--el-table-tr-bg-color:#151c28;--el-table-header-bg-color:#182239;--el-table-row-hover-bg-color:#24324c;--el-table-current-row-bg-color:#24324c;--el-table-border-color:#273144;--el-table-text-color:#ced8eb;--el-table-header-text-color:#a0b8e9}
.admin-shell :deep(.el-table--striped .el-table__body tr.el-table__row--striped:not(.hover-row)>td.el-table__cell){background:#1d2635}
@media(max-width:900px){.admin-shell{display:block}.admin-nav{height:auto;position:sticky;top:0;z-index:20;padding:10px 14px;flex-direction:row;align-items:center;overflow:auto}.console-title div,.admin-account div,.admin-account .avatar{display:none}.console-title{padding:0 12px 0 0}.console-mark{width:32px;height:32px}.admin-nav nav{padding:0;flex-direction:row;gap:4px}.admin-nav nav a{white-space:nowrap;padding:0 10px}.admin-account{margin:0 0 0 auto;padding:0 0 0 12px;border:0}.admin-account .el-button{padding:0}.admin-nav nav svg{width:16px;height:16px}}
@media(max-width:540px){.admin-nav{padding:10px}.admin-nav nav a{padding:0 8px;gap:6px;font-size:12px}.admin-nav nav svg{display:none}.console-title{padding-right:9px}.console-mark{width:29px;height:29px}.admin-account{padding-left:9px}}
</style>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import AppHeader from '@/components/AppHeader.vue'
import { useSession } from '@/composables/useSession'
const session=useSession();const router=useRouter()
async function leave(){await session.logout();await router.push('/problems')}
</script>
<template><div class="app-shell user-shell"><AppHeader :user="session.user.value" :state="session.state.value" @logout="leave" /><main><RouterView /></main></div></template>
<style scoped>.app-shell{min-height:100dvh;display:grid;grid-template-columns:var(--oj-sidebar-width) minmax(0,1fr)}.app-shell>main{min-width:0}@media(max-width:900px){.app-shell{display:block}}</style>

<script setup lang="ts">
import { watch } from 'vue'
import { useRoute } from 'vue-router'
import SiteAssistant from '@/components/SiteAssistant.vue'
import { closeSiteAssistant } from '@/composables/useSiteAssistant'
const route=useRoute()
watch(()=>route.name,name=>{if(name==='home')closeSiteAssistant()},{immediate:true})
</script>
<template><RouterView /><SiteAssistant v-if="route.name!=='home'" /></template>

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { VueQueryPlugin } from '@tanstack/vue-query'
import { ElAlert,ElButton,ElDialog,ElDivider,ElDrawer,ElEmpty,ElForm,ElFormItem,ElInput,ElInputNumber,ElLoading,ElOption,ElPagination,ElProgress,ElRadioButton,ElRadioGroup,ElSelect,ElSkeleton,ElSwitch,ElTabPane,ElTabs,ElTable,ElTableColumn,ElTag } from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { queryClient } from './state'
import '@/styles/tokens.css'
import '@/styles/base.css'
import '@/styles/user-theme.css'

const app=createApp(App).use(createPinia()).use(VueQueryPlugin, { queryClient }).use(router).use(ElLoading)
for(const component of [ElAlert,ElButton,ElDialog,ElDivider,ElDrawer,ElEmpty,ElForm,ElFormItem,ElInput,ElInputNumber,ElOption,ElPagination,ElProgress,ElRadioButton,ElRadioGroup,ElSelect,ElSkeleton,ElSwitch,ElTabPane,ElTabs,ElTable,ElTableColumn,ElTag])app.component(component.name!,component)
app.mount('#app')

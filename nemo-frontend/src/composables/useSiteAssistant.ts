import { ref } from 'vue'
export const assistantVisible=ref(false)
export const assistantTab=ref<'help'|'feedback'>('help')
export const assistantProblemNo=ref<number>()
export function openSiteAssistant(tab:'help'|'feedback'='help',problemNo?:number){assistantTab.value=tab;assistantProblemNo.value=problemNo;assistantVisible.value=true}
export function closeSiteAssistant(){assistantVisible.value=false}

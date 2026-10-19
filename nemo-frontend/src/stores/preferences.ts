import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

const KEY = 'nemooj.preferences.v1'
interface Persisted { editorTheme: 'vs'|'vs-dark'; fontSize: number; splitRatio: number; preferredLanguage: string }

export const usePreferencesStore = defineStore('preferences', () => {
  const saved = (() => { try { return JSON.parse(localStorage.getItem(KEY) ?? '{}') as Partial<Persisted> } catch { return {} } })()
  const editorTheme = ref<'vs'|'vs-dark'>(saved.editorTheme === 'vs' ? 'vs' : 'vs-dark')
  const fontSize = ref(Math.min(22, Math.max(12, saved.fontSize ?? 14)))
  const splitRatio = ref(Math.min(.65, Math.max(.32, saved.splitRatio ?? .42)))
  const preferredLanguage = ref(saved.preferredLanguage ?? 'java')
  watch([editorTheme, fontSize, splitRatio, preferredLanguage], () => {
    localStorage.setItem(KEY, JSON.stringify({ editorTheme: editorTheme.value, fontSize: fontSize.value, splitRatio: splitRatio.value, preferredLanguage: preferredLanguage.value }))
  }, { deep: true })
  return { editorTheme, fontSize, splitRatio, preferredLanguage }
})

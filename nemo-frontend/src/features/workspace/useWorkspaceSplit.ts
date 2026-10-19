import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'

const DIVIDER = 8
const MIN_PROBLEM = 310
const MIN_CODE = 430
const MIN_EDITOR = 260
const MIN_RESULT = 160
const TOOLBAR = 58
const DRAFT = 30

function storedNumber(key: string, fallback: number) {
  try {
    const value = Number(localStorage.getItem(key))
    return Number.isFinite(value) && value > 0 ? value : fallback
  } catch { return fallback }
}
function storeNumber(key: string, value: number) {
  try { localStorage.setItem(key, String(value)) } catch { /* Optional preference. */ }
}
function clamp(value: number, min: number, max: number) { return Math.min(Math.max(value, min), Math.max(min, max)) }

export function useWorkspaceSplit(split: Ref<HTMLElement | null>, codePane: Ref<HTMLElement | null>) {
  const problemWidth = ref(storedNumber('nemooj.workspace.problemWidth', 44))
  const resultHeight = ref(storedNumber('nemooj.workspace.resultHeight', 210))
  const codePaneHeight = ref(726)
  const maxResultHeight = computed(() => Math.max(MIN_RESULT, codePaneHeight.value - TOOLBAR - DRAFT - DIVIDER - MIN_EDITOR))
  const visibleResultHeight = computed(() => Math.min(resultHeight.value, maxResultHeight.value))
  const problemPercent = computed(() => `${problemWidth.value}%`)
  const resultPixels = computed(() => `${visibleResultHeight.value}px`)
  let stopDragging: (() => void) | undefined
  let observer: ResizeObserver | undefined
  watch(codePane, element => {
    observer?.disconnect()
    if (!element) return
    codePaneHeight.value = element.clientHeight
    if (typeof ResizeObserver === 'undefined') return
    observer = new ResizeObserver(() => { codePaneHeight.value = element.clientHeight })
    observer.observe(element)
  }, { immediate: true })

  function setProblemPixels(pixels: number) {
    const width = split.value?.clientWidth ?? 0
    if (width < MIN_PROBLEM + DIVIDER + MIN_CODE) return
    problemWidth.value = Math.round(clamp(pixels, MIN_PROBLEM, width - DIVIDER - MIN_CODE) / width * 1000) / 10
  }
  function setResultPixels(pixels: number) {
    resultHeight.value = Math.round(clamp(pixels, MIN_RESULT, maxResultHeight.value))
  }
  function finish() {
    stopDragging?.()
    stopDragging = undefined
  }
  function beginDrag(event: PointerEvent, axis: 'vertical' | 'horizontal') {
    if (event.button !== 0) return
    const host = axis === 'vertical' ? split.value : codePane.value
    if (!host) return
    event.preventDefault()
    finish()
    const handle = event.currentTarget as HTMLElement
    handle.setPointerCapture(event.pointerId)
    const rect = host.getBoundingClientRect()
    const initialHeight = visibleResultHeight.value
    const initialY = event.clientY
    const oldCursor = document.body.style.cursor
    const oldSelection = document.body.style.userSelect
    document.body.style.cursor = axis === 'vertical' ? 'col-resize' : 'row-resize'
    document.body.style.userSelect = 'none'
    const move = (next: PointerEvent) => {
      if (next.pointerId !== event.pointerId) return
      if (axis === 'vertical') setProblemPixels(next.clientX - rect.left)
      else setResultPixels(initialHeight + initialY - next.clientY)
    }
    const end = (next: PointerEvent) => {
      if (next.pointerId !== event.pointerId) return
      if (axis === 'vertical') storeNumber('nemooj.workspace.problemWidth', problemWidth.value)
      else storeNumber('nemooj.workspace.resultHeight', resultHeight.value)
      finish()
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', end)
    window.addEventListener('pointercancel', end)
    stopDragging = () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', end)
      window.removeEventListener('pointercancel', end)
      if (handle.hasPointerCapture(event.pointerId)) handle.releasePointerCapture(event.pointerId)
      document.body.style.cursor = oldCursor
      document.body.style.userSelect = oldSelection
    }
  }
  function onVerticalKeydown(event: KeyboardEvent) {
    const width = split.value?.clientWidth ?? 0
    if (!width) return
    const current = width * problemWidth.value / 100
    const next = event.key === 'ArrowLeft' ? current - 24 : event.key === 'ArrowRight' ? current + 24 : event.key === 'Home' ? MIN_PROBLEM : event.key === 'End' ? width - DIVIDER - MIN_CODE : null
    if (next === null) return
    event.preventDefault()
    setProblemPixels(next)
    storeNumber('nemooj.workspace.problemWidth', problemWidth.value)
  }
  function onHorizontalKeydown(event: KeyboardEvent) {
    const next = event.key === 'ArrowDown' ? visibleResultHeight.value - 24 : event.key === 'ArrowUp' ? visibleResultHeight.value + 24 : event.key === 'Home' ? MIN_RESULT : event.key === 'End' ? maxResultHeight.value : null
    if (next === null) return
    event.preventDefault()
    setResultPixels(next)
    storeNumber('nemooj.workspace.resultHeight', resultHeight.value)
  }
  onBeforeUnmount(() => { finish(); observer?.disconnect() })
  return { problemWidth, problemPercent, visibleResultHeight, resultPixels, maxResultHeight, beginDrag, onVerticalKeydown, onHorizontalKeydown }
}

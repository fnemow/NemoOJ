import { onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'

interface Particle {
  x: number
  y: number
  vx: number
  vy: number
  radius: number
  phase: number
  accent: boolean
}

/** Decorative only: capped at 30fps, no network/dependencies, stopped off-page. */
export function useParticleBackground(canvas: Ref<HTMLCanvasElement | undefined>) {
  const paused = ref(false)
  const reducedMotion = ref(false)
  let context: CanvasRenderingContext2D | null = null
  let observer: ResizeObserver | undefined
  let preference: MediaQueryList | undefined
  let frame = 0
  let lastPaint = 0
  let width = 0
  let height = 0
  let particles: Particle[] = []

  function paint(delta: number, time: number) {
    if (!context) return
    context.clearRect(0, 0, width, height)
    for (const point of particles) {
      point.x = (point.x + point.vx * delta + 1) % 1
      point.y = (point.y + point.vy * delta + 1) % 1
    }
    context.lineWidth = 0.6
    const range = width < 600 ? 110 : 150
    for (let i = 0; i < particles.length; i++) {
      const a = particles[i]!
      for (let j = i + 1; j < particles.length; j++) {
        const b = particles[j]!
        const distance = Math.hypot((a.x - b.x) * width, (a.y - b.y) * height)
        if (distance > range) continue
        context.strokeStyle = `rgba(84, 124, 222, ${(1 - distance / range) * 0.25})`
        context.beginPath()
        context.moveTo(a.x * width, a.y * height)
        context.lineTo(b.x * width, b.y * height)
        context.stroke()
      }
    }
    for (const point of particles) {
      const alpha = 0.35 + (Math.sin(time / 2400 + point.phase) + 1) * 0.22
      context.fillStyle = point.accent ? `rgba(89, 139, 255, ${alpha})` : `rgba(124, 149, 205, ${alpha})`
      context.beginPath()
      context.arc(point.x * width, point.y * height, point.radius, 0, Math.PI * 2)
      context.fill()
      if (point.accent) {
        context.fillStyle = 'rgba(84, 130, 255, 0.09)'
        context.beginPath()
        context.arc(point.x * width, point.y * height, point.radius * 5, 0, Math.PI * 2)
        context.fill()
      }
    }
  }

  function stop() {
    cancelAnimationFrame(frame)
    frame = 0
    lastPaint = 0
  }
  function animate(time: number) {
    if (!lastPaint || time - lastPaint >= 1000 / 30) {
      paint(lastPaint ? Math.min(time - lastPaint, 64) : 0, time)
      lastPaint = time
    }
    frame = requestAnimationFrame(animate)
  }
  function refresh() {
    stop()
    if (!context) return
    paint(0, 0)
    if (!paused.value && !reducedMotion.value && !document.hidden) {
      frame = requestAnimationFrame(animate)
    }
  }
  function resize() {
    const element = canvas.value
    if (!element || !context) return
    const bounds = element.getBoundingClientRect()
    width = bounds.width
    height = bounds.height
    const ratio = Math.min(window.devicePixelRatio || 1, 1.5)
    element.width = Math.round(width * ratio)
    element.height = Math.round(height * ratio)
    context.setTransform(ratio, 0, 0, ratio, 0, 0)
    const count = Math.min(width < 600 ? 32 : 86, Math.max(24, Math.round(width * height / 16000)))
    particles = Array.from({ length: count }, (_, index) => ({
      x: Math.random(), y: Math.random(),
      vx: (Math.random() - 0.5) * 0.000013,
      vy: (Math.random() - 0.5) * 0.000011,
      radius: index % 6 === 0 ? 1.8 : 0.8 + Math.random() * 0.6,
      phase: Math.random() * Math.PI * 2,
      accent: index % 6 === 0,
    }))
    refresh()
  }
  function preferenceChanged() {
    reducedMotion.value = preference?.matches ?? false
    refresh()
  }

  onMounted(() => {
    preference = window.matchMedia('(prefers-reduced-motion: reduce)')
    reducedMotion.value = preference.matches
    preference.addEventListener('change', preferenceChanged)
    document.addEventListener('visibilitychange', refresh)
    try { context = canvas.value?.getContext('2d') ?? null }
    catch { context = null }
    if (!context || !canvas.value) return
    observer = new ResizeObserver(resize)
    observer.observe(canvas.value)
    resize()
  })
  watch(paused, refresh)
  onBeforeUnmount(() => {
    stop()
    observer?.disconnect()
    preference?.removeEventListener('change', preferenceChanged)
    document.removeEventListener('visibilitychange', refresh)
    context = null
  })
  return { paused, reducedMotion }
}

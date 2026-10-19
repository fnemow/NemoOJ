export const filingMode = import.meta.env.VITE_SITE_MODE === 'filing'
// 注册必须显式开启，与登录和提交开关独立。
export const registrationEnabled = !filingMode && import.meta.env.VITE_REGISTRATION_ENABLED === 'true'

export function filingRedirect(path: string, needsAuth: boolean, filing = filingMode): string | undefined {
  if (filing && (needsAuth || /^\/(?:login|register)(?:\/|$)/i.test(path))) return '/problems'
}

export function registrationRedirect(path: string, enabled = registrationEnabled): string | undefined {
  if (!enabled && /^\/register(?:\/|$)/i.test(path)) return '/login'
}

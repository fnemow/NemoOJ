import { createRouter, createWebHistory, type RouteLocationNormalized } from 'vue-router'
import { queryClient } from './state'
import { sessionKey, sessionQuery } from '@/composables/useSession'
import { filingMode, filingRedirect, registrationEnabled, registrationRedirect } from './siteMode'

declare module 'vue-router' { interface RouteMeta { auth?: boolean; admin?: boolean; memberOnly?: boolean } }

export function safeReturnTo(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/login') || value.startsWith('/register')) return '/problems'
  return value
}

export function roleAccessRedirect(meta: { admin?: boolean; memberOnly?: boolean }, role?: string): string | undefined {
  if (meta.admin && role !== 'admin') return '/403'
  if (meta.memberOnly && role === 'admin') return '/admin/problems'
}

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(to, _from, saved) { return saved ?? (to.path === '/problems' ? undefined : { top: 0 }) },
  routes: [
    { path: '/', name: 'home', component: () => import('@/pages/HomePage.vue') },
    { path: '/', component: () => import('@/layouts/AppLayout.vue'), children: [
      { path: 'problems', name: 'problems', component: () => import('@/pages/ProblemListPage.vue'), meta: { memberOnly: true } },
      { path: 'submissions', name: 'submissions', component: () => import('@/pages/SubmissionListPage.vue'), meta: { auth: true, memberOnly: true } },
      { path: 'submissions/:submissionId', name: 'submission-detail', component: () => import('@/pages/SubmissionDetailPage.vue'), meta: { auth: true, memberOnly: true } },
      { path: 'feedback', name: 'feedback', component: () => import('@/pages/FeedbackPage.vue'), meta: { auth: true, memberOnly: true } },
      { path: 'study-plans', name: 'study-plans', component: () => import('@/pages/TrainingPage.vue'), meta: { auth: true, memberOnly: true } },
      { path: 'private-problems/:id', name: 'private-problem', component: () => import('@/pages/PrivateProblemPage.vue'), meta: { auth: true, memberOnly: true } },
      { path: 'me', name: 'profile', component: () => import('@/pages/ProfilePage.vue'), meta: { auth: true, memberOnly: true } }
    ] },
    { path: '/problems/:problemId', name: 'problem-workspace', component: () => import('@/pages/ProblemWorkspacePage.vue'), meta: { memberOnly: true } },
    ...(!filingMode ? [{ path: '/', component: () => import('@/layouts/AuthLayout.vue'), children: [
      { path: 'login', name: 'login', component: () => import('@/pages/LoginPage.vue') },
      ...(registrationEnabled ? [{ path: 'register', name: 'register', component: () => import('@/pages/RegisterPage.vue') }] : [])
    ] }] : []),
    { path: '/admin', component: () => import('@/layouts/AdminLayout.vue'), meta: { auth: true, admin: true }, children: [
      { path: '', redirect: '/admin/problems' },
      { path: 'problems', name: 'admin-problems', component: () => import('@/pages/admin/AdminProblemListPage.vue') },
      { path: 'problems/new', name: 'admin-problem-new', component: () => import('@/pages/admin/AdminProblemEditPage.vue') },
      { path: 'problems/:problemId/edit', name: 'admin-problem-edit', component: () => import('@/pages/admin/AdminProblemEditPage.vue') },
      { path: 'feedback', name: 'admin-feedback', component: () => import('@/pages/admin/AdminFeedbackPage.vue') },
      { path: 'ai', name: 'admin-ai', component: () => import('@/pages/admin/AdminAiPage.vue') },
      { path: 'users', name: 'admin-users', component: () => import('@/pages/admin/AdminUserPage.vue') }
    ] },
    { path: '/403', component: () => import('@/pages/ForbiddenPage.vue') },
    { path: '/:pathMatch(.*)*', component: () => import('@/pages/NotFoundPage.vue') }
  ]
})

router.beforeEach(async (to: RouteLocationNormalized) => {
  const filingTarget=filingRedirect(to.path,Boolean(to.meta.auth))
  if(filingTarget)return filingTarget
  const registrationTarget=registrationRedirect(to.path)
  if(registrationTarget)return registrationTarget
  if(filingMode)return true
  if (!to.meta.auth && !to.meta.memberOnly) return true
  try {
    const user = await queryClient.ensureQueryData({ queryKey: sessionKey, queryFn: sessionQuery, staleTime: 30_000 })
    if (!user) return to.meta.auth ? { path: '/login', query: { returnTo: safeReturnTo(to.fullPath) } } : true
    const redirect=roleAccessRedirect(to.meta,user.userRole)
    if(redirect)return redirect
  } catch { return true }
  return true
})

export default router

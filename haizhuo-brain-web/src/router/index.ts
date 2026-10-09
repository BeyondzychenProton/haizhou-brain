import { createRouter, createWebHistory } from 'vue-router'
import { loginDestination, requiredPasswordChangeRedirect } from '../api/authPolicies'
import { useAuthStore } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/auth/LoginView.vue') },
    { path: '/activate', name: 'activate', component: () => import('../views/auth/ActivationView.vue') },
    {
      path: '/password/change',
      name: 'password-change',
      component: () => import('../views/auth/PasswordChangeView.vue'),
      meta: { auth: true },
    },
    { path: '/app/employees', name: 'employees', component: () => import('../views/app/EmployeesView.vue'), meta: { auth: true } },
    { path: '/app/sessions', name: 'session-history', component: () => import('../views/app/SessionHistoryView.vue'), meta: { auth: true } },
    { path: '/app/sessions/:sessionId', name: 'session', component: () => import('../views/app/SessionView.vue'), meta: { auth: true } },
    { path: '/admin', name: 'admin', component: () => import('../views/admin/AdminView.vue'), meta: { auth: true, admin: true } },
    { path: '/', redirect: '/app/employees' },
  ],
})

router.beforeEach(async to => {
  const auth = useAuthStore()
  if (to.meta.auth && !auth.currentUser) {
    try {
      await auth.load()
    } catch {
      // 身份读取失败不能证明用户未登录。
      return false
    }
  }

  if (to.meta.auth && !auth.authenticated) return { name: 'login' }
  const passwordRoute = requiredPasswordChangeRedirect(auth.requiresPasswordChange, to.name ?? null)
  if (passwordRoute) return { name: passwordRoute }
  if (to.meta.admin && !auth.isAdmin) return { name: 'employees' }
  if (to.name === 'login' && auth.authenticated) return { name: loginDestination(auth.requiresPasswordChange) }
})

export default router

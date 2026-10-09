import { computed, onScopeDispose, ref } from 'vue'
import { defineStore } from 'pinia'
import * as api from '../api/auth'
import { clearCsrfCache } from '../api/httpClient'
import { publishAuthStateChanged, subscribeToAuthSignals } from '../api/authSignals'
import {
  isUncertainMutationError,
  loginDestination,
  responseStatus,
  shouldRedirectForUnauthorized,
} from '../api/authPolicies'

export type AuthFlowErrorCode =
  | 'LOGIN_SESSION_UNVERIFIED'
  | 'CURRENT_PASSWORD_INVALID'
  | 'SESSION_EXPIRED'
  | 'SESSION_CHECK_UNAVAILABLE'
  | 'REQUEST_VERIFICATION_FAILED'
  | 'INVALID_REQUEST'
  | 'PASSWORD_RESULT_UNCONFIRMED'
  | 'PASSWORD_RESULT_UNCONFIRMED_SESSION_EXPIRED'
  | 'PASSWORD_UPDATED_NEEDS_REFRESH'
  | 'PASSWORD_UPDATED_SESSION_EXPIRED'
  | 'SESSION_CHANGED'
  | 'PASSWORD_CHANGE_FAILED'

export class AuthFlowError extends Error {
  constructor(readonly code: AuthFlowErrorCode) {
    super(code)
    this.name = 'AuthFlowError'
  }
}

function matchesLoginResponse(response: api.LoginResponse, user: api.CurrentUser): boolean {
  return response.userId === user.userId
    && response.mustChangePassword === user.mustChangePassword
    && [...response.roles].sort().join('|') === [...user.roles].sort().join('|')
}

async function redirectToLogin(): Promise<void> {
  try {
    const router = (await import('../router')).default
    const routeName = router.currentRoute.value.name ?? null
    if (shouldRedirectForUnauthorized(401, 'redirect', routeName)) await router.push({ name: 'login' })
  } catch {
    // 下一次受保护的路由跳转会将未登录用户送往登录页。
  }
}

async function redirectAuthenticatedFromLogin(user: api.CurrentUser): Promise<void> {
  try {
    const router = (await import('../router')).default
    if (router.currentRoute.value.name === 'login') {
      await router.push({ name: loginDestination(user.mustChangePassword) })
    }
  } catch {
    // 下一次受保护的路由跳转会使用刷新后的身份信息。
  }
}

export const useAuthStore = defineStore('auth', () => {
  const currentUser = ref<api.CurrentUser | null>(null)
  const loading = ref(false)
  const sessionNeedsRefresh = ref(false)
  const authenticated = computed(() => !!currentUser.value)
  const isAdmin = computed(() => currentUser.value?.roles.includes('PLATFORM_ADMIN') ?? false)
  const requiresPasswordChange = computed(() => currentUser.value?.mustChangePassword ?? false)
  let authGeneration = 0
  let loadingCount = 0

  function startLoading(): void {
    loadingCount += 1
    loading.value = true
  }

  function stopLoading(): void {
    loadingCount = Math.max(0, loadingCount - 1)
    loading.value = loadingCount > 0
  }

  async function load(): Promise<api.CurrentUser | null> {
    const generation = authGeneration
    try {
      const user = await api.me()
      if (generation === authGeneration) {
        currentUser.value = user
        sessionNeedsRefresh.value = false
        await redirectAuthenticatedFromLogin(user)
      }
      return generation === authGeneration ? user : currentUser.value
    } catch (error) {
      if (generation !== authGeneration) return currentUser.value
      if (responseStatus(error) === 401) {
        currentUser.value = null
        sessionNeedsRefresh.value = false
        return null
      }
      sessionNeedsRefresh.value = true
      throw error
    }
  }

  async function login(mobile: string, password: string): Promise<void> {
    const generation = ++authGeneration
    startLoading()
    try {
      const response = await api.login(mobile, password)
      clearCsrfCache()

      let user: api.CurrentUser
      try {
        user = await api.me()
      } catch {
        if (generation === authGeneration) {
          currentUser.value = null
          sessionNeedsRefresh.value = true
        }
        throw new AuthFlowError('LOGIN_SESSION_UNVERIFIED')
      }

      if (generation !== authGeneration) throw new AuthFlowError('SESSION_CHANGED')
      if (!matchesLoginResponse(response, user)) {
        currentUser.value = null
        sessionNeedsRefresh.value = true
        throw new AuthFlowError('LOGIN_SESSION_UNVERIFIED')
      }
      currentUser.value = user
      sessionNeedsRefresh.value = false
      publishAuthStateChanged()
    } finally {
      stopLoading()
    }
  }

  async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
    const expectedUserId = currentUser.value?.userId
    if (expectedUserId === undefined) throw new AuthFlowError('SESSION_EXPIRED')

    const generation = ++authGeneration
    startLoading()
    try {
      let response: api.LoginResponse
      try {
        response = await api.changePassword(currentPassword, newPassword, { authFailureMode: 'local' })
      } catch (error) {
        const status = responseStatus(error)
        if (status === 401) {
          let user: api.CurrentUser
          try {
            user = await api.me()
          } catch (sessionError) {
            if (responseStatus(sessionError) === 401) {
              if (generation === authGeneration) {
                currentUser.value = null
                sessionNeedsRefresh.value = false
              }
              throw new AuthFlowError('SESSION_EXPIRED')
            }
            if (generation === authGeneration) sessionNeedsRefresh.value = true
            throw new AuthFlowError('SESSION_CHECK_UNAVAILABLE')
          }
          if (generation !== authGeneration) throw new AuthFlowError('SESSION_CHANGED')
          if (user.userId !== expectedUserId) {
            currentUser.value = null
            throw new AuthFlowError('SESSION_CHANGED')
          }
          currentUser.value = user
          sessionNeedsRefresh.value = false
          throw new AuthFlowError('CURRENT_PASSWORD_INVALID')
        }
        if (status === 403) throw new AuthFlowError('REQUEST_VERIFICATION_FAILED')
        if (status === 400) throw new AuthFlowError('INVALID_REQUEST')
        if (isUncertainMutationError(error)) {
          await reconcileUncertainPasswordChange(generation, expectedUserId)
          throw new AuthFlowError('PASSWORD_RESULT_UNCONFIRMED')
        }
        throw new AuthFlowError('PASSWORD_CHANGE_FAILED')
      }

      clearCsrfCache()
      if (generation !== authGeneration || currentUser.value?.userId !== expectedUserId || response.userId !== expectedUserId) {
        if (generation === authGeneration) currentUser.value = null
        publishAuthStateChanged()
        throw new AuthFlowError('SESSION_CHANGED')
      }

      const previousUser = currentUser.value
      if (!previousUser) throw new AuthFlowError('SESSION_CHANGED')
      currentUser.value = {
        ...previousUser,
        roles: response.roles,
        mustChangePassword: response.mustChangePassword,
      }
      sessionNeedsRefresh.value = false
      publishAuthStateChanged()

      let verifiedUser: api.CurrentUser
      try {
        verifiedUser = await api.me()
      } catch (error) {
        if (generation === authGeneration && responseStatus(error) === 401) {
          currentUser.value = null
          sessionNeedsRefresh.value = false
          throw new AuthFlowError('PASSWORD_UPDATED_SESSION_EXPIRED')
        }
        if (generation === authGeneration) sessionNeedsRefresh.value = true
        throw new AuthFlowError('PASSWORD_UPDATED_NEEDS_REFRESH')
      }

      if (generation !== authGeneration) throw new AuthFlowError('SESSION_CHANGED')
      if (!matchesLoginResponse(response, verifiedUser)) {
        currentUser.value = null
        sessionNeedsRefresh.value = false
        throw new AuthFlowError('SESSION_CHANGED')
      }
      currentUser.value = verifiedUser
      sessionNeedsRefresh.value = false
    } finally {
      stopLoading()
    }
  }

  async function reconcileUncertainPasswordChange(generation: number, expectedUserId: number): Promise<void> {
    let user: api.CurrentUser
    try {
      user = await api.me()
    } catch (error) {
      if (generation === authGeneration && responseStatus(error) === 401) {
        currentUser.value = null
        sessionNeedsRefresh.value = false
        throw new AuthFlowError('PASSWORD_RESULT_UNCONFIRMED_SESSION_EXPIRED')
      }
      if (generation === authGeneration) sessionNeedsRefresh.value = true
      throw new AuthFlowError('PASSWORD_RESULT_UNCONFIRMED')
    }
    if (generation !== authGeneration) throw new AuthFlowError('SESSION_CHANGED')
    if (user.userId !== expectedUserId) {
      currentUser.value = null
      throw new AuthFlowError('SESSION_CHANGED')
    }
    currentUser.value = user
    sessionNeedsRefresh.value = false
  }

  async function logout(): Promise<void> {
    const generation = ++authGeneration
    startLoading()
    try {
      await api.logout()
      clearCsrfCache()
      if (generation === authGeneration) {
        currentUser.value = null
        sessionNeedsRefresh.value = false
      }
      publishAuthStateChanged()
    } finally {
      stopLoading()
    }
  }

  async function reconcileFromAnotherTab(): Promise<void> {
    const generation = ++authGeneration
    clearCsrfCache()
    try {
      const user = await api.me()
      if (generation === authGeneration) {
        currentUser.value = user
        sessionNeedsRefresh.value = false
      }
    } catch (error) {
      if (generation !== authGeneration) return
      if (responseStatus(error) === 401) {
        currentUser.value = null
        sessionNeedsRefresh.value = false
        await redirectToLogin()
      } else {
        sessionNeedsRefresh.value = true
      }
    }
  }

  const unsubscribeAuthSignals = subscribeToAuthSignals(reason => {
    if (reason === 'expired') {
      authGeneration += 1
      currentUser.value = null
      sessionNeedsRefresh.value = false
      void redirectToLogin()
      return
    }
    void reconcileFromAnotherTab()
  })
  onScopeDispose(unsubscribeAuthSignals)

  return {
    currentUser,
    loading,
    sessionNeedsRefresh,
    authenticated,
    isAdmin,
    requiresPasswordChange,
    load,
    login,
    changePassword,
    logout,
  }
})

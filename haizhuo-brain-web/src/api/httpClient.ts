import axios, { type AxiosError } from 'axios'
import { publishUnauthorizedState } from './authSignals'
import type { AuthFailureMode } from './authPolicies'

declare module 'axios' {
  interface AxiosRequestConfig<D = any> {
    authFailureMode?: AuthFailureMode
  }

  interface InternalAxiosRequestConfig<D = any> {
    authFailureMode?: AuthFailureMode
  }
}

// CSRF token is obtained from the platform endpoint and is intentionally masked by Spring Security.
// Axios's cookie-to-header automation would replace it with the raw cookie value and cause a 403.
export const httpClient = axios.create({
  baseURL: '/',
  withCredentials: true,
  withXSRFToken: false,
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
})

let csrfHeader = ''
let csrfToken = ''
let csrfGeneration = 0
let csrfRequest: Promise<void> | null = null

export function clearCsrfCache(): void {
  csrfGeneration += 1
  csrfHeader = ''
  csrfToken = ''
  csrfRequest = null
}

export async function ensureCsrf(): Promise<void> {
  if (csrfToken) return
  if (!csrfRequest) {
    const generation = csrfGeneration
    csrfRequest = httpClient
      .get<{ headerName: string; token: string }>('/api/v1/auth/csrf')
      .then(({ data }) => {
        if (generation === csrfGeneration) {
          csrfHeader = data.headerName
          csrfToken = data.token
        }
      })
      .finally(() => {
        if (generation === csrfGeneration) csrfRequest = null
      })
  }

  const generation = csrfGeneration
  await csrfRequest
  if (!csrfToken && generation !== csrfGeneration) await ensureCsrf()
}

httpClient.interceptors.request.use(async config => {
  if (config.method && !['get', 'head', 'options'].includes(config.method.toLowerCase())) {
    await ensureCsrf()
    if (csrfHeader) config.headers.set(csrfHeader, csrfToken)
  }
  return config
})

httpClient.interceptors.response.use(
  response => response,
  async (error: AxiosError) => {
    const status = error.response?.status
    if (status === 401 && error.config?.authFailureMode !== 'local') {
      clearCsrfCache()
      publishUnauthorizedState()
    }
    return Promise.reject(error)
  },
)

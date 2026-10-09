import { httpClient } from './httpClient'
import type { AuthFailureMode } from './authPolicies'

export interface CurrentUser {
  userId: number
  mobileMasked: string
  roles: string[]
  mustChangePassword: boolean
}

export interface LoginResponse {
  userId: number
  roles: string[]
  mustChangePassword: boolean
}

export interface AuthRequestOptions {
  authFailureMode?: AuthFailureMode
}

const localAuthFailure: AuthRequestOptions = { authFailureMode: 'local' }

export async function login(mobile: string, password: string): Promise<LoginResponse> {
  return (await httpClient.post<LoginResponse>(
    '/api/v1/auth/login',
    { mobile, password },
    localAuthFailure,
  )).data
}

export async function activateAccount(activationToken: string, newPassword: string): Promise<void> {
  await httpClient.post(
    '/api/v1/auth/activate',
    { activationToken, newPassword },
    localAuthFailure,
  )
}

export async function me(options: AuthRequestOptions = localAuthFailure): Promise<CurrentUser> {
  return (await httpClient.get<CurrentUser>('/api/v1/auth/me', options)).data
}

export async function changePassword(
  currentPassword: string,
  newPassword: string,
  options: AuthRequestOptions = localAuthFailure,
): Promise<LoginResponse> {
  return (await httpClient.post<LoginResponse>(
    '/api/v1/auth/password/change',
    { currentPassword, newPassword },
    options,
  )).data
}

export async function logout(): Promise<void> {
  await httpClient.post('/api/v1/auth/logout')
}

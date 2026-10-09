export type AuthFailureMode = 'redirect' | 'local'

export const MIN_PASSWORD_LENGTH = 12
export const MAX_PASSWORD_LENGTH = 128
export const MAX_ACTIVATION_TOKEN_LENGTH = 512

export function validateActivationFields(
  activationToken: string,
  newPassword: string,
  confirmedPassword: string,
): string | null {
  if (!activationToken.trim()) return '请粘贴管理员提供的激活凭据'
  if (activationToken.length > MAX_ACTIVATION_TOKEN_LENGTH) return '激活凭据不能超过 512 个字符'
  return validateNewPasswordFields(newPassword, confirmedPassword)
}

export function validatePasswordChangeFields(
  currentPassword: string,
  newPassword: string,
  confirmedPassword: string,
): string | null {
  if (!currentPassword.trim()) return '请输入当前密码'
  if (currentPassword.length > MAX_PASSWORD_LENGTH) return '当前密码不能超过 128 个字符'
  return validateNewPasswordFields(newPassword, confirmedPassword)
}

function validateNewPasswordFields(newPassword: string, confirmedPassword: string): string | null {
  if (!newPassword.trim()) return '请输入新密码'
  if (newPassword.length < MIN_PASSWORD_LENGTH || newPassword.length > MAX_PASSWORD_LENGTH) {
    return '新密码长度必须为 12 到 128 个字符'
  }
  if (newPassword !== confirmedPassword) return '两次输入的新密码不一致'
  return null
}

export function responseStatus(error: unknown): number | undefined {
  if (typeof error !== 'object' || error === null || !('response' in error)) return undefined
  const response = (error as { response?: { status?: unknown } }).response
  return typeof response?.status === 'number' ? response.status : undefined
}

export function isUncertainMutationError(error: unknown): boolean {
  const status = responseStatus(error)
  return status === undefined || status >= 500
}

export function shouldRedirectForUnauthorized(
  status: number | undefined,
  authFailureMode: AuthFailureMode | undefined,
  routeName: string | symbol | null,
): boolean {
  return status === 401 && authFailureMode !== 'local' && routeName !== 'login'
}

export function loginDestination(mustChangePassword: boolean): 'password-change' | 'employees' {
  return mustChangePassword ? 'password-change' : 'employees'
}

export function requiredPasswordChangeRedirect(
  mustChangePassword: boolean,
  routeName: string | symbol | null,
): 'password-change' | null {
  return mustChangePassword && routeName !== 'password-change' ? 'password-change' : null
}

import { ElMessage } from 'element-plus'
import type { AxiosError } from 'axios'

/** 后端 GlobalExceptionHandler 统一返回的 ApiError 信封。 */
interface ApiErrorBody {
  code?: string
  message?: string
}

/**
 * 把后端错误翻译成可直接展示给用户的中文文案。
 * 后端的 ApiError 已经是「稳定且不泄漏凭据」的口径，前端不需要再加工，只在缺身子时兜底。
 */
export function toMessage(error: unknown, fallback = '操作失败，请稍后重试'): string {
  const axiosError = error as AxiosError<ApiErrorBody> | undefined
  if (!axiosError?.response) return '网络连接失败，请检查网络后重试'
  // 个别业务错误给出可操作的中文指引，优先于后端英文原文。
  const knownMessages: Record<string, string> = {
    'Published definition has no runtime bundle; re-publish the employee first':
      '当前员工定义发布时缺少运行时能力包，请在「管理端 → 定义编排」中重新校验并发布后再运行',
    'Session employee is no longer published': '该会话对应的员工已被下线，请新建会话',
  }
  const known = knownMessages[axiosError.response.data?.message ?? '']
  if (known) return known
  if (axiosError.response.data?.message) return axiosError.response.data.message
  switch (axiosError.response.status) {
    case 400:
      return '请求参数不正确'
    case 401:
      return '认证失败或会话已失效'
    case 403:
      return '没有执行该操作的权限'
    case 409:
      return '数据状态已变化，请刷新后重试'
    case 422:
      return '草稿校验未通过，请先处理校验问题'
    case 503:
      return '服务暂不可用'
    default:
      return fallback
  }
}

export function notifyError(error: unknown, fallback?: string): void {
  ElMessage.error(toMessage(error, fallback))
}

export function notifySuccess(text: string): void {
  ElMessage.success(text)
}

/** 4xx 是用户侧输入问题，重试没有意义；网络与其他错误才交给上层决定是否重试。 */
export function isRetryable(error: unknown): boolean {
  const status = (error as AxiosError | undefined)?.response?.status
  return status === undefined || status >= 500
}

/** 统一的本地时间展示，兼容后端 ISO-8601 字符串与缺失值。 */
export function formatTime(value?: string | null): string {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}

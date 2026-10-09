const authorizationHeader = /\b(?:proxy-)?authorization\s*:\s*(?:bearer\s+)?[^\r\n]+/gi
const credentialValue = /(["']?\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token|client[_-]?secret|authorization|password|passwd|secret|token)\b["']?\s*[:=]\s*)(?:\[[^\]]*\]|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,;}\]]+)/gi
const bearerToken = /\bbearer\s+[a-z0-9._~+/-]+=*/gi
const windowsInternalPath = /(?<![\w])(?:[A-Z]:\\)(?:[^\\/:*?"<>|\r\n]+\\)+[^\\/:*?"<>|\s,;]+/gi
const unixInternalPath = /(?<![\w])\/(?:home|Users|var|etc|workspace|app|opt|srv|tmp|mnt)\/[^\s,;]+/gi

/** 仅对界面展示内容做脱敏；调用方保留规范结果正文及其来源摘要不变。 */
export function sanitizeDisplayText(value: string | null | undefined): string {
  if (!value) return ''
  return value
    .replace(authorizationHeader, match => `${match.slice(0, match.indexOf(':') + 1)} [redacted]`)
    .replace(credentialValue, '$1[redacted]')
    .replace(bearerToken, 'Bearer [redacted]')
    .replace(windowsInternalPath, '[internal path omitted]')
    .replace(unixInternalPath, '[internal path omitted]')
}

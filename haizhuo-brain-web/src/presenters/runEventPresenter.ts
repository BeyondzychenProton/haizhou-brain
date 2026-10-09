import type { RunEvent } from '../api/app'
import type { RunStreamEvent } from '../api/runStream'
import type { CanonicalRunResult } from '../composables/runResultLoader'

export type ConversationPhase = 'queued' | 'generating' | 'waiting-tool' | 'waiting-confirmation'
  | 'cancelling' | 'recovery-pending' | 'finalizing' | 'final' | 'partial-stopped'
export type ConversationBodySource = 'draft' | 'event-summary' | 'canonical-result' | 'legacy-summary'

export type ConversationItem = {
  key: string
  role: 'user' | 'assistant' | 'system'
  text: string
  eventType: string
  sequenceNo: number
  pending?: boolean
  runId?: string
  sessionId?: string
  executorRoleId?: string
  phase?: ConversationPhase
  bodySource?: ConversationBodySource
  mediaType?: string
  resultId?: string | null
  bodySha256?: string
  resultLoadState?: 'idle' | 'loading' | 'loaded' | 'failed'
  statusMessage?: string
  terminal?: boolean
}

type PresentableEvent = (RunEvent | RunStreamEvent) & { executorRoleId?: string }

function sequenceOf(event: PresentableEvent): number {
  return 'sequenceNo' in event ? event.sequenceNo : (event.runSequence ?? 0)
}

function contentOf(event: PresentableEvent): string {
  return 'content' in event ? event.content : (event.payload.delta ?? event.payload.text ?? '')
}

function sessionIdOf(event: PresentableEvent): string | undefined {
  return 'sessionId' in event ? event.sessionId : undefined
}

function resultIdOf(event: PresentableEvent): string | null | undefined {
  return 'resultId' in event ? event.resultId : undefined
}

function rootAssistantKey(runId: string): string {
  // ROOT_FINAL 在 v2/v3 中共用正式结果身份；原生回复和内容块 ID 仅用于草稿。
  return `${runId}-assistant`
}

function userMessageKey(event: PresentableEvent, sequenceNo: number): string {
  if ('payload' in event && event.payload.messageId) return event.payload.messageId
  return `${event.runId}-user-${sequenceNo}`
}

const RUN_STATUS: Record<string, { phase: ConversationPhase; message: string; terminal?: boolean }> = {
  QUEUED: { phase: 'queued', message: '正在排队' },
  RUNNING: { phase: 'generating', message: '正在生成' },
  WAITING_TOOL: { phase: 'waiting-tool', message: '等待工具执行' },
  WAITING_CONFIRMATION: { phase: 'waiting-confirmation', message: '等待人工确认' },
  CANCELLING: { phase: 'cancelling', message: '取消请求已受理，运行仍在检查点收尾' },
  RECOVERY_REQUIRED: { phase: 'recovery-pending', message: '运行结果待管理员核查', terminal: true },
  SUCCEEDED: { phase: 'finalizing', message: '运行已完成，正在读取正式结果', terminal: true },
  FAILED: { phase: 'partial-stopped', message: '运行失败，以上内容可能不完整', terminal: true },
  CANCELLED: { phase: 'partial-stopped', message: '运行已取消，以上内容未完成', terminal: true },
  EXPIRED: { phase: 'partial-stopped', message: '运行已过期，以上内容未完成', terminal: true },
  TERMINATED: { phase: 'partial-stopped', message: '运行已结束核查，以上内容未完成', terminal: true },
}

function appendStatus(items: ConversationItem[], runId: string, sequenceNo: number, eventType: string,
                      text: string, sessionId?: string): ConversationItem[] {
  const key = `${runId}-${sequenceNo}-${eventType}`
  if (items.some(item => item.key === key)) return items
  return [...items, { key, runId, sessionId, role: 'system', text, eventType, sequenceNo }]
}

/** 合并用户可见事件，并阻止迟到增量或事件摘要覆盖已加载的正式结果。 */
export function mergeConversationEvent(items: ConversationItem[], event: PresentableEvent): ConversationItem[] {
  const sequenceNo = sequenceOf(event)
  const content = contentOf(event)
  const sessionId = sessionIdOf(event)

  if (event.type === 'USER_INPUT') {
    const key = userMessageKey(event, sequenceNo)
    if (items.some(item => item.key === key)) return items
    return [...items, { key, runId: event.runId, sessionId, role: 'user', text: content,
      eventType: event.type, sequenceNo, executorRoleId: event.executorRoleId }]
  }

  if (event.type === 'message.text.delta' || event.type === 'MODEL_DELTA') {
    if (!content) return items
    const key = rootAssistantKey(event.runId)
    const index = items.findIndex(item => item.key === key)
    if (index < 0) {
      return [...items, { key, runId: event.runId, sessionId, role: 'assistant', text: content,
        eventType: event.type, sequenceNo, pending: true, phase: 'generating', bodySource: 'draft',
        executorRoleId: event.executorRoleId }]
    }
    const previous = items[index]
    if (previous.terminal || previous.bodySource === 'canonical-result' || previous.bodySource === 'legacy-summary') return items
    const cancelling = previous.phase === 'cancelling'
    const next = items.slice()
    next[index] = { ...previous, text: previous.text + content, eventType: event.type, sequenceNo,
      pending: !cancelling, phase: cancelling ? 'cancelling' : 'generating', bodySource: 'draft',
      statusMessage: cancelling ? previous.statusMessage : undefined,
      executorRoleId: event.executorRoleId ?? previous.executorRoleId }
    return next
  }

  if (event.type === 'RUN_COMPLETED' || event.type === 'message.final' || event.type === 'AGENT_MESSAGE') {
    const key = rootAssistantKey(event.runId)
    const index = items.findIndex(item => item.key === key)
    const previous = index < 0 ? undefined : items[index]
    if (previous?.bodySource === 'canonical-result' || previous?.bodySource === 'legacy-summary') return items
    const finalizing: ConversationItem = {
      ...previous,
      key,
      runId: event.runId,
      sessionId: sessionId ?? previous?.sessionId,
      role: 'assistant',
      // 正式结果读取完成前，保留内容更长的实时草稿。
      text: previous?.text || content,
      eventType: event.type,
      sequenceNo,
      pending: false,
      phase: 'finalizing',
      bodySource: previous?.text ? 'draft' : (content ? 'event-summary' : 'draft'),
      resultId: resultIdOf(event) ?? previous?.resultId,
      resultLoadState: 'loading',
      statusMessage: '运行已完成，正在校准正式结果',
      terminal: true,
      executorRoleId: event.executorRoleId ?? previous?.executorRoleId,
    }
    if (index < 0) return [...items, finalizing]
    const next = items.slice()
    next[index] = finalizing
    return next
  }

  const eventState: Record<string, string> = {
    RUN_STARTED: 'RUNNING',
    RUN_QUEUED: 'QUEUED',
    RUN_WAITING_TOOL: 'WAITING_TOOL',
    RUN_WAITING_CONFIRMATION: 'WAITING_CONFIRMATION',
    RUN_CANCEL_REQUESTED: 'CANCELLING',
    RUN_RECOVERY_REQUIRED: 'RECOVERY_REQUIRED',
    RUN_FAILED: 'FAILED',
    RUN_CANCELLED: 'CANCELLED',
    RUN_EXPIRED: 'EXPIRED',
    RUN_TERMINATED: 'TERMINATED',
  }
  const state = eventState[event.type]
  if (state) {
    const status = RUN_STATUS[state]
    const key = rootAssistantKey(event.runId)
    const index = items.findIndex(item => item.key === key)
    if (index < 0) return [...items, {
      key, runId: event.runId, sessionId, role: 'assistant', text: '', eventType: event.type,
      sequenceNo, pending: state === 'RUNNING', phase: status.phase, bodySource: 'draft',
      statusMessage: content || status.message, terminal: status.terminal,
      executorRoleId: event.executorRoleId,
    }]
    const previous = items[index]
    if (previous.bodySource === 'canonical-result' || previous.bodySource === 'legacy-summary') return items
    if (previous.terminal && !status.terminal) return items
    const next = items.slice()
    next[index] = { ...previous, sequenceNo, eventType: event.type, pending: state === 'RUNNING',
      phase: status.phase, statusMessage: content || status.message, terminal: status.terminal ?? previous.terminal }
    return next
  }

  if (event.type === 'PLAN_SNAPSHOT') {
    const key = `${event.runId}-plan`
    const item: ConversationItem = { key, runId: event.runId, sessionId, role: 'system', text: content,
      eventType: event.type, sequenceNo }
    const index = items.findIndex(candidate => candidate.key === key)
    if (index < 0) return [...items, item]
    const next = items.slice()
    next[index] = item
    return next
  }

  if (['RUN_GUIDANCE_RECEIVED', 'RUN_GUIDANCE_CONSUMED'].includes(event.type)) {
    return appendStatus(items, event.runId, sequenceNo, event.type, content, sessionId)
  }
  return items
}

export function applyRunState(items: ConversationItem[], runId: string, state: string): ConversationItem[] {
  const status = RUN_STATUS[state]
  if (!status) return items
  const key = rootAssistantKey(runId)
  const index = items.findIndex(item => item.key === key)
  if (index < 0) return status.terminal && state !== 'SUCCEEDED'
    ? appendStatus(items, runId, 0, `RUN_${state}`, status.message)
    : items
  const previous = items[index]
  if (previous.bodySource === 'canonical-result' || previous.bodySource === 'legacy-summary') return items
  // 过期轮询不得重新打开终止消息，也不得降低已完成的正式结果读取状态。
  if (previous.terminal && !status.terminal) return items
  const next = items.slice()
  next[index] = { ...previous, pending: state === 'RUNNING', phase: status.phase,
    statusMessage: status.message, terminal: status.terminal ?? previous.terminal }
  return next
}

export function applyCanonicalResult(items: ConversationItem[], result: CanonicalRunResult): ConversationItem[] {
  const key = rootAssistantKey(result.runId)
  const index = items.findIndex(item => item.key === key)
  const previous = index < 0 ? undefined : items[index]
  const canonical: ConversationItem = {
    ...previous,
    key,
    runId: result.runId,
    role: 'assistant',
    text: result.body,
    eventType: previous?.eventType ?? 'RUN_COMPLETED',
    sequenceNo: previous?.sequenceNo ?? 0,
    pending: false,
    phase: 'final',
    bodySource: result.legacySummary ? 'legacy-summary' : 'canonical-result',
    mediaType: result.mediaType,
    resultId: result.resultId,
    bodySha256: result.bodySha256,
    resultLoadState: 'loaded',
    statusMessage: result.legacySummary ? '历史摘要：未保存的正文无法恢复' : undefined,
    terminal: true,
    executorRoleId: result.executorRoleId ?? previous?.executorRoleId,
  }
  if (index < 0) return [...items, canonical]
  const next = items.slice()
  next[index] = canonical
  return next
}

export function markResultLoadFailed(items: ConversationItem[], runId: string): ConversationItem[] {
  const key = rootAssistantKey(runId)
  const index = items.findIndex(item => item.key === key)
  if (index < 0) return appendStatus(items, runId, 0, 'RESULT_LOAD_FAILED',
    '运行已完成，完整结果读取失败；可重试读取，不会重新执行运行')
  const previous = items[index]
  if (previous.bodySource === 'canonical-result' || previous.bodySource === 'legacy-summary') return items
  const next = items.slice()
  next[index] = { ...previous, pending: false, phase: 'finalizing', resultLoadState: 'failed', terminal: true,
    statusMessage: '运行已完成，完整结果读取失败；可重试读取，不会重新执行运行' }
  return next
}

export function present(event: RunEvent): ConversationItem | null {
  const merged = mergeConversationEvent([], event)
  return merged[0] ?? null
}

export function presentTimeline(events: RunEvent[]): ConversationItem[] {
  return events.reduce<ConversationItem[]>((items, event) => mergeConversationEvent(items, event), [])
}

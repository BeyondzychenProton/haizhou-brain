import type { RunEvent } from '../api/app'
import type { RunStreamEvent } from '../api/runStream'

export type ConversationItem = {
  key: string
  role: 'user' | 'assistant' | 'system'
  text: string
  eventType: string
  sequenceNo: number
  pending?: boolean
}

type PresentableEvent = RunEvent | RunStreamEvent

function sequenceOf(event: PresentableEvent): number {
  return 'sequenceNo' in event ? event.sequenceNo : (event.runSequence ?? 0)
}

function contentOf(event: PresentableEvent): string {
  return 'content' in event ? event.content : (event.payload.delta ?? event.payload.text ?? '')
}

function messageKeyOf(event: PresentableEvent, fallback: string): string {
  if ('payload' in event && event.payload.messageId) return event.payload.messageId
  return fallback
}

/**
 * 按消息身份合并实时增量；持久最终答案会整体替换瞬时文本，从而在丢 delta 或重连后校准。
 */
export function mergeConversationEvent(items: ConversationItem[], event: PresentableEvent): ConversationItem[] {
  const sequenceNo = sequenceOf(event)
  const content = contentOf(event)

  if (event.type === 'USER_INPUT') {
    const key = messageKeyOf(event, `${event.runId}-user-${sequenceNo}`)
    if (items.some(item => item.key === key)) return items
    return [...items, { key, role: 'user', text: content, eventType: event.type, sequenceNo }]
  }

  if (event.type === 'message.text.delta' || event.type === 'MODEL_DELTA') {
    if (!content) return items
    const key = messageKeyOf(event, `${event.runId}-assistant`)
    const index = items.findIndex(item => item.key === key)
    if (index < 0) {
      return [...items, { key, role: 'assistant', text: content, eventType: event.type, sequenceNo, pending: true }]
    }
    if (items[index].pending === false) return items
    const next = items.slice()
    next[index] = { ...next[index], text: next[index].text + content, eventType: event.type, pending: true }
    return next
  }

  if (event.type === 'RUN_COMPLETED' || event.type === 'message.final' || event.type === 'AGENT_MESSAGE') {
    const key = messageKeyOf(event, `${event.runId}-assistant`)
    const finalItem: ConversationItem = {
      key,
      role: 'assistant',
      text: content,
      eventType: event.type,
      sequenceNo,
      pending: false,
    }
    const index = items.findIndex(item => item.key === key)
    if (index < 0) return [...items, finalItem]
    const next = items.slice()
    next[index] = finalItem
    return next
  }

  if (['RUN_FAILED', 'RUN_CANCEL_REQUESTED', 'RUN_CANCELLED', 'RUN_GUIDANCE_RECEIVED',
    'RUN_GUIDANCE_CONSUMED'].includes(event.type)) {
    const key = `${event.runId}-${sequenceNo}-${event.type}`
    if (items.some(item => item.key === key)) return items
    return [...items, { key, role: 'system', text: content, eventType: event.type, sequenceNo }]
  }

  return items
}

export function present(event: RunEvent): ConversationItem | null {
  const merged = mergeConversationEvent([], event)
  return merged[0] ?? null
}

export function presentTimeline(events: RunEvent[]): ConversationItem[] {
  return events.reduce<ConversationItem[]>((items, event) => mergeConversationEvent(items, event), [])
}

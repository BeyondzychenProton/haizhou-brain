import type { ContentBlock } from '../renderers/contentBlockRegistry'

export interface StreamInteractionOption {
  id: string
  label: string
  description?: string
}

export interface StreamInteractionRequest {
  interactionId: string
  interactionType: 'TOOL_APPROVAL' | 'USER_SELECTION' | string
  title: string
  message?: string
  options: StreamInteractionOption[]
  status: 'PENDING' | 'SUBMITTED' | 'APPLIED' | 'REJECTED' | 'EXPIRED' | string
  expiresAt?: string
}

export interface RunStreamPayload {
  messageId: string | null
  blockId: string | null
  delta: string | null
  text: string | null
  /** 可扩展内容块/交互数据；旧事件没有该字段时按空对象处理。 */
  metadata?: Record<string, unknown> & {
    interaction?: StreamInteractionRequest
    contentBlocks?: ContentBlock[]
  }
}

export interface RunStreamEvent {
  schemaVersion: number
  eventId: string
  sessionId: string
  sessionCursor: number | null
  runId: string
  attemptId: string | null
  runSequence: number | null
  streamOffset: number | null
  type: string
  visibility: string
  durability: 'durable' | 'transient'
  occurredAt: string
  payload: RunStreamPayload
  origin?: { kind: string } | null
  resultId?: string | null
}

export interface RunStreamCallbacks {
  onOpen: () => void
  onEvent: (event: RunStreamEvent) => void
  onDisconnect: () => void
  onMalformedEvent?: (raw: string) => void
}

/**
 * 打开同源 Cookie 鉴权的会话级 SSE（P2）：跨 Run 保持同一条连接，SSE id 是持久
 * sessionCursor，补读与退避重连由调用方负责，因此这里主动关闭浏览器的隐式无限重试，
 * 避免无权限资源产生重连风暴。运行级端点仍保留在后端，供单 Run 诊断使用。
 */
export function openSessionStream(sessionId: string, after: number, callbacks: RunStreamCallbacks): () => void {
  const url = `/api/v1/sessions/${encodeURIComponent(sessionId)}/stream?after=${Math.max(after, 0)}&format=v2`
  const source = new EventSource(url, { withCredentials: true })
  let closed = false

  source.onopen = () => callbacks.onOpen()
  source.onmessage = message => {
    try {
      callbacks.onEvent(JSON.parse(message.data) as RunStreamEvent)
    } catch {
      callbacks.onMalformedEvent?.(message.data)
    }
  }
  source.onerror = () => {
    if (closed) return
    source.close()
    callbacks.onDisconnect()
  }

  return () => {
    closed = true
    source.close()
  }
}

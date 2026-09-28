export interface RunStreamPayload {
  messageId: string | null
  blockId: string | null
  delta: string | null
  text: string | null
}

export interface RunStreamEvent {
  schemaVersion: number
  eventId: string
  runId: string
  attemptId: string | null
  runSequence: number | null
  streamOffset: number | null
  type: string
  durability: 'durable' | 'transient'
  occurredAt: string
  payload: RunStreamPayload
}

export interface RunStreamCallbacks {
  onOpen: () => void
  onEvent: (event: RunStreamEvent) => void
  onDisconnect: () => void
  onMalformedEvent?: (raw: string) => void
}

/**
 * 打开同源 Cookie 鉴权的 Run SSE。断线后由调用方按持久 runSequence 补读并退避重连，
 * 因此这里主动关闭浏览器的隐式无限重试，避免无权限资源产生重连风暴。
 */
export function openRunStream(runId: string, after: number, callbacks: RunStreamCallbacks): () => void {
  const url = `/api/v1/sessions/runs/${encodeURIComponent(runId)}/stream?after=${Math.max(after, 0)}`
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

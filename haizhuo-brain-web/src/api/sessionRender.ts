import { httpClient } from './httpClient'
import type { RenderEvent, RenderView } from '../presenters/sessionRenderPresenter'

export interface SessionRenderStreamCallbacks {
  onOpen: () => void
  onEvent: (event: RenderEvent) => void
  onDisconnect: () => void
  onMalformedEvent?: (raw: string) => void
}

export async function readSessionRenderView(sessionId: string, cursor?: string): Promise<RenderView> {
  const { data } = await httpClient.get<RenderView>(
    '/api/v1/sessions/' + encodeURIComponent(sessionId) + '/view',
    { params: cursor ? { cursor } : undefined },
  )
  return data
}

/** 此传输层仅读取会话展示投影，不会提交或恢复 Run。 */
export function openSessionRenderStream(
  sessionId: string,
  after: number,
  renderAfter: number,
  callbacks: SessionRenderStreamCallbacks,
): () => void {
  const url = '/api/v1/sessions/' + encodeURIComponent(sessionId) + '/render-events?after='
    + Math.max(0, after) + '&renderAfter=' + Math.max(0, renderAfter)
  const source = new EventSource(url, { withCredentials: true })
  let closed = false
  source.onopen = () => callbacks.onOpen()
  source.onmessage = message => {
    try { callbacks.onEvent(JSON.parse(message.data) as RenderEvent) }
    catch { callbacks.onMalformedEvent?.(message.data) }
  }
  source.onerror = () => {
    if (closed) return
    closed = true
    source.close()
    callbacks.onDisconnect()
  }
  return () => {
    closed = true
    source.close()
  }
}

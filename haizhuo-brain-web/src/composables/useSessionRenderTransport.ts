import { computed, onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'
import { readSessionRenderView, openSessionRenderStream } from '../api/sessionRender'
import {
  applyRenderEvent,
  projectionFromView,
  type RenderProjection,
} from '../presenters/sessionRenderPresenter'

const enabled = import.meta.env.VITE_SESSION_RENDER_V3 === 'true'
const RECONNECT_DELAYS = [1000, 2000, 4000, 8000, 10000]

export function useSessionRenderTransport(sessionId: Ref<string>) {
  const projection = shallowRef<RenderProjection | null>(null)
  const loading = ref(false)
  const error = ref<unknown>(null)
  const state = ref<'disabled' | 'idle' | 'loading' | 'connecting' | 'live' | 'interrupted' | 'forbidden' | 'unavailable'>(
    enabled ? 'idle' : 'disabled',
  )
  const hasMore = ref(false)
  const olderCursor = ref<string | null>(null)
  const canLoadOlder = computed(() => enabled && hasMore.value && Boolean(olderCursor.value))
  let generation = 0
  let closeStream: (() => void) | undefined
  let reconnectTimer: number | undefined
  let reconnectAttempt = 0

  function clearTimers() {
    if (reconnectTimer !== undefined) window.clearTimeout(reconnectTimer)
    reconnectTimer = undefined
  }

  function close() {
    generation++
    clearTimers()
    closeStream?.()
    closeStream = undefined
    if (enabled) state.value = 'idle'
  }

  function clearProjection() {
    projection.value = null
    hasMore.value = false
    olderCursor.value = null
    error.value = null
  }

  async function loadView(expectedGeneration = generation, expectedSession = sessionId.value) {
    if (!enabled || !expectedSession) return false
    loading.value = true
    state.value = 'loading'
    try {
      const view = await readSessionRenderView(expectedSession)
      if (expectedGeneration !== generation || expectedSession !== sessionId.value) return false
      projection.value = projectionFromView(view, projection.value ?? undefined)
      hasMore.value = view.hasMore
      olderCursor.value = view.nextCursor
      error.value = null
      return true
    } catch (cause) {
      if (expectedGeneration === generation && expectedSession === sessionId.value) {
        error.value = cause
        const statusCode = statusOf(cause)
        if (statusCode === 401 || statusCode === 403) {
          state.value = 'forbidden'
          clearProjection()
        } else if (statusCode === 404) {
          state.value = 'unavailable'
          clearProjection()
        } else state.value = 'interrupted'
      }
      return false
    } finally {
      if (expectedGeneration === generation && expectedSession === sessionId.value) loading.value = false
    }
  }

  function connect(expectedGeneration = generation, expectedSession = sessionId.value) {
    if (!enabled || !expectedSession || expectedGeneration !== generation
        || state.value === 'forbidden' || state.value === 'unavailable') return
    const current = projection.value
    if (!current) return
    state.value = 'connecting'
    closeStream?.()
    closeStream = openSessionRenderStream(expectedSession, current.sessionCursor, current.renderCursor, {
      onOpen: () => {
        if (expectedGeneration !== generation) return
        state.value = 'live'
        reconnectAttempt = 0
      },
      onEvent: event => {
        if (expectedGeneration !== generation || event.sessionId !== expectedSession || !projection.value) return
        const next = applyRenderEvent(projection.value, event)
        projection.value = next
        if (next.reloadRequired) void resnapshot(expectedGeneration, expectedSession)
      },
      onDisconnect: () => { void recover(expectedGeneration, expectedSession) },
      onMalformedEvent: raw => console.warn('Ignored malformed session render event', raw),
    })
  }

  async function resnapshot(expectedGeneration: number, expectedSession: string) {
    if (expectedGeneration !== generation) return
    closeStream?.()
    closeStream = undefined
    if (await loadView(expectedGeneration, expectedSession)) connect(expectedGeneration, expectedSession)
  }

  async function recover(expectedGeneration: number, expectedSession: string) {
    if (expectedGeneration !== generation) return
    closeStream = undefined
    state.value = 'interrupted'
    // EventSource 不暴露 HTTP 状态码，因此由只读接口区分身份失效和网络中断。
    const loaded = await loadView(expectedGeneration, expectedSession)
    if (expectedGeneration !== generation) return
    const statusCode = statusOf(error.value)
    if (!loaded && (statusCode === 401 || statusCode === 403 || statusCode === 404)) return
    const delay = RECONNECT_DELAYS[Math.min(reconnectAttempt, RECONNECT_DELAYS.length - 1)]
    reconnectAttempt++
    clearTimers()
    reconnectTimer = window.setTimeout(() => {
      reconnectTimer = undefined
      if (expectedGeneration === generation) connect(expectedGeneration, expectedSession)
    }, delay)
  }

  async function loadOlder() {
    const cursor = olderCursor.value
    const current = projection.value
    const requestedSession = sessionId.value
    const requestedGeneration = generation
    if (!enabled || !cursor || !current || loading.value) return false
    loading.value = true
    try {
      const view = await readSessionRenderView(requestedSession, cursor)
      if (requestedGeneration !== generation || requestedSession !== sessionId.value || !projection.value) return false
      const byId = new Map<string, RenderProjection['items'][number]>()
      for (const item of view.items) byId.set(item.messageId, item)
      for (const item of projection.value.items) byId.set(item.messageId, item)
      projection.value = { ...projection.value, items: [...byId.values()] }
      hasMore.value = view.hasMore
      olderCursor.value = view.nextCursor
      return true
    } catch (cause) {
      if (requestedGeneration === generation) error.value = cause
      return false
    } finally {
      if (requestedGeneration === generation) loading.value = false
    }
  }

  async function start() {
    close()
    if (!enabled) return
    const currentGeneration = generation
    const currentSession = sessionId.value
    clearProjection()
    if (await loadView(currentGeneration, currentSession)) connect(currentGeneration, currentSession)
  }

  watch(sessionId, () => {
    close()
    clearProjection()
    if (enabled) void start()
  })

  onScopeDispose(close)

  return { enabled, projection, loading, error, state, canLoadOlder, loadOlder, start, close }
}

function statusOf(error: unknown): number | undefined {
  return (error as { response?: { status?: number } } | null)?.response?.status
}

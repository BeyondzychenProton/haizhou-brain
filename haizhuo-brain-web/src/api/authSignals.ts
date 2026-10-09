export type AuthSignalReason = 'changed' | 'expired' | 'focus'
type AuthSignalListener = (reason: AuthSignalReason) => void

const channelName = 'haizhuo-auth-state'
const signalName = 'auth-state-changed'
const listeners = new Set<AuthSignalListener>()
const channel: BroadcastChannel | undefined =
  typeof window !== 'undefined' && typeof BroadcastChannel !== 'undefined'
    ? new BroadcastChannel(channelName)
    : undefined

function notify(reason: AuthSignalReason): void {
  for (const listener of listeners) listener(reason)
}

if (channel) {
  channel.addEventListener('message', event => {
    if (event.data === signalName) notify('changed')
  })
} else if (typeof window !== 'undefined') {
  window.addEventListener('focus', () => notify('focus'))
}

export function subscribeToAuthSignals(listener: AuthSignalListener): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function publishAuthStateChanged(): void {
  channel?.postMessage(signalName)
}

export function publishUnauthorizedState(): void {
  notify('expired')
  channel?.postMessage(signalName)
}

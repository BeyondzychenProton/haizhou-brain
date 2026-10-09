import { httpClient } from './httpClient'

export interface OperationsOverview {
  observedAt: string
  instanceLabel: string | null
  runtime: {
    agentScopeVersion: string
    loaded: boolean
    profileGates: Record<string, boolean>
  }
  workers: Array<{
    kind: 'RUN' | 'CHANNEL_DELIVERY'
    configuredEnabled: boolean
    loaded: boolean
    safeInstanceLabel: string | null
    leaseTtlSeconds: number | null
    reclaimEveryTicks: number | null
    lastObservedAt: string | null
  }>
  observability: {
    configuredEnabled: boolean
    loaded: boolean
    captureContentEnabled: boolean
    lastProbeAt: string | null
    probeStatus: 'HEALTHY' | 'UNAVAILABLE' | 'UNKNOWN' | 'NOT_CONFIGURED'
    safeErrorCode: string | null
    scoreQueueDepth: number | null
  }
  links: Array<{ kind: string; label: string; url: string }>
}

export function operationsOverview(signal?: AbortSignal) {
  return httpClient.get<OperationsOverview>('/api/admin/v1/operations/overview', { signal }).then(r => r.data)
}

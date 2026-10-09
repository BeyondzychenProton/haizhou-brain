import { httpClient } from './httpClient'

export type RuntimeGateState = 'DESIGNED' | 'IMPLEMENTED_CLOSED' | 'VALIDATED' | 'ENABLED' | 'SUSPENDED'

export interface RuntimeGateStatus {
  gateId: string
  state: RuntimeGateState
  configured: boolean
  verificationMatched: boolean
  enabled: boolean
  reasonCode: string
  verificationReasonCode: string
  requirements: string[]
  checkedAt: string
}

export interface RuntimeReadiness {
  schemaVersion: number
  items: RuntimeGateStatus[]
  nextCursor: string | null
  hasMore: boolean
}

export function runtimeReadiness(signal?: AbortSignal) {
  return httpClient.get<RuntimeReadiness>('/api/admin/v1/runtime/readiness', { signal }).then(r => r.data)
}

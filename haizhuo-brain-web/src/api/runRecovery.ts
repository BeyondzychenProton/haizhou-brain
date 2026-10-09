import { httpClient } from './httpClient'

export type RecoveryRunState = 'RECOVERY_REQUIRED' | 'TERMINATED'

export interface RecoveryRunSummary {
  runId: string
  sessionId: string
  ownerUserId: number
  employeeId: number
  definitionVersionId: number
  runtimeProfile: string
  state: RecoveryRunState
  failureCode: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  recoveryRequiredAt: string | null
  recoveryRequiredAtStatus: 'KNOWN' | 'UNKNOWN'
}

export interface RecoveryRunAttempt {
  attemptId: string
  attemptNo: number
  workerLabel: string
  fenceToken: number
  state: string
  heartbeatAt: string
  leaseExpiresAt: string
  startedAt: string
  finishedAt: string | null
}

export interface RecoveryRunEligibility {
  allowed: boolean
  reasonCode: 'ELIGIBLE' | 'NOT_RECOVERY_REQUIRED' | 'LEGACY_PROFILE' | 'ATTEMPT_MISSING' | 'FENCE_UNAVAILABLE'
  expectedFenceToken: number | null
}

export interface RecoveryPublicEvent {
  eventType: string
  occurredAt: string
}

export interface RecoveryAction {
  requestId: string
  actorUserId: number
  expectedFenceToken: number
  reason: string
  stopEvidenceReferenceLabel: string
  createdAt: string
}

export interface RecoverySessionRunFact {
  runId: string
  state: string
  createdAt: string
  queuePosition: number
}

export interface RecoverySessionActivity {
  slotOccupied: boolean
  activeOrQueuedRuns: RecoverySessionRunFact[]
  observedAt: string
}

export interface RecoveryRunDetail extends RecoveryRunSummary {
  latestAttempt: RecoveryRunAttempt | null
  terminationEligibility: RecoveryRunEligibility
  publicEventSummary: RecoveryPublicEvent[]
  recoveryActions: RecoveryAction[]
  sessionActivity: RecoverySessionActivity
  observedAt: string
}

export interface RecoveryRunPage {
  items: RecoveryRunSummary[]
  nextCursor: string | null
  hasMore: boolean
}

export interface RecoveryRunQuery {
  state: RecoveryRunState
  userId?: number
  employeeId?: number
  createdFrom?: string
  createdTo?: string
  cursor?: string
  limit?: number
}

export interface RecoveryTerminationRequest {
  expectedFenceToken: number
  requestId: string
  stopEvidenceReference: string
  reason: string
}

export interface RecoveryTerminationResponse {
  runId: string
  state: 'TERMINATED'
  finishedAt: string
  requestId: string
}

export function listRecoveryRuns(query: RecoveryRunQuery) {
  return httpClient.get<RecoveryRunPage>('/api/admin/v1/runs/recovery', { params: query }).then(r => r.data)
}

export function getRecoveryRun(runId: string) {
  return httpClient.get<RecoveryRunDetail>(`/api/admin/v1/runs/${encodeURIComponent(runId)}/recovery`)
    .then(r => r.data)
}

export function terminateRecoveryRun(runId: string, request: RecoveryTerminationRequest) {
  return httpClient.post<RecoveryTerminationResponse>(
    `/api/admin/v1/runs/${encodeURIComponent(runId)}/recovery/terminate`, request
  ).then(r => r.data)
}

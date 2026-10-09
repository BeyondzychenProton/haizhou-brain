import { httpClient } from './httpClient'

export type ChannelSessionScope = 'MAIN' | 'PER_PEER' | 'PER_CHANNEL_PEER' | 'PER_ACCOUNT_CHANNEL_PEER'

export interface ChannelAccount {
  bindingId: string
  tenantId: number
  provider: string
  externalAccountKey: string
  credentialRef: string
  defaultEmployeeId: number
  sessionScope: ChannelSessionScope
  enabled: boolean
  revision: number
  runtimeRefresh: ChannelRuntimeRefresh | null
}

export interface ChannelRuntimeRefresh {
  status: 'APPLIED' | 'PENDING' | 'FAILED' | string
  requestedRevision: number
  loadedRevision: number | null
  instanceLabel: string
  observedAt: string
  safeErrorCode: string | null
}

export interface ChannelIdentity {
  bindingId: string
  externalUserId: string
  userId: number
  state: 'LINKED' | 'REVOKED' | string
  linkedAt: string
  updatedAt: string
}

export interface RuntimeChannel {
  channelId: string
  defaultAgentId: string
  sessionScope: string
  bindingCount: number
  started: boolean
  instanceLabel: string
  observedAt: string | null
  accountSnapshots: Array<{
    bindingId: string
    configuredRevision: number
    loadedRevision: number | null
    loadState: 'LOADED' | 'STALE' | 'UNKNOWN' | 'UNLOADED' | string
  }>
}

export interface ChannelEmployeeOption {
  employeeId: number
  employeeCode: string
  displayName: string
  enabled: boolean
  published: boolean
}

export interface DeliverySummary {
  deliveryId: string
  runId: string
  sessionId: string | null
  bindingId: string
  provider: string
  state: 'PENDING' | 'SENDING' | 'RETRYABLE_FAILURE' | 'PERMANENT_FAILURE' | 'DELIVERED' | 'UNCERTAIN' | string
  attempts: number
  resultId: string | null
  contentPreview: string
  externalMessageId: string | null
  lastErrorCode: string | null
  createdAt: string
  updatedAt: string
  sendingExpiresAt: string | null
  evidenceSource: 'SIMULATED' | 'UNVERIFIED' | string
  revision: number
}

export interface DeliveryPage {
  items: DeliverySummary[]
  nextCursor: string | null
  hasMore: boolean
}

export interface DeliveryDetail {
  summary: DeliverySummary
  runState: string | null
  attemptsHistory: Array<{
    attemptNo: number
    claimGeneration: number
    startedAt: string
    finishedAt: string | null
    status: string
    safeErrorCode: string | null
  }>
  attemptsHistoryState: 'LEGACY_UNRECORDED' | 'PARTIAL' | 'RECORDED' | string
  verifications: Array<{ decision: string; occurredAt: string; evidenceReferenceLabel: string }>
}

export interface DeliveryQuery {
  state?: string
  bindingId?: string
  provider?: string
  runId?: string
  createdFrom?: string
  createdTo?: string
  cursor?: string
  limit?: number
}

export function listChannelAccounts() {
  return httpClient.get<ChannelAccount[]>('/api/admin/v1/channels/accounts').then(response => response.data)
}

export function createChannelAccount(input: Omit<ChannelAccount, 'tenantId' | 'revision' | 'runtimeRefresh'> & {
  tenantId: number
  requestId: string
  reason: string
}) {
  return httpClient.post<ChannelAccount>('/api/admin/v1/channels/accounts', input).then(response => response.data)
}

export function updateChannelAccount(bindingId: string, input: Partial<Pick<ChannelAccount,
  'enabled' | 'defaultEmployeeId' | 'sessionScope'>> & {
    expectedRevision: number
    requestId: string
    reason: string
  }) {
  return httpClient.patch<ChannelAccount>(`/api/admin/v1/channels/accounts/${encodeURIComponent(bindingId)}`, input)
    .then(response => response.data)
}

export function listChannelIdentities(bindingId: string) {
  return httpClient.get<ChannelIdentity[]>(
    `/api/admin/v1/channels/accounts/${encodeURIComponent(bindingId)}/identities`,
  ).then(response => response.data)
}

export function linkChannelIdentity(bindingId: string, externalUserId: string, userId: number,
                                    metadata: { requestId: string; reason: string }) {
  return httpClient.put<ChannelIdentity>(
    `/api/admin/v1/channels/accounts/${encodeURIComponent(bindingId)}/identities/${encodeURIComponent(externalUserId)}`,
    { userId, ...metadata },
  ).then(response => response.data)
}

export function revokeChannelIdentity(bindingId: string, externalUserId: string,
                                      metadata: { requestId: string; reason: string }) {
  return httpClient.post<{ bindingId: string; externalUserId: string; state: string }>(
    `/api/admin/v1/channels/accounts/${encodeURIComponent(bindingId)}/identities/${encodeURIComponent(externalUserId)}/revoke`,
    metadata,
  ).then(response => response.data)
}

export function listRuntimeChannels() {
  return httpClient.get<RuntimeChannel[]>('/api/admin/v1/channels/runtime').then(response => response.data)
}

export async function listPublishedChannelEmployees() {
  const employees: ChannelEmployeeOption[] = []
  let cursor: string | undefined
  for (let pageNo = 0; pageNo < 100; pageNo++) {
    const page = await httpClient.get<{
      items: ChannelEmployeeOption[]
      nextCursor: string | null
      hasMore: boolean
    }>('/api/admin/v1/agents', { params: { enabled: true, published: true, limit: 100, cursor } })
      .then(response => response.data)
    employees.push(...page.items)
    if (!page.hasMore || !page.nextCursor) return employees
    cursor = page.nextCursor
  }
  throw new Error('Published employee directory exceeded the pagination safety bound')
}

export function listChannelDeliveries(query: DeliveryQuery = {}) {
  return httpClient.get<DeliveryPage>('/api/admin/v1/channels/deliveries', { params: query })
    .then(response => response.data)
}

export function getChannelDelivery(deliveryId: string) {
  return httpClient.get<DeliveryDetail>(`/api/admin/v1/channels/deliveries/${encodeURIComponent(deliveryId)}`)
    .then(response => response.data)
}

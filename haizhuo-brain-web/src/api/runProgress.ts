import { httpClient } from './httpClient'

export type WorkItemResultAvailability = 'PRIVATE' | 'USER_VISIBLE' | 'UNAVAILABLE'

export interface WorkItemRevisionSummary {
  assignmentRevision: number
  executionState: string
  formatStatus: string
  reviewStatus: string
  resultAvailability: WorkItemResultAvailability
  resultId: string | null
  createdAt: string | null
  completedAt: string | null
}

export interface WorkItemSummary {
  workItemRef: string
  displayLabel: string
  roleId: string
  executorDisplayName: string
  sourceKind: string
  required: boolean
  assignmentRevision: number
  state: string
  createdAt: string
  updatedAt: string
  blockingDependencyCount: number | null
  latestRevision: WorkItemRevisionSummary
}

export interface RunProgressProjection {
  schemaVersion: number
  runId: string
  runtimeProfile: string
  runState: string
  version: string
  asOfSessionCursor: number
  updatedAt: string | null
  available: boolean
  summary: {
    workItemTotal: number
    activeCount: number
    returnedCount: number
    acceptedCount: number
    revisionRequestedCount: number
    rejectedCount: number
    unknownCount: number
  }
  delegationBudget: {
    invocationsReserved: number
    invocationLimit: number | null
    activeInvocations: number
    parallelLimit: number | null
  } | null
  workItemsPreview: WorkItemSummary[]
  hasMoreWorkItems: boolean
  team: {
    executionId: string
    state: string
    startedAt: string | null
    completedAt: string | null
    safeStopReasonCode: string | null
    memberSummaries: Array<{
      roleId: string
      displayName: string
      kind: string
      state: string
      lastTransitionAt: string | null
      stateSource: string
    }>
    actionBudgets: Array<{ actionType: string; reserved: number; limit: number | null }>
  } | null
}

export interface RunWorkItemDetail {
  runId: string
  item: WorkItemSummary
  revisions: WorkItemRevisionSummary[]
}

export interface RunWorkItemPage {
  items: WorkItemSummary[]
  nextCursor: string | null
  hasMore: boolean
}

export interface RunWorkItemResult {
  runId: string
  workItemRef: string
  assignmentRevision: number
  mediaType: string
  body: string
  bodySha256: string
  byteSize: number
  createdAt: string
}

export async function getRunProgress(runId: string, ifVersion?: string) {
  const response = await httpClient.get<RunProgressProjection>(
    `/api/v1/sessions/runs/${encodeURIComponent(runId)}/progress`,
    {
      params: ifVersion ? { ifVersion } : undefined,
      validateStatus: status => (status >= 200 && status < 300) || status === 304,
    },
  )
  return response.status === 304
    ? { notModified: true, projection: null as RunProgressProjection | null }
    : { notModified: false, projection: response.data }
}

export async function getRunWorkItems(runId: string, cursor?: string | null, limit = 100) {
  return (await httpClient.get<RunWorkItemPage>(
    `/api/v1/sessions/runs/${encodeURIComponent(runId)}/work-items`,
    { params: { cursor: cursor || undefined, limit } },
  )).data
}

export async function getRunWorkItem(runId: string, workItemRef: string) {
  return (await httpClient.get<RunWorkItemDetail>(
    `/api/v1/sessions/runs/${encodeURIComponent(runId)}/work-items/${encodeURIComponent(workItemRef)}`,
  )).data
}

export async function getRunWorkItemResult(runId: string, workItemRef: string, assignmentRevision: number) {
  return (await httpClient.get<RunWorkItemResult>(
    `/api/v1/sessions/runs/${encodeURIComponent(runId)}/work-items/${encodeURIComponent(workItemRef)}`
      + `/revisions/${assignmentRevision}/result`,
  )).data
}

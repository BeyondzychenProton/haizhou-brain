import { httpClient } from './httpClient'

export interface CursorPage<T> {
  items: T[]
  nextCursor: string | null
  hasMore: boolean
}

export interface SessionHistoryItem {
  sessionId: string
  employeeId: number
  definitionVersionId: number | null
  status: string
  createdAt: string
  lastActiveAt: string
}

export interface RunHistoryItem {
  runId: string
  sessionId: string
  state: string
  definitionVersionId: number
  createdAt: string
  queuePosition: number
  executorRoleId: string
  executorEmployeeId: number
  executorDefinitionVersionId: number
  mode: string
}

export interface ResultHistoryItem {
  resultId: string
  runId: string
  kind: string
  mediaType: string
  bodySha256: string
  byteSize: number
  createdAt: string
  executorRoleId: string | null
  executorEmployeeId: number | null
  executorDefinitionVersionId: number | null
}

export interface HistoricalToolSummary {
  toolExecutionId: string
  toolName: string
  state: string
  approvalDecision: string | null
  createdAt: string
  updatedAt: string
}

export interface RunFeedbackItem {
  feedbackId: string
  runId: string
  value: number
  comment: string | null
  createdAt: string
  exportDisposition: 'QUEUE_STATUS_UNKNOWN' | 'ACCEPTED_NOT_CONFIRMED' | 'NOT_ACCEPTED'
}

export function listSessionHistoryPage(filters: {
  employeeId?: number
  status?: string
  cursor?: string | null
  limit?: number
} = {}) {
  return httpClient.get<CursorPage<SessionHistoryItem>>('/api/v1/sessions/page', { params: filters })
    .then(response => response.data)
}

export function listRunHistoryPage(sessionId: string, filters: {
  state?: string
  cursor?: string | null
  limit?: number
} = {}) {
  return httpClient.get<CursorPage<RunHistoryItem>>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/runs/page`,
    { params: filters }).then(response => response.data)
}

export function listResultHistoryPage(sessionId: string, filters: { cursor?: string | null; limit?: number } = {}) {
  return httpClient.get<CursorPage<ResultHistoryItem>>(
    `/api/v1/sessions/${encodeURIComponent(sessionId)}/results/page`, { params: filters },
  ).then(response => response.data)
}

export function listHistoricalToolSummaries(runId: string) {
  return httpClient.get<HistoricalToolSummary[]>(
    `/api/v1/sessions/runs/${encodeURIComponent(runId)}/tool-summaries`,
  ).then(response => response.data)
}

export function listHistoricalRunFeedback(runId: string, filters: { cursor?: string | null; limit?: number } = {}) {
  return httpClient.get<CursorPage<RunFeedbackItem>>(
    `/api/v1/sessions/runs/${encodeURIComponent(runId)}/feedback`, { params: filters },
  ).then(response => response.data)
}

export function submitHistoricalRunFeedback(runId: string, request: {
  clientRequestId: string
  value: 0 | 1
  comment?: string
}) {
  return httpClient.post<RunFeedbackItem>(
    `/api/v1/sessions/runs/${encodeURIComponent(runId)}/feedback`, request,
  ).then(response => response.data)
}

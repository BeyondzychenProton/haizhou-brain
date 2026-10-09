import { httpClient } from './httpClient'

export type CapabilityAssetKind = 'SKILL' | 'KNOWLEDGE'

export interface CapabilityAssetFile {
  relativePath: string
  mediaType: string
  content: string
  sha256: string
  byteSize: number
}

export interface CapabilityAssetSummary {
  capabilityCode: string
  type: CapabilityAssetKind
  displayName: string
  draftRevision: number
  publishedRevision: string | null
  publishedRevisionId: number
  assetHash: string | null
  updatedAt: string
}

export interface CapabilityAssetDraft {
  capabilityCode: string
  type: CapabilityAssetKind
  draftRevision: number
  displayName: string
  description: string
  files: CapabilityAssetFile[]
  manifestJson: string
  assetHash: string
  updatedBy: number
  updatedAt: string
}

export interface CapabilityAssetRevision extends Omit<CapabilityAssetDraft, 'draftRevision' | 'updatedBy' | 'updatedAt'> {
  capabilityRevisionId: number
  revision: string
  publishRequestId: string
  reviewedBy: number
  reviewedAt: string
}

export interface CapabilityAssetRevisionSummary {
  capabilityRevisionId: number
  capabilityCode: string
  type: CapabilityAssetKind
  revision: string
  displayName: string
  assetHash: string
  reviewedBy: number
  reviewedAt: string
  fileCount: number
  totalBytes: number
}

export interface CapabilityAssetRevisionPage {
  items: CapabilityAssetRevisionSummary[]
  nextCursor: string | null
  hasMore: boolean
}

export function listCapabilityAssets() {
  return httpClient.get<CapabilityAssetSummary[]>('/api/admin/v1/capability-assets').then(r => r.data)
}

export function getCapabilityAssetDraft(code: string) {
  return httpClient.get<CapabilityAssetDraft>(`/api/admin/v1/capability-assets/${encodeURIComponent(code)}/draft`)
    .then(r => r.data)
}

export function saveCapabilityAssetDraft(code: string, payload: {
  expectedDraftRevision: number
  type: CapabilityAssetKind
  displayName: string
  description: string
  files: Array<Pick<CapabilityAssetFile, 'relativePath' | 'content'>>
  reason: string
}) {
  return httpClient.put<CapabilityAssetDraft>(
    `/api/admin/v1/capability-assets/${encodeURIComponent(code)}/draft`, payload
  ).then(r => r.data)
}

export function publishCapabilityAsset(code: string, expectedDraftRevision: number, requestId: string, reason: string) {
  return httpClient.post<CapabilityAssetRevision>(
    `/api/admin/v1/capability-assets/${encodeURIComponent(code)}/publish`,
    { expectedDraftRevision, requestId, reason }
  ).then(r => r.data)
}

export function listCapabilityAssetRevisions(code: string, cursor?: string, limit = 20) {
  const params: Record<string, string | number> = { limit }
  if (cursor) params.cursor = cursor
  return httpClient.get<CapabilityAssetRevisionPage>(
    `/api/admin/v1/capability-assets/${encodeURIComponent(code)}/revisions`, { params }
  ).then(r => r.data)
}

export function getCapabilityAssetRevision(code: string, revisionId: number) {
  return httpClient.get<CapabilityAssetRevision>(
    `/api/admin/v1/capability-assets/${encodeURIComponent(code)}/revisions/${revisionId}`
  ).then(r => r.data)
}

import { httpClient } from './httpClient'

export type RunArtifactState = 'STAGED' | 'AVAILABLE' | 'UNAVAILABLE'

export interface RunArtifactItem {
  artifactId: string
  runId: string
  title: string
  mediaType: 'text/markdown'
  byteSize: number
  sha256: string
  state: RunArtifactState
  createdAt: string
}

export interface RunArtifactPage {
  items: RunArtifactItem[]
  nextCursor: string | null
  hasMore: boolean
}

export function listRunArtifacts(runId: string, cursor?: string | null, limit = 20) {
  return httpClient.get<RunArtifactPage>(
    `/api/v1/sessions/runs/${encodeURIComponent(runId)}/artifacts`, { params: { cursor, limit } },
  ).then(response => response.data)
}

export function createMarkdownArtifact(runId: string, requestId: string) {
  return httpClient.post<RunArtifactItem>(
    `/api/v1/sessions/runs/${encodeURIComponent(runId)}/artifacts/markdown`, { requestId },
  ).then(response => response.data)
}

export async function downloadRunArtifact(artifactId: string): Promise<void> {
  const response = await httpClient.get<Blob>(
    `/api/v1/artifacts/${encodeURIComponent(artifactId)}/content`, { responseType: 'blob' },
  )
  const objectUrl = URL.createObjectURL(response.data)
  const anchor = document.createElement('a')
  anchor.href = objectUrl
  anchor.download = `${artifactId}.md`
  anchor.rel = 'noopener'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
}

import type { RunWorkItemPage, WorkItemRevisionSummary, WorkItemSummary } from '../api/runProgress'

export interface LoadedWorkItemPages {
  items: WorkItemSummary[]
  nextCursor: string | null
  hasMore: boolean
  loadedPageCount: number
}

export async function reloadLoadedWorkItemPages(
  requestedPageCount: number,
  fetchPage: (cursor: string | null, limit: number) => Promise<RunWorkItemPage>,
): Promise<LoadedWorkItemPages> {
  if (!Number.isInteger(requestedPageCount) || requestedPageCount < 0)
    throw new Error('loaded page count is invalid')

  const items: WorkItemSummary[] = []
  let cursor: string | null = null
  let nextCursor: string | null = null
  let hasMore = false
  let loadedPageCount = 0

  for (let pageIndex = 0; pageIndex < requestedPageCount; pageIndex++) {
    const page = await fetchPage(cursor, 100)
    items.push(...page.items)
    loadedPageCount++
    nextCursor = page.nextCursor
    hasMore = page.hasMore
    if (!page.hasMore || !page.nextCursor) break
    cursor = page.nextCursor
  }

  return { items, nextCursor, hasMore, loadedPageCount }
}

export function canOpenWorkItemResult(revision: WorkItemRevisionSummary, unavailableResultKeys: ReadonlySet<string>,
                                      workItemRef: string): boolean {
  const key = workItemResultKey(workItemRef, revision.assignmentRevision)
  return revision.resultAvailability === 'USER_VISIBLE' && !!revision.resultId && !unavailableResultKeys.has(key)
}

export function workItemResultKey(workItemRef: string, assignmentRevision: number): string {
  return `${workItemRef}:${assignmentRevision}`
}

export function progressResultFailureText(status?: number): string {
  return status === 404
    ? '该修订结果当前不可用或无权查看。'
    : '结果读取失败，请重试。'
}

export function progressStateLabel(state: string): string {
  return ({
    PENDING: '待执行',
    ACCEPTED: '已验收',
    REVISION_REQUESTED: '需修订',
    REJECTED: '已拒绝',
    RUNNING: '执行中',
    RETURNED: '已返回',
    INVALID: '格式未通过',
    NOT_STARTED: '未执行',
    UNKNOWN: '状态待核查',
    ACTIVE: '执行中',
    FINISHED: '执行结束',
  } as Record<string, string>)[state] ?? '状态待核查'
}

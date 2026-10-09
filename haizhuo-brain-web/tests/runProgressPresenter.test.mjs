import assert from 'node:assert/strict'
import test from 'node:test'
import { importTypeScript } from './helpers/importTypeScript.mjs'

const {
  canOpenWorkItemResult,
  progressResultFailureText,
  progressStateLabel,
  reloadLoadedWorkItemPages,
  workItemResultKey,
} = await importTypeScript(
  new URL('../src/presenters/runProgressPresenter.ts', import.meta.url),
)

test('only explicitly user-visible results with a linked result id can be opened', () => {
  const unavailable = new Set()
  const revision = { assignmentRevision: 2, resultAvailability: 'USER_VISIBLE', resultId: 'result-2' }

  assert.equal(canOpenWorkItemResult(revision, unavailable, 'item-a'), true)
  assert.equal(canOpenWorkItemResult({ ...revision, resultAvailability: 'PRIVATE' }, unavailable, 'item-a'), false)
  assert.equal(canOpenWorkItemResult({ ...revision, resultAvailability: 'UNAVAILABLE' }, unavailable, 'item-a'), false)
  assert.equal(canOpenWorkItemResult({ ...revision, resultId: null }, unavailable, 'item-a'), false)
  unavailable.add(workItemResultKey('item-a', 2))
  assert.equal(canOpenWorkItemResult(revision, unavailable, 'item-a'), false)
})

test('result failures hide authorization details and collaboration states stay explicit', () => {
  assert.equal(progressResultFailureText(404), '该修订结果当前不可用或无权查看。')
  assert.equal(progressResultFailureText(503), '结果读取失败，请重试。')
  assert.equal(progressStateLabel('NOT_STARTED'), '未执行')
  assert.equal(progressStateLabel('RECOVERY_REQUIRED'), '状态待核查')
})

test('loaded work item pages are reread sequentially and replaced from the first page', async () => {
  const cursors = []
  const refreshed = await reloadLoadedWorkItemPages(2, async (cursor, limit) => {
    cursors.push(cursor)
    assert.equal(limit, 100)
    if (cursor === null) {
      return {
        items: [{ workItemRef: 'item-1', state: 'RUNNING' }],
        nextCursor: 'cursor-page-1',
        hasMore: true,
      }
    }
    assert.equal(cursor, 'cursor-page-1')
    return {
      items: [{ workItemRef: 'item-101', state: 'PENDING' }],
      nextCursor: 'cursor-page-2',
      hasMore: true,
    }
  })

  assert.deepEqual(cursors, [null, 'cursor-page-1'])
  assert.deepEqual(refreshed.items, [
    { workItemRef: 'item-1', state: 'RUNNING' },
    { workItemRef: 'item-101', state: 'PENDING' },
  ])
  assert.equal(refreshed.nextCursor, 'cursor-page-2')
  assert.equal(refreshed.hasMore, true)
  assert.equal(refreshed.loadedPageCount, 2)
})

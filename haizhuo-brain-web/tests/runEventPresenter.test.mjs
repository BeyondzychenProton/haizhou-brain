import assert from 'node:assert/strict'
import test from 'node:test'
import { importTypeScript } from './helpers/importTypeScript.mjs'

const { applyCanonicalResult, applyRunState, mergeConversationEvent } = await importTypeScript(
  new URL('../src/presenters/runEventPresenter.ts', import.meta.url),
)

test('canonical result is the v2 root identity and seals late events and deltas', () => {
  const runId = 'run-contract-1'
  const draft = {
    runId,
    sequenceNo: 1,
    type: 'MODEL_DELTA',
    content: 'draft text',
    createdAt: '2026-10-09T00:00:00Z',
  }
  const completed = {
    runId,
    sequenceNo: 2,
    type: 'RUN_COMPLETED',
    content: 'short event summary',
    createdAt: '2026-10-09T00:00:01Z',
  }

  const drafting = mergeConversationEvent([], draft)
  const finalizing = mergeConversationEvent(drafting, completed)
  const result = {
    resultId: 'result-1', runId, mediaType: 'text/markdown', body: 'complete formal result',
    bodySha256: 'abc', byteSize: 22, schemaVersion: 1, createdAt: '2026-10-09T00:00:02Z',
    legacySummary: false, executorRoleId: 'coordinator', executorEmployeeId: 1,
    executorDefinitionVersionId: 2,
  }
  const final = applyCanonicalResult(finalizing, result)
  const afterLateDelta = mergeConversationEvent(final, draft)
  const afterLateSummary = mergeConversationEvent(afterLateDelta, completed)

  assert.equal(final.length, 1)
  assert.equal(final[0].key, `${runId}-assistant`)
  assert.equal(final[0].text, 'complete formal result')
  assert.equal(final[0].pending, false)
  assert.equal(final[0].bodySource, 'canonical-result')
  assert.equal(final[0].resultId, 'result-1')
  assert.equal(final[0].phase, 'final')
  assert.equal(finalizing[0].text, 'draft text')
  assert.equal(finalizing[0].bodySource, 'draft')
  assert.strictEqual(afterLateDelta, final)
  assert.strictEqual(afterLateSummary, final)
})

test('terminal failures stop the caret and preserve partial text as incomplete', () => {
  const draft = { runId: 'run-partial', sequenceNo: 1, type: 'MODEL_DELTA', content: 'partial body', createdAt: '' }
  const failed = { runId: 'run-partial', sequenceNo: 2, type: 'RUN_FAILED', content: 'safe failure', createdAt: '' }
  const partial = mergeConversationEvent(mergeConversationEvent([], draft), failed)[0]

  assert.equal(partial.text, 'partial body')
  assert.equal(partial.pending, false)
  assert.equal(partial.phase, 'partial-stopped')
  assert.equal(partial.statusMessage, 'safe failure')
  assert.equal(mergeConversationEvent([partial], { ...draft, sequenceNo: 3, content: 'late' })[0].text, 'partial body')
})

test('waiting phases stop the caret and allow the same Run to resume', () => {
  const draft = { runId: 'run-wait', sequenceNo: 1, type: 'MODEL_DELTA', content: 'before', createdAt: '' }
  const waiting = { runId: 'run-wait', sequenceNo: 2, type: 'RUN_WAITING_CONFIRMATION', content: 'approval needed', createdAt: '' }
  const resumed = { ...draft, sequenceNo: 3, content: 'after' }
  const before = mergeConversationEvent([], draft)
  const paused = mergeConversationEvent(before, waiting)
  const continued = mergeConversationEvent(paused, resumed)[0]

  assert.equal(paused[0].pending, false)
  assert.equal(paused[0].phase, 'waiting-confirmation')
  assert.equal(continued.text, 'beforeafter')
  assert.equal(continued.pending, true)
  assert.equal(continued.phase, 'generating')
})

test('a stale active-state poll cannot reopen a terminal message', () => {
  const draft = { runId: 'run-state', sequenceNo: 1, type: 'MODEL_DELTA', content: 'partial', createdAt: '' }
  const failed = mergeConversationEvent(mergeConversationEvent([], draft), {
    runId: 'run-state', sequenceNo: 2, type: 'RUN_FAILED', content: '', createdAt: '',
  })
  const stalePoll = applyRunState(failed, 'run-state', 'RUNNING')
  assert.equal(stalePoll[0].pending, false)
  assert.equal(stalePoll[0].phase, 'partial-stopped')
})

test('failure or cancellation without deltas still closes the root message and rejects late text', () => {
  const failed = mergeConversationEvent([], {
    runId: 'run-no-delta', sequenceNo: 1, type: 'RUN_FAILED', content: 'failed safely', createdAt: '',
  })
  assert.equal(failed.length, 1)
  assert.equal(failed[0].key, 'run-no-delta-assistant')
  assert.equal(failed[0].text, '')
  assert.equal(failed[0].pending, false)
  assert.equal(failed[0].terminal, true)

  const lateText = mergeConversationEvent(failed, {
    runId: 'run-no-delta', sequenceNo: 2, type: 'MODEL_DELTA', content: 'late', createdAt: '',
  })
  assert.strictEqual(lateText, failed)
  const staleRunning = mergeConversationEvent(failed, {
    runId: 'run-no-delta', sequenceNo: 3, type: 'RUN_STARTED', content: '', createdAt: '',
  })
  assert.strictEqual(staleRunning, failed)
})

test('late deltas during cancellation remain visibly partial without a generation caret', () => {
  const cancelling = mergeConversationEvent([], {
    runId: 'run-cancelling', sequenceNo: 1, type: 'RUN_CANCEL_REQUESTED', content: '', createdAt: '',
  })
  const lateDelta = mergeConversationEvent(cancelling, {
    runId: 'run-cancelling', sequenceNo: 2, type: 'MODEL_DELTA', content: 'last chunk', createdAt: '',
  })
  assert.equal(lateDelta[0].text, 'last chunk')
  assert.equal(lateDelta[0].pending, false)
  assert.equal(lateDelta[0].phase, 'cancelling')
})

test('long canonical Markdown keeps exact Unicode body and legacy results are labeled as summaries', () => {
  const runId = 'run-long-body'
  const body = `${'😀 **paragraph**\n\n```java\nvar answer = "完整正文";\n```\n\n'.repeat(160)}`
  const final = applyCanonicalResult([], {
    resultId: 'result-long', runId, mediaType: 'text/markdown', body, bodySha256: 'body-hash',
    byteSize: new TextEncoder().encode(body).byteLength, schemaVersion: 1,
    createdAt: '2026-10-09T00:00:00Z', legacySummary: false, executorRoleId: 'coordinator',
    executorEmployeeId: 1, executorDefinitionVersionId: 2,
  })
  assert.ok(body.length > 8_000)
  assert.equal(final[0].text, body)
  assert.equal(final[0].bodySource, 'canonical-result')

  const legacy = applyCanonicalResult([], {
    resultId: null, runId: 'run-legacy', mediaType: 'text/plain', body: 'old summary',
    bodySha256: 'legacy-hash', byteSize: 11, schemaVersion: 1,
    createdAt: '2026-10-09T00:00:00Z', legacySummary: true, executorRoleId: null,
    executorEmployeeId: null, executorDefinitionVersionId: null,
  })
  assert.equal(legacy[0].bodySource, 'legacy-summary')
  assert.match(legacy[0].statusMessage, /历史摘要/)
  assert.equal(legacy[0].pending, false)
})

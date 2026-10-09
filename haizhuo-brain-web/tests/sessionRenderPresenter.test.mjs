import assert from 'node:assert/strict'
import test from 'node:test'
import { importTypeScript } from './helpers/importTypeScript.mjs'

const { applyCanonicalRenderResult, applyRenderEvent, projectionFromView } = await importTypeScript(
  new URL('../src/presenters/sessionRenderPresenter.ts', import.meta.url),
)

function view(overrides = {}) {
  return {
    schemaVersion: 3,
    sessionId: 'session-1',
    runs: [{ runId: 'run-1', state: 'RUNNING' }],
    items: [],
    snapshotCursor: 7,
    cursorFloor: 1,
    renderCursor: 0,
    renderCursorFloor: 0,
    draftRecoveryStatus: 'AVAILABLE',
    nextCursor: null,
    hasMore: false,
    ...overrides,
  }
}

function batch(overrides = {}) {
  return {
    schemaVersion: 3,
    eventId: 'session-1:render:1',
    sessionId: 'session-1',
    sessionCursor: null,
    renderCursor: 1,
    runId: 'run-1',
    runSequence: null,
    attemptId: 'attempt-1',
    streamOffset: 3,
    type: 'message.text.batch',
    visibility: 'USER',
    durability: 'transient',
    occurredAt: '2026-10-09T04:00:00Z',
    payload: {
      messageId: 'run-1-draft-1', blockId: 'b-text', fromOffset: 0, toOffset: 2,
      delta: '你好', text: null, resultId: null,
    },
    ...overrides,
  }
}

test('root draft uses attempt-local code-point offsets and render cursor independently', () => {
  let projection = projectionFromView(view())
  projection = applyRenderEvent(projection, batch())
  assert.equal(projection.renderCursor, 1)
  assert.equal(projection.sessionCursor, 7)
  assert.equal(projection.items[0].attemptId, 'attempt-1')
  assert.equal(projection.items[0].text, '你好')
  assert.equal(projection.lastOffsetByAttempt['run-1:attempt-1'], 2)
  assert.strictEqual(applyRenderEvent(projection, batch()), projection)
})

test('canonical result identity is runId-assistant and late summaries cannot replace its body', () => {
  let projection = projectionFromView(view())
  projection = applyCanonicalRenderResult(projection, {
    runId: 'run-1', resultId: 'result-1', body: '完整正式正文',
    mediaType: 'text/markdown', bodySha256: 'hash-1',
  })
  projection = applyRenderEvent(projection, {
    schemaVersion: 3, eventId: 'session-1:8', sessionId: 'session-1',
    sessionCursor: 8, renderCursor: null, runId: 'run-1', runSequence: 9,
    attemptId: null, streamOffset: null, type: 'RUN_COMPLETED', visibility: 'USER', durability: 'durable',
    occurredAt: '2026-10-09T04:01:00Z',
    payload: { messageId: 'run-1-assistant', blockId: 'text', fromOffset: null, toOffset: null,
      delta: null, text: '短事件摘要', resultId: 'result-1' },
  })
  assert.equal(projection.items.length, 1)
  assert.equal(projection.items[0].messageId, 'run-1-assistant')
  assert.equal(projection.items[0].text, '完整正式正文')
  assert.equal(projection.items[0].bodySource, 'canonical-result')
})

test('late draft after a canonical result advances only in-order render position and adds no bubble', () => {
  let projection = applyCanonicalRenderResult(projectionFromView(view()), {
    runId: 'run-1', resultId: 'result-1', body: 'formal', mediaType: 'text/markdown', bodySha256: 'hash',
  })
  projection = applyRenderEvent(projection, batch())
  assert.equal(projection.renderCursor, 1)
  assert.equal(projection.items.length, 1)
  assert.equal(projection.items[0].text, 'formal')
})

test('missing render position triggers a view reload instead of silently skipping a batch', () => {
  const projection = applyRenderEvent(projectionFromView(view()), batch({ renderCursor: 2 }))
  assert.equal(projection.reloadRequired, true)
  assert.equal(projection.items.length, 0)
})

test('private visibility and non-root message types never enter the ordinary projection', () => {
  const privateEvent = batch({ visibility: 'INTERNAL' })
  const unknownEvent = batch({ type: 'private.message', renderCursor: 1 })
  const start = projectionFromView(view())
  assert.strictEqual(applyRenderEvent(start, privateEvent), start)
  assert.strictEqual(applyRenderEvent(start, unknownEvent), start)
})

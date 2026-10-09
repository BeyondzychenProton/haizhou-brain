import assert from 'node:assert/strict'
import test from 'node:test'
import { importTypeScript } from './helpers/importTypeScript.mjs'

const { ResultIdentityMismatchError, RunResultLoader } = await importTypeScript(
  new URL('../src/composables/runResultLoader.ts', import.meta.url),
)

function result(runId, overrides = {}) {
  return {
    resultId: `result-${runId}`, runId, mediaType: 'text/markdown', body: 'formal body', bodySha256: 'sha',
    byteSize: 11, schemaVersion: 1, createdAt: '2026-10-09T00:00:00Z', legacySummary: false,
    executorRoleId: 'coordinator', executorEmployeeId: 1, executorDefinitionVersionId: 2, ...overrides,
  }
}

test('parallel completion, polling, and history reads share one request and cache by Session + Run', async () => {
  let reads = 0
  let resolveRead
  const loader = new RunResultLoader(runId => {
    reads++
    if (reads === 1) return new Promise(resolve => { resolveRead = resolve })
    return Promise.resolve(result(runId))
  })
  const live = loader.ensure('session-1', 'run-1', 'result-run-1')
  const poll = loader.ensure('session-1', 'run-1')
  const history = loader.ensure('session-1', 'run-1')
  assert.equal(reads, 1)
  resolveRead(result('run-1'))

  const values = await Promise.all([live, poll, history])
  assert.deepEqual(values.map(value => value.body), ['formal body', 'formal body', 'formal body'])
  assert.strictEqual(await loader.ensure('session-1', 'run-1'), values[0])
  assert.equal(reads, 1)
})

test('network and server failures retry at bounded delays; client errors do not retry', async () => {
  const delays = []
  let reads = 0
  const loader = new RunResultLoader(async runId => {
    reads++
    if (reads === 1) throw new Error('network')
    return result(runId)
  }, { sleep: async delay => delays.push(delay) })
  await loader.ensure('session-1', 'run-2')
  assert.equal(reads, 2)
  assert.deepEqual(delays, [500])

  let deniedReads = 0
  const denied = new RunResultLoader(async () => {
    deniedReads++
    throw { response: { status: 403 } }
  }, { sleep: async () => assert.fail('403 must not retry') })
  await assert.rejects(denied.ensure('session-1', 'run-denied'))
  assert.equal(deniedReads, 1)
})

test('wrong Run or completed result identity is rejected and never cached', async () => {
  let reads = 0
  const loader = new RunResultLoader(async () => {
    reads++
    return result('some-other-run')
  })
  await assert.rejects(loader.ensure('session-1', 'run-3'), ResultIdentityMismatchError)
  await assert.rejects(loader.ensure('session-1', 'run-3', 'expected-result'), ResultIdentityMismatchError)
  assert.equal(reads, 2)

  const matchingRun = new RunResultLoader(async runId => result(runId, { resultId: 'actual-result' }))
  await assert.rejects(matchingRun.ensure('session-1', 'run-4', 'different-result'), ResultIdentityMismatchError)
  await assert.equal((await matchingRun.ensure('session-1', 'run-4')).resultId, 'actual-result')
})

test('switching or signing out clears cached bodies and fences late cache writes', async () => {
  let reads = 0
  let resolveRead
  const loader = new RunResultLoader(runId => {
    reads++
    if (reads === 1) return new Promise(resolve => { resolveRead = resolve })
    return Promise.resolve(result(runId))
  })
  const oldSessionRead = loader.ensure('session-old', 'run-5')
  loader.clearSession('session-old')
  resolveRead(result('run-5'))
  await oldSessionRead
  await loader.ensure('session-old', 'run-5')
  assert.equal(reads, 2)
  loader.clearAll()
  await loader.ensure('session-old', 'run-5')
  assert.equal(reads, 3)
})

test('result reads honor the configured concurrency cap while queued reads take reserved slots', async () => {
  let active = 0
  let maximumActive = 0
  const pending = []
  const loader = new RunResultLoader(runId => {
    active++
    maximumActive = Math.max(maximumActive, active)
    return new Promise(resolve => pending.push(() => {
      active--
      resolve(result(runId))
    }))
  }, { maxConcurrent: 2 })

  const first = loader.ensure('session', 'run-a')
  const second = loader.ensure('session', 'run-b')
  const third = loader.ensure('session', 'run-c')
  await Promise.resolve()
  assert.equal(pending.length, 2)

  pending[0]()
  await first
  const fourth = loader.ensure('session', 'run-d')
  await Promise.resolve()
  assert.equal(pending.length, 3)
  assert.equal(maximumActive, 2)

  pending[1]()
  pending[2]()
  await Promise.all([second, third])
  await Promise.resolve()
  assert.equal(pending.length, 4)
  pending[3]()
  await fourth
  assert.equal(maximumActive, 2)
})

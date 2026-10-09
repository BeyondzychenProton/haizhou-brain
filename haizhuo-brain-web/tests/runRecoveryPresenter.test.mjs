import assert from 'node:assert/strict'
import test from 'node:test'
import { importTypeScript } from './helpers/importTypeScript.mjs'

const { canSubmitTermination, createLatestRequestGeneration, recoveryActionRecorded, terminationEligibilityText } = await importTypeScript(
  new URL('../src/presenters/runRecoveryPresenter.ts', import.meta.url)
)

function detail(overrides = {}) {
  return {
    runId: 'run-1',
    sessionId: 'session-1',
    ownerUserId: 42,
    employeeId: 8,
    definitionVersionId: 108,
    runtimeProfile: 'SINGLE_SKILLED',
    state: 'RECOVERY_REQUIRED',
    failureCode: 'LEASE_LOST',
    createdAt: '2026-10-09T04:00:00Z',
    startedAt: '2026-10-09T04:01:00Z',
    finishedAt: null,
    recoveryRequiredAt: null,
    recoveryRequiredAtStatus: 'UNKNOWN',
    latestAttempt: {
      attemptId: 'attempt-1',
      attemptNo: 1,
      workerLabel: 'worker-old',
      fenceToken: 19,
      state: 'RECOVERY_REQUIRED',
      heartbeatAt: '2026-10-09T03:00:00Z',
      leaseExpiresAt: '2026-10-09T03:01:00Z',
      startedAt: '2026-10-09T02:59:00Z',
      finishedAt: null,
    },
    terminationEligibility: { allowed: true, reasonCode: 'ELIGIBLE', expectedFenceToken: 19 },
    publicEventSummary: [],
    recoveryActions: [],
    observedAt: '2026-10-09T04:02:00Z',
    ...overrides,
  }
}

test('expired lease and stale heartbeat do not replace the explicit stop evidence confirmation', () => {
  const run = detail()
  assert.equal(canSubmitTermination(run, false, 'ops-ticket-1', '已人工核查'), false)
  assert.equal(canSubmitTermination(run, true, '', '已人工核查'), false)
  assert.equal(canSubmitTermination(run, true, 'ops-ticket-1', ''), false)
  assert.equal(canSubmitTermination(run, true, 'ops-ticket-1', '已人工核查'), true)
  assert.match(terminationEligibilityText(run), /不证明旧执行或外部动作已停止/)
})

test('server eligibility and Run state remain required for termination', () => {
  assert.equal(canSubmitTermination(detail({
    terminationEligibility: { allowed: false, reasonCode: 'LEGACY_PROFILE', expectedFenceToken: null },
  }), true, 'ops-ticket-1', '已人工核查'), false)
  assert.equal(canSubmitTermination(detail({ state: 'TERMINATED' }), true, 'ops-ticket-1', '已人工核查'), false)
})

test('refresh can reconcile an uncertain response by the stable request id', () => {
  assert.equal(recoveryActionRecorded(detail({ recoveryActions: [{ requestId: 'request-1' }] }), 'request-1'), true)
  assert.equal(recoveryActionRecorded(detail(), 'request-1'), false)
})

test('a newer detail read for the selected Run wins over a late response for the previous selection', async () => {
  const requests = createLatestRequestGeneration()
  let resolvePrevious
  const previousResponse = new Promise(resolve => { resolvePrevious = resolve })
  let displayedRunId = null

  async function applyWhenCurrent(runId, response) {
    const generation = requests.next()
    const result = await response
    if (requests.isCurrent(generation)) displayedRunId = result
  }

  const previousRead = applyWhenCurrent('run-a', previousResponse)
  await applyWhenCurrent('run-b', Promise.resolve('run-b'))
  resolvePrevious('run-a')
  await previousRead

  assert.equal(displayedRunId, 'run-b')
})

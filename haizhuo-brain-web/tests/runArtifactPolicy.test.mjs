import assert from 'node:assert/strict'
import test from 'node:test'
import { importTypeScript } from './helpers/importTypeScript.mjs'

const { isMarkdownArtifactExportAllowed } = await importTypeScript(
  new URL('../src/presenters/runArtifactPolicy.ts', import.meta.url),
)

const run = { runId: 'run-1', state: 'SUCCEEDED' }
const fullResult = { runId: 'run-1', legacySummary: false }

test('Markdown export requires the selected successful Run full canonical result', () => {
  assert.equal(isMarkdownArtifactExportAllowed(null, fullResult), false)
  assert.equal(isMarkdownArtifactExportAllowed({ runId: 'run-1', state: 'RUNNING' }, fullResult), false)
  assert.equal(isMarkdownArtifactExportAllowed(run, null), false)
  assert.equal(isMarkdownArtifactExportAllowed(run, { ...fullResult, runId: 'run-other' }), false)
  assert.equal(isMarkdownArtifactExportAllowed(run, { ...fullResult, legacySummary: true }), false)
  assert.equal(isMarkdownArtifactExportAllowed(run, fullResult), true)
})

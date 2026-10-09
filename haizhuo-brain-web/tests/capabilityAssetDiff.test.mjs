import assert from 'node:assert/strict'
import test from 'node:test'
import { importTypeScript } from './helpers/importTypeScript.mjs'

const { compareAssetFiles, makeTextDiff } = await importTypeScript(
  new URL('../src/presenters/capabilityAssetDiff.ts', import.meta.url),
)

test('asset manifest comparison distinguishes added, removed, and changed files', () => {
  const changes = compareAssetFiles(
    [
      { relativePath: 'keep.md', byteSize: 2, sha256: 'a', content: 'ok' },
      { relativePath: 'remove.md', byteSize: 4, sha256: 'b', content: 'gone' },
      { relativePath: 'change.md', byteSize: 3, sha256: 'c', content: 'old' },
    ],
    [
      { relativePath: 'keep.md', byteSize: 2, sha256: 'a', content: 'ok' },
      { relativePath: 'change.md', byteSize: 3, sha256: 'd', content: 'new' },
      { relativePath: 'add.md', byteSize: 5, sha256: 'e', content: 'new file' },
    ],
  )
  assert.deepEqual(changes.map(item => [item.relativePath, item.kind]), [
    ['add.md', 'ADDED'], ['change.md', 'CHANGED'], ['remove.md', 'REMOVED'],
  ])
})

test('text diff is based on original lines and preserves an unchanged suffix', () => {
  assert.deepEqual(makeTextDiff('one\nold\nlast', 'one\nnew\nlast'), [
    { kind: 'same', text: 'one' },
    { kind: 'removed', text: 'old' },
    { kind: 'added', text: 'new' },
    { kind: 'same', text: 'last' },
  ])
})

test('large text diff stays bounded while leaving the original body readable', () => {
  assert.deepEqual(makeTextDiff('a\nb', 'a\nc', 3), [
    { kind: 'same', text: '差异预览超过 3 行；请切换原文查看完整内容。' },
  ])
})

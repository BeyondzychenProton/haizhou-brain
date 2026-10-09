import assert from 'node:assert/strict'
import test from 'node:test'
import { importTypeScript } from './helpers/importTypeScript.mjs'

const { sanitizeDisplayText } = await importTypeScript(
  new URL('../src/utils/sanitizeDisplayText.ts', import.meta.url),
)

test('redacts authorization headers, bearer and key-value credentials, and internal paths', () => {
  const input = [
    'Authorization: Bearer abc.def-123',
    'proxy-authorization: Basic private-value',
    'api_key=key-value clientSecret: "client-secret" authorization=auth-value Bearer free.token-value',
    String.raw`C:\Users\Alice Smith\private\answer.md /home/alice/private/result.md`,
  ].join('\n')
  const safe = sanitizeDisplayText(input)
  assert.equal(safe, [
    'Authorization: [redacted]', 'proxy-authorization: [redacted]',
    'api_key=[redacted] clientSecret: [redacted] authorization=[redacted] Bearer [redacted]',
    '[internal path omitted] [internal path omitted]',
  ].join('\n'))
  assert.equal(sanitizeDisplayText(safe), safe, 'redaction is idempotent for already-redacted text')
  for (const secret of ['abc.def-123', 'private-value', 'key-value', 'client-secret', 'auth-value', 'Alice Smith', '/home/alice'])
    assert.equal(safe.includes(secret), false)
})

test('ordinary text remains unchanged and nullish text renders empty', () => {
  assert.equal(sanitizeDisplayText('ordinary explanation'), 'ordinary explanation')
  assert.equal(sanitizeDisplayText(undefined), '')
  assert.equal(sanitizeDisplayText(null), '')
})

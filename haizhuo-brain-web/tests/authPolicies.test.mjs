import assert from 'node:assert/strict'
import test from 'node:test'
import { importTypeScript } from './helpers/importTypeScript.mjs'

const policies = await importTypeScript(new URL('../src/api/authPolicies.ts', import.meta.url))

test('activation validates the original token and Java-compatible password length boundaries', () => {
  assert.equal(policies.validateActivationFields(' selector.secret ', '123456789012', '123456789012'), null)
  assert.equal(policies.validateActivationFields('   ', '123456789012', '123456789012'), '请粘贴管理员提供的激活凭据')
  assert.equal(policies.validateActivationFields('x'.repeat(513), '123456789012', '123456789012'), '激活凭据不能超过 512 个字符')
  assert.equal(policies.validateActivationFields('token', '12345678901', '12345678901'), '新密码长度必须为 12 到 128 个字符')
  assert.equal(policies.validateActivationFields('token', 'x'.repeat(129), 'x'.repeat(129)), '新密码长度必须为 12 到 128 个字符')
  assert.equal(policies.validateActivationFields('token', '123456789012', '123456789013'), '两次输入的新密码不一致')
})

test('password change validates current password without trimming submitted values', () => {
  assert.equal(policies.validatePasswordChangeFields('old-password', ' 1234567890 ', ' 1234567890 '), null)
  assert.equal(policies.validatePasswordChangeFields('x'.repeat(128), 'y'.repeat(128), 'y'.repeat(128)), null)
  assert.equal(policies.validatePasswordChangeFields('   ', '123456789012', '123456789012'), '请输入当前密码')
  assert.equal(policies.validatePasswordChangeFields('x'.repeat(129), '123456789012', '123456789012'), '当前密码不能超过 128 个字符')
  assert.equal(policies.validatePasswordChangeFields('old-password', '             ', '             '), '请输入新密码')
})

test('local 401s stay on the credential form while other 401s retain global login handling', () => {
  assert.equal(policies.shouldRedirectForUnauthorized(401, 'local', 'activate'), false)
  assert.equal(policies.shouldRedirectForUnauthorized(401, 'local', 'password-change'), false)
  assert.equal(policies.shouldRedirectForUnauthorized(401, undefined, 'employees'), true)
  assert.equal(policies.shouldRedirectForUnauthorized(401, undefined, 'login'), false)
  assert.equal(policies.shouldRedirectForUnauthorized(403, undefined, 'employees'), false)
})

test('forced and daily password routes remain distinct', () => {
  assert.equal(policies.requiredPasswordChangeRedirect(true, 'employees'), 'password-change')
  assert.equal(policies.requiredPasswordChangeRedirect(true, 'admin'), 'password-change')
  assert.equal(policies.requiredPasswordChangeRedirect(true, 'password-change'), null)
  assert.equal(policies.requiredPasswordChangeRedirect(false, 'password-change'), null)
  assert.equal(policies.loginDestination(true), 'password-change')
  assert.equal(policies.loginDestination(false), 'employees')
})

test('network and 5xx write failures are treated as uncertain, not password errors', () => {
  assert.equal(policies.isUncertainMutationError({}), true)
  assert.equal(policies.isUncertainMutationError({ response: { status: 503 } }), true)
  assert.equal(policies.isUncertainMutationError({ response: { status: 401 } }), false)
  assert.equal(policies.isUncertainMutationError({ response: { status: 403 } }), false)
})

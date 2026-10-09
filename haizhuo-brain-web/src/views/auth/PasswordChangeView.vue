<template>
  <div class="login-page">
    <el-card class="login-card">
      <h1>{{ forcedMode ? '请设置新的登录密码' : '修改密码' }}</h1>
      <p class="muted">
        {{ forcedMode ? '请设置新的登录密码后继续使用工作台。' : '输入当前密码并设置新的登录密码。' }}
      </p>

      <template v-if="locked">
        <el-alert :title="error" type="warning" show-icon :closable="false" />
        <el-button v-if="refreshPage" class="mt" @click="reloadPage">刷新登录状态</el-button>
        <el-button v-else class="mt" @click="returnToLogin">{{ auth.authenticated ? '退出并返回登录' : '返回登录' }}</el-button>
      </template>

      <el-form v-else @submit.prevent="submit">
        <el-form-item label="当前密码">
          <el-input
            v-model="currentPassword"
            type="password"
            show-password
            autocomplete="current-password"
            maxlength="128"
          />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input
            v-model="newPassword"
            type="password"
            show-password
            autocomplete="new-password"
            maxlength="128"
          />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input
            v-model="confirmedPassword"
            type="password"
            show-password
            autocomplete="new-password"
            maxlength="128"
          />
        </el-form-item>
        <div class="actions">
          <el-button type="primary" native-type="submit" :loading="submitting || auth.loading">
            保存新密码
          </el-button>
          <el-button v-if="!forcedMode" :disabled="submitting || auth.loading" @click="cancel">取消</el-button>
          <el-button link :disabled="auth.loading" @click="signOut">退出登录</el-button>
        </div>
      </el-form>

      <el-alert v-if="error && !locked" :title="error" type="error" show-icon :closable="false" class="mt" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { responseStatus, validatePasswordChangeFields } from '../../api/authPolicies'
import { AuthFlowError, useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const currentPassword = ref('')
const newPassword = ref('')
const confirmedPassword = ref('')
const error = ref('')
const submitting = ref(false)
const locked = ref(false)
const refreshPage = ref(false)
const forcedMode = computed(() => auth.requiresPasswordChange)
let disposed = false

function clearSensitiveFields(): void {
  currentPassword.value = ''
  newPassword.value = ''
  confirmedPassword.value = ''
}

async function submit(): Promise<void> {
  if (submitting.value || locked.value) return
  error.value = ''
  const validationError = validatePasswordChangeFields(
    currentPassword.value,
    newPassword.value,
    confirmedPassword.value,
  )
  if (validationError) {
    error.value = validationError
    return
  }

  submitting.value = true
  try {
    await auth.changePassword(currentPassword.value, newPassword.value)
    if (disposed) return
    clearSensitiveFields()
    await router.push({ name: 'employees' })
  } catch (requestError: unknown) {
    if (disposed) return
    if (requestError instanceof AuthFlowError) {
      switch (requestError.code) {
        case 'CURRENT_PASSWORD_INVALID':
          currentPassword.value = ''
          error.value = '当前密码不正确'
          return
        case 'SESSION_EXPIRED':
        case 'PASSWORD_UPDATED_SESSION_EXPIRED':
        case 'PASSWORD_RESULT_UNCONFIRMED_SESSION_EXPIRED':
          clearSensitiveFields()
          locked.value = true
          error.value = requestError.code === 'SESSION_EXPIRED'
            ? '登录状态已失效，请使用新密码尝试登录。'
            : '密码修改结果尚未确认，登录状态已失效。请使用新密码尝试登录。'
          return
        case 'SESSION_CHECK_UNAVAILABLE':
          clearSensitiveFields()
          locked.value = true
          refreshPage.value = true
          error.value = '当前无法确认登录会话状态。请刷新页面后再继续。'
          return
        case 'REQUEST_VERIFICATION_FAILED':
          error.value = '请求验证失败，请刷新页面后重试。'
          return
        case 'INVALID_REQUEST':
          error.value = '请检查密码长度和输入内容后重试。'
          return
        case 'PASSWORD_RESULT_UNCONFIRMED':
          clearSensitiveFields()
          locked.value = true
          error.value = '修改结果尚未确认，请勿重复提交。请退出后尝试使用新密码登录；若登录失败，再使用原密码或联系管理员。'
          return
        case 'PASSWORD_UPDATED_NEEDS_REFRESH':
          clearSensitiveFields()
          locked.value = true
          error.value = '密码已更新，但当前会话状态需要刷新。请退出后使用新密码重新登录。'
          return
        case 'SESSION_CHANGED':
          clearSensitiveFields()
          locked.value = true
          error.value = '账号会话已变化，请返回登录页重新确认身份。'
          return
        default:
          error.value = '密码修改失败，请稍后重试。'
          return
      }
    }

    if (responseStatus(requestError) === 401) {
      error.value = '当前密码不正确'
    } else {
      error.value = '密码修改失败，请稍后重试。'
    }
  } finally {
    submitting.value = false
  }
}

async function cancel(): Promise<void> {
  if (forcedMode.value) return
  clearSensitiveFields()
  await router.push({ name: 'employees' })
}

async function signOut(): Promise<void> {
  try {
    await auth.logout()
    await router.push({ name: 'login' })
  } catch {
    error.value = '退出状态尚未确认，请刷新页面后重试。'
  }
}

async function returnToLogin(): Promise<void> {
  if (auth.authenticated) {
    try {
      await auth.logout()
    } catch {
      if (auth.authenticated) {
        error.value = '退出状态尚未确认，请刷新页面后重试。'
        return
      }
    }
  }
  await router.push({ name: 'login' })
}

function reloadPage(): void {
  window.location.reload()
}

onBeforeRouteLeave(() => {
  disposed = true
  clearSensitiveFields()
})

onBeforeUnmount(() => {
  disposed = true
  clearSensitiveFields()
})
</script>

<style scoped>
.actions { display: flex; flex-wrap: wrap; gap: 8px; }
</style>

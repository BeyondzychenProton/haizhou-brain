<template>
  <div class="login-page">
    <el-card class="login-card">
      <h1>激活账号</h1>

      <template v-if="auth.authenticated">
        <el-alert
          title="当前浏览器已登录，请先退出当前账号后再激活。"
          type="warning"
          show-icon
          :closable="false"
        />
        <p class="muted">当前账号：{{ auth.currentUser?.mobileMasked }}</p>
        <el-button :loading="auth.loading" @click="signOut">退出当前账号</el-button>
      </template>

      <div v-else-if="checkingSession" class="muted">正在确认当前登录状态…</div>

      <div v-else-if="sessionUnknown">
        <el-alert
          title="当前无法确认登录状态，请刷新后重试。"
          type="warning"
          show-icon
          :closable="false"
        />
        <el-button class="mt" :loading="checkingSession" @click="checkCurrentSession">重新检查</el-button>
      </div>

      <div v-else-if="activationState === 'ACTIVATED'">
        <el-result icon="success" title="账号已激活" sub-title="请返回登录页，使用手机号和新密码登录。">
          <template #extra>
            <el-button type="primary" @click="goToLogin">前往登录</el-button>
          </template>
        </el-result>
      </div>

      <el-form v-else @submit.prevent="submit">
        <p class="muted">请粘贴管理员提供的一次性激活凭据，并设置新的登录密码。</p>
        <el-form-item label="激活凭据">
          <el-input
            v-model="activationToken"
            type="textarea"
            :rows="3"
            maxlength="512"
            show-word-limit
            autocomplete="off"
          />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="newPassword" type="password" show-password autocomplete="new-password" maxlength="128" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="confirmedPassword" type="password" show-password autocomplete="new-password" maxlength="128" />
        </el-form-item>
        <el-button
          type="primary"
          native-type="submit"
          :loading="activationState === 'SUBMITTING'"
          :disabled="activationState === 'SUBMITTING'"
          class="full"
        >
          激活账号
        </el-button>
      </el-form>

      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="mt" />
      <div v-if="activationState !== 'ACTIVATED'" class="auth-links">
        <router-link to="/login">返回登录</router-link>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import * as authApi from '../../api/auth'
import { isUncertainMutationError, responseStatus, validateActivationFields } from '../../api/authPolicies'
import { useAuthStore } from '../../stores/auth'

type ActivationState = 'EDITING' | 'SUBMITTING' | 'ACTIVATED'

const auth = useAuthStore()
const router = useRouter()
const activationToken = ref('')
const newPassword = ref('')
const confirmedPassword = ref('')
const error = ref('')
const activationState = ref<ActivationState>('EDITING')
const checkingSession = ref(true)
const sessionUnknown = ref(false)
let disposed = false

function clearSensitiveFields(): void {
  activationToken.value = ''
  newPassword.value = ''
  confirmedPassword.value = ''
}

function clearPasswords(): void {
  newPassword.value = ''
  confirmedPassword.value = ''
}

async function checkCurrentSession(): Promise<void> {
  checkingSession.value = true
  sessionUnknown.value = false
  try {
    if (!auth.currentUser) await auth.load()
  } catch {
    if (!disposed) sessionUnknown.value = true
  } finally {
    if (!disposed) checkingSession.value = false
  }
}

async function submit(): Promise<void> {
  if (activationState.value === 'SUBMITTING' || auth.authenticated || sessionUnknown.value) return
  error.value = ''
  const validationError = validateActivationFields(
    activationToken.value,
    newPassword.value,
    confirmedPassword.value,
  )
  if (validationError) {
    error.value = validationError
    return
  }

  activationState.value = 'SUBMITTING'
  try {
    await authApi.activateAccount(activationToken.value, newPassword.value)
    if (disposed) return
    clearSensitiveFields()
    activationState.value = 'ACTIVATED'
  } catch (requestError: unknown) {
    if (disposed) return
    clearPasswords()
    activationState.value = 'EDITING'
    const status = responseStatus(requestError)
    if (status === 401) {
      error.value = '激活未完成，请核对凭据，稍后重试或联系管理员重新获取。'
    } else if (status === 403) {
      error.value = '请求验证失败，请刷新页面后重试。'
    } else if (isUncertainMutationError(requestError)) {
      error.value = '激活请求状态尚未确认。请勿连续提交；稍后重试或联系管理员核查。'
    } else {
      error.value = '激活未完成，请检查输入后重试。'
    }
  }
}

async function signOut(): Promise<void> {
  error.value = ''
  try {
    await auth.logout()
  } catch {
    error.value = '退出状态尚未确认，请刷新页面后重试。'
  }
}

async function goToLogin(): Promise<void> {
  await router.push({ name: 'login' })
}

onMounted(() => {
  void checkCurrentSession()
})

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
.auth-links { margin-top: 18px; text-align: center; }
.auth-links a { color: var(--el-color-primary); text-decoration: none; }
</style>

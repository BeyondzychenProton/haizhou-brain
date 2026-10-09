<template>
  <div class="login-page">
    <el-card class="login-card">
      <h1>海卓智慧大脑</h1>
      <p class="muted">登录你的数字员工工作台</p>
      <el-form @submit.prevent="submit">
        <el-form-item label="手机号">
          <el-input v-model="mobile" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="auth.loading" class="full">登录</el-button>
      </el-form>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="mt" />
      <div class="auth-links">
        <router-link to="/activate">激活账号</router-link>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore, AuthFlowError } from '../../stores/auth'
import { loginDestination, responseStatus } from '../../api/authPolicies'

const auth = useAuthStore()
const router = useRouter()
const mobile = ref('')
const password = ref('')
const error = ref('')
let submitting = false

async function submit() {
  if (submitting) return
  error.value = ''
  submitting = true
  try {
    await auth.login(mobile.value, password.value)
    await router.push({ name: loginDestination(auth.requiresPasswordChange) })
  } catch (requestError: unknown) {
    if (requestError instanceof AuthFlowError && requestError.code === 'LOGIN_SESSION_UNVERIFIED') {
      password.value = ''
      error.value = '登录请求已完成，但当前会话状态无法确认。请刷新页面后重试。'
    } else if (responseStatus(requestError) === 401) {
      error.value = '手机号或密码错误'
    } else {
      error.value = '登录失败，请稍后重试'
    }
  } finally {
    submitting = false
  }
}
</script>

<style scoped>
.auth-links { margin-top: 18px; text-align: center; }
.auth-links a { color: var(--el-color-primary); text-decoration: none; }
</style>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <h1>请修改初始密码</h1>
      <p class="muted">为保护管理员账号，请先设置新的登录密码。</p>
      <el-form @submit.prevent="submit">
        <el-form-item label="当前密码"><el-input v-model="currentPassword" type="password" show-password autocomplete="current-password" /></el-form-item>
        <el-form-item label="新密码"><el-input v-model="newPassword" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-form-item label="确认新密码"><el-input v-model="confirmedPassword" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="auth.loading" class="full">保存并进入工作台</el-button>
      </el-form>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="mt" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const currentPassword = ref('')
const newPassword = ref('')
const confirmedPassword = ref('')
const error = ref('')

async function submit() {
  error.value = ''
  if (newPassword.value !== confirmedPassword.value) {
    error.value = '两次输入的新密码不一致'
    return
  }
  try {
    await auth.changePassword(currentPassword.value, newPassword.value)
    await router.push({ name: 'employees' })
  } catch (requestError: any) {
    error.value = requestError.response?.status === 401 ? '当前密码不正确' : '密码修改失败，请稍后重试'
  }
}
</script>

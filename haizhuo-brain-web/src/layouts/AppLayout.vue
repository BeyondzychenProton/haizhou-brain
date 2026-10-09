<template>
  <el-container class="shell">
    <el-aside width="248px">
      <div class="brand">海卓智慧大脑</div>
      <div class="side-title">我的数字员工</div>
      <el-menu router :default-active="$route.path">
        <el-menu-item index="/app/employees">数字员工</el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/admin">管理中心</el-menu-item>
        <el-menu-item v-if="!auth.requiresPasswordChange" index="/password/change">修改密码</el-menu-item>
      </el-menu>
      <div class="side-user">
        <span>{{ auth.currentUser?.mobileMasked }}</span>
        <el-button link @click="signOut">退出</el-button>
      </div>
    </el-aside>
    <el-main>
      <el-alert
        v-if="auth.sessionNeedsRefresh"
        title="当前登录状态暂时无法确认，请刷新页面后重试。"
        type="warning"
        show-icon
        :closable="false"
        class="mb"
      />
      <el-alert v-if="signOutError" :title="signOutError" type="error" show-icon :closable="false" class="mb" />
      <slot />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useRouter } from 'vue-router'

const auth = useAuthStore()
const router = useRouter()
const signOutError = ref('')

async function signOut(): Promise<void> {
  signOutError.value = ''
  try {
    await auth.logout()
    await router.push('/login')
  } catch {
    signOutError.value = '退出状态尚未确认，请稍后重试。'
  }
}
</script>

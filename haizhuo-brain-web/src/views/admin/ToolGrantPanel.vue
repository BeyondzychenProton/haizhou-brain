<template>
  <section>
    <div class="page-head">
      <div>
        <h3>工具授权</h3>
        <p class="muted">按平台用户管理能力编码级授权；这不替代 MCP Server 对目标资源的最终判权。</p>
      </div>
      <div class="toolbar">
        <el-input v-model="keyword" clearable placeholder="按能力名称或编码筛选" style="width: 240px" />
        <el-button @click="refresh">刷新</el-button>
      </div>
    </div>

    <el-alert type="warning" :closable="false" class="block"
              title="授予能力不等于绕过资源权限；每次实际工具调用仍由服务端重新判权。" />

    <el-form label-width="90px" class="block">
      <el-form-item label="平台用户">
        <el-select v-model="selectedUserId" filterable placeholder="请选择已激活用户" style="width: 340px"
                   :loading="usersLoading" @change="loadGrants">
          <el-option v-for="user in users" :key="user.userId" :label="userLabel(user)" :value="user.userId" />
        </el-select>
      </el-form-item>
    </el-form>

    <el-skeleton v-if="loading" :rows="6" animated />
    <el-empty v-else-if="!selectedUserId" description="请选择平台用户" />
    <el-empty v-else-if="!filteredRows.length" description="没有匹配的能力" />
    <el-table v-else :data="filteredRows" border stripe>
      <el-table-column prop="displayName" label="能力" min-width="150" />
      <el-table-column prop="capabilityCode" label="编码" min-width="190" />
      <el-table-column label="来源" width="110">
        <template #default="{ row }">{{ typeLabel(row.type) }}</template>
      </el-table-column>
      <el-table-column prop="revisions" label="修订" min-width="100" />
      <el-table-column label="全局状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="用户授权" width="130" align="center">
        <template #default="{ row }">
          <el-switch :model-value="row.granted" :loading="busyCode === row.capabilityCode"
                     :disabled="!row.enabled" @change="onToggle(row, $event)" />
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import * as adminApi from '../../api/admin'
import type { Capability, UserCapabilityGrant, UserDirectoryEntry } from '../../api/admin'
import { notifyError, notifySuccess } from '../../utils/notify'

interface GrantRow {
  capabilityCode: string
  displayName: string
  type: Capability['type']
  revisions: string
  enabled: boolean
  granted: boolean
}

const users = ref<UserDirectoryEntry[]>([])
const capabilities = ref<Capability[]>([])
const grants = ref<UserCapabilityGrant[]>([])
const selectedUserId = ref<number>()
const keyword = ref('')
const usersLoading = ref(false)
const loading = ref(false)
const busyCode = ref('')

const rows = computed<GrantRow[]>(() => {
  const grouped = new Map<string, Capability[]>()
  for (const capability of capabilities.value) {
    const existing = grouped.get(capability.capabilityCode) ?? []
    existing.push(capability)
    grouped.set(capability.capabilityCode, existing)
  }
  const grantMap = new Map(grants.value.map(item => [item.capabilityCode, item.enabled]))
  return [...grouped.entries()].map(([capabilityCode, entries]) => ({
    capabilityCode,
    displayName: entries[0].displayName,
    type: entries[0].type,
    revisions: entries.map(item => item.revision).join('、'),
    enabled: entries.some(item => item.enabled),
    granted: grantMap.get(capabilityCode) ?? false
  }))
})

const filteredRows = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  if (!key) return rows.value
  return rows.value.filter(row => row.capabilityCode.toLowerCase().includes(key)
    || row.displayName.toLowerCase().includes(key))
})

async function refresh() {
  usersLoading.value = true
  try {
    const [userPage, catalog] = await Promise.all([
      adminApi.listUsers({ status: 'ACTIVE', limit: 100, offset: 0 }),
      adminApi.capabilities()
    ])
    users.value = userPage.content
    capabilities.value = catalog
    if (!selectedUserId.value || !users.value.some(user => user.userId === selectedUserId.value)) {
      selectedUserId.value = users.value[0]?.userId
    }
  } catch (error) {
    notifyError(error, '工具授权基础数据加载失败')
  } finally {
    usersLoading.value = false
  }
  await loadGrants()
}

async function loadGrants() {
  if (!selectedUserId.value) {
    grants.value = []
    return
  }
  loading.value = true
  try {
    grants.value = await adminApi.userCapabilityGrants(selectedUserId.value)
  } catch (error) {
    grants.value = []
    notifyError(error, '用户工具授权加载失败')
  } finally {
    loading.value = false
  }
}

async function toggle(row: GrantRow, enabled: boolean) {
  if (!selectedUserId.value) return
  let reason = ''
  try {
    const prompt = await ElMessageBox.prompt(
      `请输入「${enabled ? '授予' : '撤销'}」${row.displayName} 的操作原因`,
      enabled ? '授予工具能力' : '撤销工具能力',
      { inputType: 'textarea', inputPlaceholder: '操作原因（必填，最多 500 字）', inputValidator: validateReason }
    )
    reason = String(prompt.value ?? '').trim()
  } catch {
    return
  }
  if (!reason) return

  busyCode.value = row.capabilityCode
  try {
    await adminApi.setUserGrant(selectedUserId.value, row.capabilityCode, enabled, reason)
    const existing = grants.value.find(item => item.capabilityCode === row.capabilityCode)
    if (existing) existing.enabled = enabled
    else grants.value.push({ userId: selectedUserId.value, capabilityCode: row.capabilityCode, enabled })
    notifySuccess(`已${enabled ? '授予' : '撤销'}「${row.displayName}」`)
  } catch (error) {
    notifyError(error, '工具授权变更失败')
  } finally {
    busyCode.value = ''
  }
}

function onToggle(row: GrantRow, value: string | number | boolean) {
  void toggle(row, Boolean(value))
}

function userLabel(user: UserDirectoryEntry): string {
  return `${user.userId} · ${user.mobileMasked}`
}

function typeLabel(type: Capability['type']): string {
  return type === 'MCP' ? 'MCP 工具' : type === 'TOOL' ? '本地工具' : type
}

function validateReason(value: string): boolean | string {
  const text = String(value ?? '').trim()
  if (!text) return '操作原因不能为空'
  if (text.length > 500) return '操作原因不能超过 500 个字符'
  return true
}

onMounted(refresh)
</script>

<style scoped>
.block { margin-bottom: 18px; }
.toolbar { display: flex; gap: 10px; }
</style>

<template>
  <section>
    <div class="page-head">
      <div>
        <h3>工具授权</h3>
        <p class="muted">按平台用户管理能力编码级授权；这不替代 MCP Server 对目标资源的最终判权。</p>
      </div>
      <div class="toolbar">
        <el-input v-model="capabilityKeyword" clearable placeholder="按能力名称或编码筛选" style="width: 240px" />
        <el-button @click="refresh">刷新</el-button>
      </div>
    </div>

    <el-alert type="warning" :closable="false" class="block"
              title="授予能力不等于绕过资源权限；每次实际工具调用仍由服务端重新判权。" />

    <div class="user-query block">
      <el-input v-model="userKeyword" clearable :maxlength="32" placeholder="按手机号查找用户（支持部分号码）"
                style="width: 300px" @keyup.enter="loadUsersNow" />
      <el-select v-model="userStatus" style="width: 150px" aria-label="用户状态筛选">
        <el-option label="已激活" value="ACTIVE" />
        <el-option label="全部状态" value="" />
        <el-option label="待激活" value="PENDING_ACTIVATION" />
        <el-option label="已停用" value="DISABLED" />
      </el-select>
      <el-button :loading="usersLoading" @click="loadUsersNow">查找</el-button>
      <span class="muted">用户目录每页 20 条，手机号已脱敏；搜索条件变化后保留当前选中用户。</span>
    </div>

    <el-alert v-if="usersError" type="error" :closable="false" show-icon class="block" :title="usersError">
      <template #default><el-button link type="primary" @click="loadUsersNow">重试用户查询</el-button></template>
    </el-alert>
    <el-alert v-if="selectedUser && blockedGrantUserIds.has(selectedUser.userId)" type="warning" :closable="false"
              show-icon class="block" title="该用户状态刚发生变化，已暂停新增授权；请在用户查询中核对状态。已有授权仍可撤销。" />
    <el-alert v-if="selectedPendingCount > 0" type="warning" :closable="false" show-icon class="block"
              title="部分授权请求已提交，但回读未完成；页面保留原状态，请重新核对服务端事实。" />

    <el-form label-width="90px" class="block">
      <el-form-item label="平台用户">
        <el-select :model-value="selectedUserId" filterable :loading="usersLoading" placeholder="请选择平台用户"
                   style="width: 360px" @change="onUserChanged">
          <el-option v-for="user in visibleUsers" :key="user.userId" :label="userLabel(user)" :value="user.userId" />
        </el-select>
        <span v-if="selectedUser" class="selected-status" :class="`status-${selectedUser.status.toLowerCase()}`">
          {{ statusLabel(selectedUser.status) }}
        </span>
      </el-form-item>
    </el-form>

    <div v-if="userTotal > PAGE_SIZE" class="pagination-row">
      <span class="muted">共 {{ userTotal }} 名用户</span>
      <el-pagination background layout="prev, pager, next, jumper" :page-size="PAGE_SIZE"
                     :current-page="userPageNumber" :total="userTotal" @current-change="onPageChanged" />
    </div>

    <el-alert v-if="capabilityError" type="error" :closable="false" show-icon class="block"
              :title="capabilityError">
      <template #default><el-button link type="primary" @click="loadCapabilities">重试能力目录</el-button></template>
    </el-alert>
    <el-alert v-if="grantsError" type="error" :closable="false" show-icon class="block" :title="grantsError">
      <template #default><el-button link type="primary" @click="retrySelectedGrants">重试读取授权</el-button></template>
    </el-alert>

    <el-skeleton v-if="loading || capabilitiesLoading" :rows="6" animated />
    <el-empty v-else-if="!selectedUserId" description="请选择平台用户" />
    <el-empty v-else-if="!grantsError && !filteredRows.length" description="没有匹配的能力" />
    <el-table v-else-if="!grantsError" :data="filteredRows" border stripe>
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
      <el-table-column label="用户授权" min-width="170" align="center">
        <template #default="{ row }">
          <el-switch :model-value="row.granted" :loading="isBusy(row.capabilityCode)"
                     :disabled="!canToggle(row)" @change="onToggle(row, $event)" />
          <el-button v-if="isPending(row.capabilityCode)" link type="warning"
                     :loading="isBusy(row.capabilityCode)" @click="retryGrantReadback(row.capabilityCode)">
            重新核对
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { AxiosError } from 'axios'
import * as adminApi from '../../api/admin'
import type { Capability, PlatformUserStatus, UserCapabilityGrant, UserDirectoryEntry } from '../../api/admin'
import { notifyError, notifySuccess } from '../../utils/notify'

interface GrantRow {
  capabilityCode: string
  displayName: string
  type: Capability['type']
  revisions: string
  enabled: boolean
  granted: boolean
}

interface GrantTarget {
  userId: number
  capabilityCode: string
  enabled: boolean
  generation: number
  userStatus: PlatformUserStatus
}

const PAGE_SIZE = 20
const users = ref<UserDirectoryEntry[]>([])
const capabilities = ref<Capability[]>([])
const grants = ref<UserCapabilityGrant[]>([])
const selectedUserId = ref<number | null>(null)
const selectedUserSnapshot = ref<UserDirectoryEntry | null>(null)
const userKeyword = ref('')
const userStatus = ref<PlatformUserStatus | ''>('ACTIVE')
const userPageNumber = ref(1)
const userTotal = ref(0)
const capabilityKeyword = ref('')
const usersLoading = ref(false)
const capabilitiesLoading = ref(false)
const loading = ref(false)
const usersError = ref('')
const capabilityError = ref('')
const grantsError = ref('')
const busyKeys = ref(new Set<string>())
const blockedGrantUserIds = ref(new Set<number>())
const pendingVerification = ref<Record<string, string>>({})
let userQueryGeneration = 0
let grantsRequestGeneration = 0
let grantFactsGeneration = 0
let selectionGeneration = 0
let userQueryTimer: ReturnType<typeof setTimeout> | undefined

const selectedUser = computed(() => selectedUserId.value == null ? null
  : selectedUserSnapshot.value?.userId === selectedUserId.value
    ? selectedUserSnapshot.value
    : users.value.find(user => user.userId === selectedUserId.value) ?? null)
const visibleUsers = computed(() => {
  const current = users.value
  const selected = selectedUser.value
  return selected && !current.some(user => user.userId === selected.userId) ? [selected, ...current] : current
})
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
  const key = capabilityKeyword.value.trim().toLowerCase()
  if (!key) return rows.value
  return rows.value.filter(row => row.capabilityCode.toLowerCase().includes(key)
    || row.displayName.toLowerCase().includes(key))
})
const selectedPendingCount = computed(() => selectedUserId.value == null ? 0
  : Object.keys(pendingVerification.value).filter(key => key.startsWith(`${selectedUserId.value}:`)).length)

watch([userKeyword, userStatus], () => {
  userPageNumber.value = 1
  userQueryGeneration++
  usersLoading.value = false
  if (userQueryTimer) clearTimeout(userQueryTimer)
  userQueryTimer = setTimeout(() => {
    userQueryTimer = undefined
    void loadUsers(userQueryGeneration)
  }, 300)
})

async function refresh() {
  if (userQueryTimer) {
    clearTimeout(userQueryTimer)
    userQueryTimer = undefined
  }
  await Promise.all([loadUsersNow(), loadCapabilities()])
  if (selectedUserId.value != null) await loadGrants(selectedUserId.value, selectionGeneration)
}

async function loadUsersNow() {
  if (userQueryTimer) {
    clearTimeout(userQueryTimer)
    userQueryTimer = undefined
  }
  await loadUsers()
}

async function loadUsers(expectedGeneration?: number) {
  const generation = expectedGeneration ?? ++userQueryGeneration
  usersLoading.value = true
  try {
    const page = await adminApi.listUsers({
      keyword: userKeyword.value,
      status: userStatus.value,
      limit: PAGE_SIZE,
      offset: (userPageNumber.value - 1) * PAGE_SIZE
    })
    if (generation !== userQueryGeneration) return
    users.value = page.content
    userTotal.value = page.total
    usersError.value = ''
    const selected = page.content.find(user => user.userId === selectedUserId.value)
    if (selected) {
      selectedUserSnapshot.value = selected
      blockedGrantUserIds.value.delete(selected.userId)
    }
  } catch (error) {
    if (generation === userQueryGeneration) {
      usersError.value = '用户查询失败，已保留当前列表和选中用户。'
      notifyError(error, usersError.value)
    }
  } finally {
    if (generation === userQueryGeneration) usersLoading.value = false
  }
}

function onPageChanged(page: number) {
  userPageNumber.value = page
  void loadUsers()
}

function onUserChanged(value: string | number | undefined) {
  const userId = value == null || value === '' ? null : Number(value)
  if (userId === selectedUserId.value) return
  const user = userId == null ? null : visibleUsers.value.find(entry => entry.userId === userId) ?? null
  selectedUserId.value = userId
  selectedUserSnapshot.value = user
  selectionGeneration++
  grantsRequestGeneration++
  grants.value = []
  grantsError.value = ''
  if (userId == null) {
    loading.value = false
    return
  }
  void loadGrants(userId, selectionGeneration)
}

async function loadGrants(userId: number, generation: number) {
  const request = ++grantsRequestGeneration
  const factsRequest = ++grantFactsGeneration
  loading.value = true
  grantsError.value = ''
  try {
    const current = await adminApi.userCapabilityGrants(userId)
    if (!isCurrentSelection(userId, generation) || request !== grantsRequestGeneration
      || factsRequest !== grantFactsGeneration) return
    grants.value = current
    clearPendingForUser(userId)
  } catch (error) {
    if (isCurrentSelection(userId, generation) && request === grantsRequestGeneration
      && factsRequest === grantFactsGeneration) {
      grantsError.value = '用户授权读取失败；当前授权状态保留为待核对。'
      notifyError(error, grantsError.value)
    }
  } finally {
    if (isCurrentSelection(userId, generation) && request === grantsRequestGeneration
      && factsRequest === grantFactsGeneration) loading.value = false
  }
}

async function loadCapabilities() {
  capabilitiesLoading.value = true
  try {
    capabilities.value = await adminApi.capabilities()
    capabilityError.value = ''
  } catch (error) {
    capabilityError.value = '能力目录读取失败，保留已加载目录。'
    notifyError(error, capabilityError.value)
  } finally {
    capabilitiesLoading.value = false
  }
}

function canToggle(row: GrantRow) {
  if (selectedUserId.value == null || isBusy(row.capabilityCode)) return false
  if (row.granted) return true
  return row.enabled && selectedUser.value?.status === 'ACTIVE'
    && !blockedGrantUserIds.value.has(selectedUserId.value)
}

function onToggle(row: GrantRow, value: string | number | boolean) {
  void toggle(row, Boolean(value))
}

async function toggle(row: GrantRow, enabled: boolean) {
  const user = selectedUser.value
  const userId = selectedUserId.value
  if (userId == null || !user || !canToggle(row) || (!enabled && !row.granted)) return
  const target: GrantTarget = {
    userId,
    capabilityCode: row.capabilityCode,
    enabled,
    generation: selectionGeneration,
    userStatus: user.status
  }
  const key = grantKey(target.userId, target.capabilityCode)
  if (busyKeys.value.has(key)) return
  busyKeys.value.add(key)
  try {
    const response = await ElMessageBox.prompt(
      `请输入「${enabled ? '授予' : '撤销'}」${row.displayName} 的操作原因`,
      enabled ? '授予工具能力' : '撤销工具能力',
      { inputType: 'textarea', inputPlaceholder: '操作原因（必填，最多 500 字）', inputValidator: validateReason }
    )
    const reason = String(response.value ?? '').trim()
    if (!reason) return

    try {
      await adminApi.setUserGrant(target.userId, target.capabilityCode, target.enabled, reason)
    } catch (writeError) {
      await recoverAfterUncertainWrite(target, writeError)
      return
    }

    try {
      const factsRequest = ++grantFactsGeneration
      const actual = await adminApi.userCapabilityGrants(target.userId)
      applyGrantFacts(target, actual, factsRequest)
      const actualEnabled = actual.find(item => item.capabilityCode === target.capabilityCode)?.enabled ?? false
      if (actualEnabled === target.enabled) notifySuccess(`已${target.enabled ? '授予' : '撤销'}「${row.displayName}」`)
      else ElMessage.warning('授权请求已提交，但服务端当前状态与本次请求不同；页面已刷新实际状态。')
    } catch {
      markPending(target, '已提交，授权状态待核对。')
      ElMessage.warning('已提交，状态待核对；可点击该行“重新核对”。')
    }
  } catch {
    // 管理员取消了原因输入，本次不发送请求。
  } finally {
    busyKeys.value.delete(key)
  }
}

async function recoverAfterUncertainWrite(target: GrantTarget, writeError: unknown) {
  try {
    const factsRequest = ++grantFactsGeneration
    const actual = await adminApi.userCapabilityGrants(target.userId)
    applyGrantFacts(target, actual, factsRequest)
    if (isStateConflict(writeError)) await refreshAfterStateConflict(target)
    notifyError(writeError, '授权结果未确认；已重新读取服务端当前状态，请核对后再操作。')
  } catch {
    markPending(target, '授权结果未确认，回读失败。')
    if (isStateConflict(writeError)) await refreshAfterStateConflict(target)
    notifyError(writeError, '授权结果未确认，当前显示保留且状态待核对。')
  }
}

async function refreshAfterStateConflict(target: GrantTarget) {
  if (selectedUserId.value === target.userId) blockedGrantUserIds.value.add(target.userId)
  await Promise.all([loadCapabilities(), loadUsersNow()])
  if (isCurrentSelection(target.userId, target.generation)) await loadGrants(target.userId, target.generation)
}

function applyGrantFacts(target: GrantTarget, actual: UserCapabilityGrant[], factsRequest: number) {
  if (factsRequest !== grantFactsGeneration) return
  delete pendingVerification.value[grantKey(target.userId, target.capabilityCode)]
  if (isCurrentSelection(target.userId, target.generation)) grants.value = actual
}

function markPending(target: GrantTarget, message: string) {
  pendingVerification.value[grantKey(target.userId, target.capabilityCode)] = message
}

async function retryGrantReadback(capabilityCode: string) {
  const userId = selectedUserId.value
  if (userId == null || isBusy(capabilityCode)) return
  const key = grantKey(userId, capabilityCode)
  const target: GrantTarget = {
    userId,
    capabilityCode,
    enabled: grants.value.find(item => item.capabilityCode === capabilityCode)?.enabled ?? false,
    generation: selectionGeneration,
    userStatus: selectedUser.value?.status ?? 'DISABLED'
  }
  busyKeys.value.add(key)
  try {
    const factsRequest = ++grantFactsGeneration
    const actual = await adminApi.userCapabilityGrants(userId)
    applyGrantFacts(target, actual, factsRequest)
    notifySuccess('已读取服务端当前授权状态')
  } catch (error) {
    markPending(target, '状态仍待核对。')
    notifyError(error, '授权状态仍待核对，请稍后重试')
  } finally {
    busyKeys.value.delete(key)
  }
}

async function retrySelectedGrants() {
  if (selectedUserId.value != null) await loadGrants(selectedUserId.value, selectionGeneration)
}

function isCurrentSelection(userId: number, generation: number) {
  return selectedUserId.value === userId && selectionGeneration === generation
}

function grantKey(userId: number, capabilityCode: string) {
  return `${userId}:${capabilityCode}`
}

function isBusy(capabilityCode: string) {
  return selectedUserId.value != null && busyKeys.value.has(grantKey(selectedUserId.value, capabilityCode))
}

function isPending(capabilityCode: string) {
  return selectedUserId.value != null
    && Boolean(pendingVerification.value[grantKey(selectedUserId.value, capabilityCode)])
}

function clearPendingForUser(userId: number) {
  const prefix = `${userId}:`
  for (const key of Object.keys(pendingVerification.value)) {
    if (key.startsWith(prefix)) delete pendingVerification.value[key]
  }
}

function userLabel(user: UserDirectoryEntry): string {
  return `${user.userId} · ${user.mobileMasked}`
}

function statusLabel(status: PlatformUserStatus): string {
  return status === 'ACTIVE' ? '已激活' : status === 'DISABLED' ? '已停用' : '待激活'
}

function typeLabel(type: Capability['type']): string {
  return type === 'MCP' ? 'MCP 工具' : type === 'TOOL' ? '本地工具' : type === 'SKILL' ? 'Skill' : '知识'
}

function validateReason(value: string): boolean | string {
  const text = String(value ?? '').trim()
  if (!text) return '操作原因不能为空'
  if (text.length > 500) return '操作原因不能超过 500 个字符'
  return true
}

function isStateConflict(error: unknown) {
  return (error as AxiosError | undefined)?.response?.status === 409
}

onMounted(() => {
  void loadUsersNow()
  void loadCapabilities()
})

onUnmounted(() => {
  if (userQueryTimer) clearTimeout(userQueryTimer)
})
</script>

<style scoped>
.block { margin-bottom: 18px; }
.toolbar, .user-query { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.pagination-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin: 0 0 14px; }
.selected-status { margin-left: 12px; font-size: 13px; color: var(--el-text-color-secondary); }
.status-disabled, .status-pending_activation { color: var(--el-color-warning); }
</style>

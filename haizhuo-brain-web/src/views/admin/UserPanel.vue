<template>
  <section>
    <div class="page-head">
      <div>
        <h3>用户管理</h3>
        <p class="muted">新增/启停账号、授予或撤销管理员。所有变更都会写入审计并要求填写操作原因。</p>
      </div>
      <div class="toolbar">
        <el-button type="primary" @click="openCreate">新增用户</el-button>
        <el-button @click="refresh">刷新</el-button>
      </div>
    </div>

    <el-form :inline="true" class="filters">
      <el-form-item label="手机号">
        <el-input v-model="keyword" clearable placeholder="支持模糊匹配" @keyup.enter="search" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="status" clearable placeholder="全部" style="width: 160px">
          <el-option label="待激活" value="PENDING_ACTIVATION" />
          <el-option label="已激活" value="ACTIVE" />
          <el-option label="已停用" value="DISABLED" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </el-form-item>
    </el-form>

    <el-skeleton v-if="loading" :rows="5" animated />
    <template v-else>
      <el-empty v-if="!rows.length" description="没有匹配的用户" />
      <template v-else>
        <el-table :data="rows" border stripe>
          <el-table-column prop="userId" label="用户 ID" width="100" />
          <el-table-column prop="mobileMasked" label="手机号" width="150" />
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="角色" min-width="160">
            <template #default="{ row }">
              <el-tag v-for="role in row.roles" :key="role" size="small" class="role-tag"
                      :type="role === 'PLATFORM_ADMIN' ? 'warning' : 'info'">
                {{ role }}
              </el-tag>
              <span v-if="!row.roles.length" class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="330" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="row.status !== 'PENDING_ACTIVATION'"
                         @click="reissue(row)">重发激活码</el-button>
              <el-button link type="primary" :disabled="row.status === 'PENDING_ACTIVATION'"
                         @click="changeStatus(row)">变更状态</el-button>
              <el-button v-if="row.roles.includes('PLATFORM_ADMIN')" link type="danger" @click="toggleAdmin(row, false)">
                撤销管理员
              </el-button>
              <!-- 待激活账号必须先完成激活才能被授予管理员，服务端会拒绝，这里提前禁用避免无意义的失败 -->
              <el-button v-else link type="warning" :disabled="row.status === 'PENDING_ACTIVATION'"
                         @click="toggleAdmin(row, true)">授予管理员</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="pager" layout="total, prev, pager, next" :total="total" :page-size="pageSize"
                       :current-page="currentPage" @current-change="goPage" />
      </template>
    </template>

    <!-- 新增用户 -->
    <el-dialog v-model="createVisible" title="新增用户" width="460px" @closed="resetCreate">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="90px">
        <el-form-item label="手机号" prop="mobile">
          <el-input v-model="createForm.mobile" placeholder="中国大陆手机号" />
        </el-form-item>
        <el-form-item label="操作原因" prop="reason">
          <el-input v-model="createForm.reason" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" title="创建后账号处于待激活状态，一次性激活凭据只会在创建成功时显示一次。" />
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 一次性激活凭据：离开对话框后无法再次获取，必须让用户能复制 -->
    <el-dialog v-model="credentialVisible" title="请立即保存激活凭据" width="560px">
      <el-alert type="warning" :closable="false" title="该凭据只显示一次，关闭后无法找回，请立即复制并交给用户。" />
      <el-form label-width="90px" class="credential">
        <el-form-item label="用户 ID">{{ credential.userId }}</el-form-item>
        <el-form-item label="手机号">{{ credential.mobile }}</el-form-item>
        <el-form-item label="有效期">{{ formatTime(credential.expiresAt) }}</el-form-item>
        <el-form-item label="激活凭据">
          <el-input :model-value="credential.token" readonly />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="copyToken">复制凭据</el-button>
        <el-button type="primary" @click="credentialVisible = false">我已保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import * as adminApi from '../../api/admin'
import type { PlatformUserStatus, UserDirectoryEntry } from '../../api/admin'
import { formatTime, notifyError, notifySuccess } from '../../utils/notify'

const rows = ref<UserDirectoryEntry[]>([])
const total = ref(0)
const loading = ref(false)
const keyword = ref('')
const status = ref<PlatformUserStatus | ''>('')
const currentPage = ref(1)
const pageSize = 20

const createVisible = ref(false)
const creating = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive({ mobile: '', reason: '' })

const credentialVisible = ref(false)
const credential = reactive({ userId: 0, mobile: '', token: '', expiresAt: '' })

/** 与后端 MobileNormalizer 保持一致：11 位中国大陆号码，或 +86 前缀形式。 */
const MOBILE_PATTERN = /^(\+861[3-9]\d{9}|1[3-9]\d{9})$/

const createRules: FormRules<typeof createForm> = {
  mobile: [
    { required: true, message: '请填写手机号', trigger: 'blur' },
    { pattern: MOBILE_PATTERN, message: '请填写正确的中国大陆手机号', trigger: 'blur' }
  ],
  reason: [
    { required: true, message: '请填写操作原因', trigger: 'blur' },
    { max: 500, message: '操作原因不能超过 500 个字符', trigger: 'blur' }
  ]
}

function statusLabel(value: string): string {
  return ({ PENDING_ACTIVATION: '待激活', ACTIVE: '已激活', DISABLED: '已停用' } as Record<string, string>)[value] || value
}

function statusTagType(value: string): 'success' | 'info' | 'danger' {
  return value === 'ACTIVE' ? 'success' : value === 'DISABLED' ? 'danger' : 'info'
}

async function load() {
  loading.value = true
  try {
    const page = await adminApi.listUsers({
      keyword: keyword.value,
      status: status.value,
      limit: pageSize,
      offset: (currentPage.value - 1) * pageSize
    })
    rows.value = page.content
    total.value = page.total
  } catch (error) {
    notifyError(error, '用户列表加载失败')
  } finally {
    loading.value = false
  }
}

function search() { currentPage.value = 1; load() }
function goPage(page: number) { currentPage.value = page; load() }
function refresh() { load() }
function resetFilters() { keyword.value = ''; status.value = ''; search() }

/** 所有写操作都要求非空的变更原因，这里统一收集，返回 null 表示用户取消。 */
async function askReason(title: string): Promise<string | null> {
  try {
    const prompt = await ElMessageBox.prompt('操作原因（必填，最多 500 字）', title, {
      inputType: 'textarea',
      inputPlaceholder: '请填写本次操作的原因',
      inputValidator: value => {
        const text = String(value ?? '').trim()
        if (!text) return '操作原因不能为空'
        if (text.length > 500) return '操作原因不能超过 500 个字符'
        return true
      }
    })
    return String(prompt.value ?? '').trim()
  } catch {
    return null
  }
}

function openCreate() { createVisible.value = true }
function resetCreate() { createForm.mobile = ''; createForm.reason = ''; createFormRef.value?.clearValidate() }

async function submitCreate() {
  if (!createFormRef.value) return
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) return
  creating.value = true
  try {
    const created = await adminApi.createUser(createForm.mobile.trim(), createForm.reason.trim())
    createVisible.value = false
    notifySuccess(`用户 ${created.userId} 已创建`)
    credential.userId = created.userId
    credential.mobile = created.mobileNormalized
    credential.token = created.activationToken
    credential.expiresAt = created.activationExpiresAt
    credentialVisible.value = true
    await load()
  } catch (error) {
    notifyError(error, '用户创建失败')
  } finally {
    creating.value = false
  }
}

async function copyToken() {
  try {
    await navigator.clipboard.writeText(credential.token)
    notifySuccess('已复制到剪贴板')
  } catch {
    notifyError('复制失败，请手动选中复制')
  }
}

async function reissue(row: UserDirectoryEntry) {
  const reason = await askReason(`重发用户 ${row.userId} 的激活凭据`)
  if (!reason) return
  try {
    const issued = await adminApi.reissueActivation(row.userId, reason)
    credential.userId = row.userId
    credential.mobile = row.mobileMasked
    credential.token = issued.activationToken
    credential.expiresAt = issued.expiresAt
    credentialVisible.value = true
    await load()
  } catch (error) {
    notifyError(error, '激活凭据重发失败')
  }
}

async function toggleAdmin(row: UserDirectoryEntry, grant: boolean) {
  const title = grant ? `授予用户 ${row.userId} 管理员角色` : `撤销用户 ${row.userId} 的管理员角色`
  const reason = await askReason(title)
  if (!reason) return
  try {
    if (grant) await adminApi.grantAdmin(row.userId, reason)
    else await adminApi.revokeAdmin(row.userId, reason)
    notifySuccess(grant ? '已授予管理员角色' : '已撤销管理员角色')
    await load()
  } catch (error) {
    notifyError(error, grant ? '授予管理员失败' : '撤销管理员失败')
  }
}

async function changeStatus(row: UserDirectoryEntry) {
  // 后端不允许改回 PENDING_ACTIVATION，也不能操作待激活账号，因此目标状态只有「反向切换」一种。
  if (row.status === 'PENDING_ACTIVATION') {
    ElMessage.warning('待激活账号不能通过启停接口变更状态')
    return
  }
  const next: PlatformUserStatus = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  const reason = await askReason(`将用户 ${row.userId} 变更为「${statusLabel(next)}」`)
  if (!reason) return
  try {
    await adminApi.changeUserStatus(row.userId, next, reason)
    notifySuccess(`已${next === 'ACTIVE' ? '启用' : '停用'}账号 ${row.userId}`)
    await load()
  } catch (error) {
    notifyError(error, '状态变更失败')
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; gap: 10px; }
.filters { margin-bottom: 8px; }
.role-tag { margin-right: 6px; }
.pager { margin-top: 16px; justify-content: flex-end; }
.credential { margin-top: 16px; }
</style>

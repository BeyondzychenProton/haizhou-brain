<template>
  <section class="channel-page">
    <header class="page-head">
      <div>
        <h2>渠道管理</h2>
        <p class="muted">账号配置、外部身份、当前实例观测与投递事实核查。投递状态只读，不会触发 Run 或重新发送。</p>
      </div>
    </header>

    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <el-tab-pane label="账号与身份" name="accounts">
        <div class="section-head">
          <div>
            <h3>渠道账号</h3>
            <p class="muted">凭据仅显示引用；多用户账号默认采用 PER_PEER 隔离。</p>
          </div>
          <el-button type="primary" @click="openCreate">新增账号</el-button>
        </div>
        <el-alert v-if="accountError" type="error" :closable="false" show-icon :title="accountError" />
        <el-skeleton v-if="accountsLoading && !accounts.length" :rows="4" animated />
        <el-empty v-else-if="!accounts.length" description="还没有渠道账号" />
        <el-table v-else v-loading="accountsLoading" :data="accounts" row-key="bindingId" stripe>
          <el-table-column prop="bindingId" label="绑定 ID" min-width="140" />
          <el-table-column prop="provider" label="渠道" min-width="110" />
          <el-table-column prop="externalAccountKey" label="外部账号" min-width="150" />
          <el-table-column prop="credentialRef" label="凭据引用" min-width="150" />
          <el-table-column label="默认员工" min-width="120">
            <template #default="scope">{{ employeeName(scope.row.defaultEmployeeId) }}</template>
          </el-table-column>
          <el-table-column label="会话隔离" min-width="160">
            <template #default="scope">{{ scopeLabel(scope.row.sessionScope) }}</template>
          </el-table-column>
          <el-table-column label="配置状态" width="110">
            <template #default="scope">
              <el-tag :type="scope.row.enabled ? 'success' : 'info'">
                {{ scope.row.enabled ? '已启用' : '已停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="revision" label="配置修订" width="100" />
          <el-table-column label="最近装载观测" min-width="180">
            <template #default="scope">{{ runtimeSummary(scope.row.bindingId) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="scope">
              <el-button link type="primary" @click="selectAccount(scope.row)">身份绑定</el-button>
              <el-button link @click="openEdit(scope.row)">编辑配置</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="identity-section">
          <div class="section-head">
            <div>
              <h3>外部身份绑定</h3>
              <p class="muted">命令有原因和幂等回执；首版没有身份 revision CAS，遇到冲突请回读后再操作。</p>
            </div>
            <el-select v-model="selectedBindingId" placeholder="选择渠道账号" clearable filterable
                       style="width: 260px" @change="loadIdentities">
              <el-option v-for="account in accounts" :key="account.bindingId" :value="account.bindingId"
                         :label="`${account.bindingId} · ${account.provider}`" />
            </el-select>
          </div>
          <el-alert v-if="identityError" type="error" :closable="false" show-icon :title="identityError" />
          <el-empty v-if="!selectedBindingId" description="先选择账号查看身份绑定" />
          <el-skeleton v-else-if="identitiesLoading && !identities.length" :rows="3" animated />
          <el-empty v-else-if="!identities.length" description="该账号还没有身份绑定" />
          <el-table v-else v-loading="identitiesLoading" :data="identities" row-key="externalUserId" stripe>
            <el-table-column prop="externalUserId" label="外部用户 ID" min-width="180" />
            <el-table-column label="平台用户" min-width="210">
              <template #default="scope">用户 #{{ scope.row.userId }} · {{ userLabel(scope.row.userId) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="scope">
                <el-tag :type="scope.row.state === 'LINKED' ? 'success' : 'info'">
                  {{ scope.row.state === 'LINKED' ? '已绑定' : '已撤销' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="绑定时间" min-width="170">
              <template #default="scope">{{ formatTime(scope.row.linkedAt) }}</template>
            </el-table-column>
            <el-table-column label="更新时间" min-width="170">
              <template #default="scope">{{ formatTime(scope.row.updatedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="scope">
                <el-button v-if="scope.row.state === 'LINKED'" link type="danger"
                           @click="revokeIdentity(scope.row)">撤销</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button v-if="selectedBindingId" class="identity-add" @click="identityDialog = true">绑定外部用户</el-button>
        </div>
      </el-tab-pane>

      <el-tab-pane label="运行时" name="runtime">
        <div class="section-head">
          <div>
            <h3>当前实例观测</h3>
            <p class="muted">显示当前实例实际接受的账号配置修订；不代表集群中其他实例已同步。</p>
          </div>
          <el-button :loading="runtimeLoading" @click="loadRuntime">刷新观测</el-button>
        </div>
        <el-alert v-if="runtimeError" type="error" :closable="false" show-icon :title="runtimeError" />
        <el-skeleton v-if="runtimeLoading && !runtimeChannels.length" :rows="3" animated />
        <el-empty v-else-if="!runtimeChannels.length" description="当前没有可观测的已装载渠道" />
        <el-table v-else v-loading="runtimeLoading" :data="runtimeChannels" row-key="channelId" stripe>
          <el-table-column prop="channelId" label="运行时渠道" min-width="140" />
          <el-table-column prop="defaultAgentId" label="默认员工" min-width="150" />
          <el-table-column label="隔离方式" min-width="170">
            <template #default="scope">{{ scopeLabel(scope.row.sessionScope) }}</template>
          </el-table-column>
          <el-table-column prop="bindingCount" label="账号数" width="100" />
          <el-table-column prop="instanceLabel" label="实例标识" min-width="160" />
          <el-table-column label="实例状态" width="130">
            <template #default="scope">
              <el-tag :type="scope.row.started ? 'success' : 'warning'">
                {{ scope.row.started ? '已启动' : '未启动' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="核对说明" min-width="280">
            <template #default="scope">
              <div v-for="snapshot in scope.row.accountSnapshots" :key="snapshot.bindingId">
                {{ snapshot.bindingId }}：配置 r{{ snapshot.configuredRevision }} / 装载
                {{ snapshot.loadedRevision == null ? '未知' : `r${snapshot.loadedRevision}` }} · {{ loadStateLabel(snapshot.loadState) }}
              </div>
              <span class="muted">观测 {{ formatTime(scope.row.observedAt) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="投递核查（只读）" name="deliveries">
        <div class="filter-grid">
          <el-select v-model="deliveryFilter.state" clearable placeholder="全部状态" style="width: 190px">
            <el-option v-for="state in deliveryStates" :key="state.value" :label="state.label" :value="state.value" />
          </el-select>
          <el-input v-model="deliveryFilter.bindingId" clearable placeholder="绑定 ID" />
          <el-input v-model="deliveryFilter.provider" clearable placeholder="渠道" />
          <el-input v-model="deliveryFilter.runId" clearable placeholder="Run ID" />
          <el-input v-model="deliveryFromLocal" clearable type="datetime-local" step="1" aria-label="开始时间" />
          <el-input v-model="deliveryToLocal" clearable type="datetime-local" step="1" aria-label="结束时间" />
          <el-button type="primary" :loading="deliveriesLoading" @click="applyDeliveryFilters">查询</el-button>
          <el-button :loading="deliveriesLoading" @click="loadDeliveries">刷新</el-button>
        </div>
        <el-alert v-if="deliveryError" type="error" :closable="false" show-icon :title="deliveryError" />
        <el-alert type="info" :closable="false" show-icon
                  title="UNCERTAIN 仅等待外部事实核查。本页不提供重发、确认送达或确认未送达操作。" />
        <el-skeleton v-if="deliveriesLoading && !deliveryPage" :rows="4" animated />
        <el-empty v-else-if="deliveryPage && !deliveryPage.items.length" description="没有匹配的投递记录" />
        <el-table v-else-if="deliveryPage" v-loading="deliveriesLoading" :data="deliveryPage.items" row-key="deliveryId" stripe>
          <el-table-column prop="deliveryId" label="投递 ID" min-width="190" />
          <el-table-column prop="runId" label="Run ID" min-width="190" />
          <el-table-column prop="bindingId" label="绑定 ID" min-width="130" />
          <el-table-column prop="provider" label="渠道" width="120" />
          <el-table-column label="投递状态" width="155">
            <template #default="scope">
              <el-tag :type="deliveryTag(scope.row.state)">{{ deliveryStateLabel(scope.row.state) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="Run 状态" width="130">
            <template #default="scope">{{ runStateById[scope.row.deliveryId] || '查看详情' }}</template>
          </el-table-column>
          <el-table-column prop="attempts" label="尝试次数" width="100" />
          <el-table-column label="证据来源" width="130">
            <template #default="scope">{{ scope.row.evidenceSource === 'SIMULATED' ? '模拟发送' : '未验证' }}</template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="175">
            <template #default="scope">{{ formatTime(scope.row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="scope"><el-button link type="primary" @click="openDelivery(scope.row)">详情</el-button></template>
          </el-table-column>
        </el-table>
        <div class="page-controls">
          <span class="muted">每页 20 条 · 时间范围按首次查询时刻固定</span>
          <div>
            <el-button :disabled="deliveryPageIndex === 0 || deliveriesLoading" @click="previousDeliveryPage">上一页</el-button>
            <el-button :disabled="!deliveryPage?.hasMore || deliveriesLoading" @click="nextDeliveryPage">下一页</el-button>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="accountDialog" :title="editingAccount ? '编辑渠道账号' : '新增渠道账号'" width="560px" destroy-on-close>
      <el-form ref="accountFormRef" :model="accountForm" :rules="accountRules" label-width="130px">
        <el-form-item v-if="!editingAccount" label="绑定 ID" prop="bindingId">
          <el-input v-model="accountForm.bindingId" maxlength="64" />
        </el-form-item>
        <el-form-item v-if="!editingAccount" label="渠道标识" prop="provider">
          <el-input v-model="accountForm.provider" maxlength="32" placeholder="例如：simulated" />
        </el-form-item>
        <el-form-item v-if="!editingAccount" label="外部账号" prop="externalAccountKey">
          <el-input v-model="accountForm.externalAccountKey" maxlength="128" />
        </el-form-item>
        <el-form-item v-if="!editingAccount" label="凭据引用" prop="credentialRef">
          <el-input v-model="accountForm.credentialRef" maxlength="256" placeholder="仅填写受控引用，不要粘贴密钥" />
        </el-form-item>
        <el-form-item label="默认员工" prop="defaultEmployeeId">
          <el-select v-model="accountForm.defaultEmployeeId" placeholder="选择已发布员工" filterable style="width: 100%">
            <el-option v-for="employee in employees" :key="employee.employeeId"
                       :label="`${employee.displayName} (#${employee.employeeId})`"
                       :value="employee.employeeId" :disabled="!employee.enabled || !employee.published" />
          </el-select>
        </el-form-item>
        <el-form-item label="会话隔离" prop="sessionScope">
          <el-select v-model="accountForm.sessionScope" style="width: 100%">
            <el-option v-if="editingAccount?.sessionScope === 'MAIN'" label="MAIN（共享上下文，旧配置）" value="MAIN" disabled />
            <el-option label="PER_PEER（按外部用户隔离）" value="PER_PEER" />
            <el-option label="PER_CHANNEL_PEER" value="PER_CHANNEL_PEER" />
            <el-option label="PER_ACCOUNT_CHANNEL_PEER" value="PER_ACCOUNT_CHANNEL_PEER" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editingAccount" label="账号状态">
          <el-switch v-model="accountForm.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="变更原因" prop="reason">
          <el-input v-model="accountForm.reason" type="textarea" maxlength="500" show-word-limit
                    :rows="3" placeholder="说明本次账号配置变更原因" />
        </el-form-item>
        <el-alert v-if="!editingAccount" type="info" :closable="false"
                  title="管理范围固定为当前租户；默认采用 PER_PEER。保存后请到运行时页核对本实例观测。" />
      </el-form>
      <template #footer>
        <el-button @click="accountDialog = false">取消</el-button>
        <el-button type="primary" :loading="accountSaving" @click="saveAccount">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="identityDialog" title="绑定外部用户" width="520px" destroy-on-close>
      <el-form label-width="120px">
        <el-form-item label="外部用户 ID">
          <el-input v-model="identityForm.externalUserId" maxlength="128" placeholder="按渠道原样填写" />
        </el-form-item>
        <el-form-item label="平台用户">
          <el-select v-model="identityForm.userId" filterable remote reserve-keyword :remote-method="searchUsers"
                     :loading="usersLoading" placeholder="按手机号或用户 ID 搜索" style="width: 100%">
            <el-option v-for="user in users" :key="user.userId" :value="user.userId"
                       :label="`#${user.userId} · ${user.mobileMasked} · ${user.status}`"
                       :disabled="user.status !== 'ACTIVE'" />
          </el-select>
        </el-form-item>
        <el-form-item label="绑定原因">
          <el-input v-model="identityForm.reason" type="textarea" maxlength="500" show-word-limit
                    :rows="3" placeholder="说明本次身份绑定原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="identityDialog = false">取消</el-button>
        <el-button type="primary" :loading="identitySaving" @click="linkIdentity">确认绑定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="deliveryDialog" title="投递事实详情（只读）" width="760px">
      <el-skeleton v-if="deliveryDetailLoading" :rows="5" animated />
      <el-alert v-else-if="deliveryDetailError" type="error" :closable="false" show-icon :title="deliveryDetailError" />
      <template v-else-if="deliveryDetail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="Delivery ID">{{ deliveryDetail.summary.deliveryId }}</el-descriptions-item>
          <el-descriptions-item label="Run ID">{{ deliveryDetail.summary.runId }}</el-descriptions-item>
          <el-descriptions-item label="Session ID">{{ deliveryDetail.summary.sessionId || '未关联' }}</el-descriptions-item>
          <el-descriptions-item label="渠道账号">{{ deliveryDetail.summary.bindingId }}</el-descriptions-item>
          <el-descriptions-item label="Run 状态">{{ deliveryDetail.runState || '未关联/未观测' }}</el-descriptions-item>
          <el-descriptions-item label="Delivery 状态">{{ deliveryStateLabel(deliveryDetail.summary.state) }}</el-descriptions-item>
          <el-descriptions-item label="尝试次数">{{ deliveryDetail.summary.attempts }}</el-descriptions-item>
          <el-descriptions-item label="事实修订">r{{ deliveryDetail.summary.revision }}</el-descriptions-item>
          <el-descriptions-item label="尝试历史">
            {{ deliveryDetail.attemptsHistoryState === 'LEGACY_UNRECORDED' ? '旧数据未记录逐次历史'
              : deliveryDetail.attemptsHistoryState === 'PARTIAL' ? '仅显示已有/最近 100 次记录'
                : '已记录' }}
          </el-descriptions-item>
          <el-descriptions-item label="证据来源">{{ deliveryDetail.summary.evidenceSource === 'SIMULATED' ? '模拟发送' : '未验证真实提供方' }}</el-descriptions-item>
          <el-descriptions-item label="安全错误码">{{ deliveryDetail.summary.lastErrorCode || '无' }}</el-descriptions-item>
          <el-descriptions-item label="外部消息 ID">{{ deliveryDetail.summary.externalMessageId || '无' }}</el-descriptions-item>
          <el-descriptions-item label="安全摘要" :span="2">{{ deliveryDetail.summary.contentPreview }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(deliveryDetail.summary.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatTime(deliveryDetail.summary.updatedAt) }}</el-descriptions-item>
        </el-descriptions>
        <el-table v-if="deliveryDetail.attemptsHistory.length" class="attempt-history" :data="deliveryDetail.attemptsHistory" row-key="attemptNo" size="small" border>
          <el-table-column prop="attemptNo" label="尝试" width="80" />
          <el-table-column prop="claimGeneration" label="认领代次" width="105" />
          <el-table-column prop="status" label="结果" width="165" />
          <el-table-column prop="safeErrorCode" label="安全错误码" min-width="155">
            <template #default="scope">{{ scope.row.safeErrorCode || '—' }}</template>
          </el-table-column>
          <el-table-column label="开始时间" min-width="170">
            <template #default="scope">{{ formatTime(scope.row.startedAt) }}</template>
          </el-table-column>
          <el-table-column label="结束时间" min-width="170">
            <template #default="scope">{{ scope.row.finishedAt ? formatTime(scope.row.finishedAt) : '进行中' }}</template>
          </el-table-column>
        </el-table>
        <el-alert class="detail-note" type="warning" :closable="false" show-icon
                  title="本次只读取数据库事实；不读取回调原文、投递目标或密钥，不执行重新发送或状态改写。" />
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { listUsers, type UserDirectoryEntry } from '../../api/admin'
import {
  createChannelAccount, getChannelDelivery, linkChannelIdentity, listChannelAccounts,
  listChannelDeliveries, listChannelIdentities, listPublishedChannelEmployees, listRuntimeChannels, revokeChannelIdentity,
  updateChannelAccount, type ChannelAccount, type ChannelIdentity, type DeliveryDetail,
  type ChannelEmployeeOption, type DeliveryPage, type DeliverySummary, type DeliveryQuery, type RuntimeChannel,
} from '../../api/channels'

type TabName = 'accounts' | 'runtime' | 'deliveries'
const activeTab = ref<TabName>('accounts')
const accounts = ref<ChannelAccount[]>([])
const employees = ref<ChannelEmployeeOption[]>([])
const users = ref<UserDirectoryEntry[]>([])
const runtimeChannels = ref<RuntimeChannel[]>([])
const identities = ref<ChannelIdentity[]>([])
const selectedBindingId = ref('')
const accountsLoading = ref(false)
const runtimeLoading = ref(false)
const identitiesLoading = ref(false)
const usersLoading = ref(false)
const accountSaving = ref(false)
const identitySaving = ref(false)
const deliveriesLoading = ref(false)
const deliveryDetailLoading = ref(false)
const accountError = ref('')
const identityError = ref('')
const runtimeError = ref('')
const deliveryError = ref('')
const deliveryDetailError = ref('')
const accountDialog = ref(false)
const identityDialog = ref(false)
const deliveryDialog = ref(false)
const editingAccount = ref<ChannelAccount | null>(null)
const accountFormRef = ref<FormInstance>()
const deliveryPage = ref<DeliveryPage | null>(null)
const deliveryDetail = ref<DeliveryDetail | null>(null)
const deliveryFilter = reactive({ state: '', bindingId: '', provider: '', runId: '' })
const deliveryFromLocal = ref('')
const deliveryToLocal = ref('')
const deliveryCursors = ref<Array<string | undefined>>([undefined])
const deliveryPageIndex = ref(0)
const runStateById = reactive<Record<string, string>>({})
const identityForm = reactive({ externalUserId: '', userId: undefined as number | undefined, reason: '' })
const accountForm = reactive({
  bindingId: '', provider: '', externalAccountKey: '', credentialRef: '',
  defaultEmployeeId: undefined as number | undefined,
  sessionScope: 'PER_PEER' as ChannelAccount['sessionScope'], enabled: true, reason: '',
})
const accountRules: FormRules = {
  bindingId: [{ required: true, message: '请输入绑定 ID', trigger: 'blur' }],
  provider: [{ required: true, message: '请输入渠道标识', trigger: 'blur' }],
  externalAccountKey: [{ required: true, message: '请输入外部账号标识', trigger: 'blur' }],
  credentialRef: [{ required: true, message: '请输入凭据引用', trigger: 'blur' }],
  defaultEmployeeId: [{ required: true, message: '请选择默认员工', trigger: 'change' }],
  sessionScope: [{ required: true, message: '请选择会话隔离方式', trigger: 'change' }],
  reason: [{ required: true, message: '请填写变更原因', trigger: 'blur' }],
}
const deliveryStates = [
  { value: 'PENDING', label: '待投递' }, { value: 'SENDING', label: '发送中' },
  { value: 'DELIVERED', label: '已送达' }, { value: 'RETRYABLE_FAILURE', label: '可重试失败' },
  { value: 'PERMANENT_FAILURE', label: '永久失败' }, { value: 'UNCERTAIN', label: '待外部核查' },
]
const deliveryStateLabel = (state: string) => deliveryStates.find(item => item.value === state)?.label || state
const scopeLabel = (scope: string) => ({
  MAIN: 'MAIN（共享上下文）', PER_PEER: 'PER_PEER（按用户）',
  PER_CHANNEL_PEER: 'PER_CHANNEL_PEER', PER_ACCOUNT_CHANNEL_PEER: 'PER_ACCOUNT_CHANNEL_PEER',
} as Record<string, string>)[scope] || scope
const deliveryTag = (state: string) => state === 'DELIVERED' ? 'success'
  : state === 'UNCERTAIN' || state === 'PERMANENT_FAILURE' ? 'danger'
    : state === 'SENDING' ? 'warning' : 'info'
const formatTime = (value?: string | null) => value ? new Date(value).toLocaleString() : '—'
const employeeName = (id: number) => employees.value.find(employee => employee.employeeId === id)?.displayName || `#${id}`
const userLabel = (id: number) => users.value.find(user => user.userId === id)?.mobileMasked || '平台用户'
const runtimeSummary = (bindingId: string) => {
  const snapshot = runtimeChannels.value.flatMap(channel => channel.accountSnapshots || [])
    .find(candidate => candidate.bindingId === bindingId)
  if (!snapshot) return '未观测'
  return `配置 r${snapshot.configuredRevision} / 装载 ${snapshot.loadedRevision == null ? '未知' : `r${snapshot.loadedRevision}`} · ${loadStateLabel(snapshot.loadState)}`
}
const loadStateLabel = (state: string) => ({
  LOADED: '已装载', STALE: '装载版本较旧', UNKNOWN: '未知', UNLOADED: '未装载',
} as Record<string, string>)[state] || state
const refreshLabel = (status?: string) => status === 'APPLIED' ? '当前实例已装载'
  : status === 'FAILED' ? '保存成功，运行时刷新失败'
    : '保存成功，运行时装载待核查'
function requestIdFor(kind: 'account' | 'identity', fingerprint: string) {
  const pending = kind === 'account' ? pendingAccountCommand : pendingIdentityCommand
  if (pending?.fingerprint === fingerprint) return pending.requestId
  const requestId = globalThis.crypto?.randomUUID?.()
    || `${Date.now()}-${Math.random().toString(36).slice(2)}`
  const next = { fingerprint, requestId }
  if (kind === 'account') pendingAccountCommand = next
  else pendingIdentityCommand = next
  return requestId
}
const pageQuery = computed<DeliveryQuery>(() => ({
  state: deliveryFilter.state || undefined,
  bindingId: deliveryFilter.bindingId.trim() || undefined,
  provider: deliveryFilter.provider.trim() || undefined,
  runId: deliveryFilter.runId.trim() || undefined,
  createdFrom: deliveryFromLocal.value ? new Date(deliveryFromLocal.value).toISOString() : undefined,
  createdTo: deliveryToLocal.value ? new Date(deliveryToLocal.value).toISOString() : undefined,
  limit: 20,
}))

let accountsGeneration = 0
let identitiesGeneration = 0
let runtimeGeneration = 0
let deliveryGeneration = 0
let detailGeneration = 0
let pageMounted = false
let pendingAccountCommand: { fingerprint: string; requestId: string } | null = null
let pendingIdentityCommand: { fingerprint: string; requestId: string } | null = null

onMounted(async () => {
  pageMounted = true
  await Promise.all([loadAccounts(), loadEmployees(), loadRuntime()])
})
onBeforeUnmount(() => {
  pageMounted = false
  accountsGeneration++
  identitiesGeneration++
  runtimeGeneration++
  deliveryGeneration++
  detailGeneration++
})

function onTabChange(name: string | number) {
  activeTab.value = name as TabName
  if (name === 'runtime') void loadRuntime()
  if (name === 'deliveries' && !deliveryPage.value) void loadDeliveries()
}

async function loadAccounts() {
  const generation = ++accountsGeneration
  accountsLoading.value = true
  accountError.value = ''
  try {
    const value = await listChannelAccounts()
    if (!pageMounted || generation !== accountsGeneration) return
    accounts.value = value
  } catch {
    if (pageMounted && generation === accountsGeneration) accountError.value = '账号列表读取失败，请检查权限或依赖后重试。'
  } finally {
    if (pageMounted && generation === accountsGeneration) accountsLoading.value = false
  }
}

async function loadEmployees() {
  try { employees.value = await listPublishedChannelEmployees() }
  catch { employees.value = [] }
}

async function loadRuntime() {
  const generation = ++runtimeGeneration
  runtimeLoading.value = true
  runtimeError.value = ''
  try {
    const value = await listRuntimeChannels()
    if (!pageMounted || generation !== runtimeGeneration) return
    runtimeChannels.value = value
  } catch {
    if (pageMounted && generation === runtimeGeneration) runtimeError.value = '运行时状态暂不可用；不代表账号配置已丢失。'
  } finally {
    if (pageMounted && generation === runtimeGeneration) runtimeLoading.value = false
  }
}

async function selectAccount(account: ChannelAccount) {
  selectedBindingId.value = account.bindingId
  await loadIdentities(account.bindingId)
}

async function loadIdentities(bindingId = selectedBindingId.value) {
  const generation = ++identitiesGeneration
  identities.value = []
  identityError.value = ''
  if (!bindingId) return
  identitiesLoading.value = true
  try {
    const value = await listChannelIdentities(bindingId)
    if (!pageMounted || generation !== identitiesGeneration || selectedBindingId.value !== bindingId) return
    identities.value = value
  } catch {
    if (pageMounted && generation === identitiesGeneration) identityError.value = '身份绑定读取失败，请刷新账号后重试。'
  } finally {
    if (pageMounted && generation === identitiesGeneration) identitiesLoading.value = false
  }
}

function openCreate() {
  editingAccount.value = null
  Object.assign(accountForm, {
    bindingId: '', provider: '', externalAccountKey: '', credentialRef: '',
    defaultEmployeeId: undefined, sessionScope: 'PER_PEER', enabled: true, reason: '',
  })
  accountDialog.value = true
}

function openEdit(account: ChannelAccount) {
  editingAccount.value = account
  Object.assign(accountForm, {
    bindingId: account.bindingId, provider: account.provider, externalAccountKey: account.externalAccountKey,
    credentialRef: account.credentialRef, defaultEmployeeId: account.defaultEmployeeId,
    sessionScope: account.sessionScope, enabled: account.enabled, reason: '',
  })
  accountDialog.value = true
}

async function saveAccount() {
  if (!accountFormRef.value || accountSaving.value) return
  try { await accountFormRef.value.validate() } catch { return }
  accountSaving.value = true
  try {
    const reason = accountForm.reason.trim()
    const fingerprint = JSON.stringify({
      bindingId: accountForm.bindingId.trim(), provider: accountForm.provider.trim(),
      externalAccountKey: accountForm.externalAccountKey.trim(), credentialRef: accountForm.credentialRef.trim(),
      defaultEmployeeId: accountForm.defaultEmployeeId, sessionScope: accountForm.sessionScope,
      enabled: accountForm.enabled, expectedRevision: editingAccount.value?.revision ?? null, reason,
    })
    const requestId = requestIdFor('account', fingerprint)
    let result: ChannelAccount
    if (editingAccount.value) {
      result = await updateChannelAccount(editingAccount.value.bindingId, {
        enabled: accountForm.enabled,
        defaultEmployeeId: accountForm.defaultEmployeeId,
        sessionScope: accountForm.sessionScope,
        expectedRevision: editingAccount.value.revision,
        requestId,
        reason,
      })
    } else {
      result = await createChannelAccount({
        bindingId: accountForm.bindingId.trim(), provider: accountForm.provider.trim(),
        externalAccountKey: accountForm.externalAccountKey.trim(), credentialRef: accountForm.credentialRef.trim(),
        defaultEmployeeId: accountForm.defaultEmployeeId!, sessionScope: accountForm.sessionScope,
        enabled: accountForm.enabled, tenantId: 1, requestId, reason,
      })
    }
    pendingAccountCommand = null
    ElMessage.success(refreshLabel(result.runtimeRefresh?.status))
    accountDialog.value = false
    await Promise.all([loadAccounts(), loadRuntime()])
  } catch (error) {
    const response = (error as { response?: { status?: number; data?: { code?: string } } })?.response
    if (response?.status === 409 && response.data?.code === 'CHANNEL_ACCOUNT_CHANGED') {
      await Promise.all([loadAccounts(), loadRuntime()])
      accountDialog.value = false
      ElMessage.warning('账号已被其他管理员修改，最新配置已回读；请重新打开账号并核对后再编辑。')
    } else {
      ElMessage.error('保存失败。请检查当前账号状态后重试。')
    }
  } finally { accountSaving.value = false }
}

async function searchUsers(keyword: string) {
  if (!keyword.trim()) { users.value = []; return }
  usersLoading.value = true
  try {
    const page = await listUsers({ keyword: keyword.trim(), status: 'ACTIVE', limit: 20, offset: 0 })
    if (pageMounted) users.value = page.content
  } catch { users.value = [] }
  finally { usersLoading.value = false }
}

async function linkIdentity() {
  const bindingId = selectedBindingId.value
  const external = identityForm.externalUserId.trim()
  const reason = identityForm.reason.trim()
  if (!bindingId || !external || !identityForm.userId || !reason || identitySaving.value) {
    ElMessage.warning('请填写外部用户 ID、有效的平台用户和绑定原因。')
    return
  }
  identitySaving.value = true
  try {
    const fingerprint = JSON.stringify({ action: 'LINK_IDENTITY', bindingId, external,
      userId: identityForm.userId, reason })
    const requestId = requestIdFor('identity', fingerprint)
    await linkChannelIdentity(bindingId, external, identityForm.userId, { requestId, reason })
    pendingIdentityCommand = null
    identityDialog.value = false
    identityForm.externalUserId = ''
    identityForm.userId = undefined
    identityForm.reason = ''
    await loadIdentities(bindingId)
    ElMessage.success('身份已绑定并回读确认。')
  } catch { ElMessage.error('绑定失败；请确认渠道启用且目标用户有效。') }
  finally { identitySaving.value = false }
}

async function revokeIdentity(identity: ChannelIdentity) {
  const bindingId = selectedBindingId.value
  try {
    const prompt = await ElMessageBox.prompt(
      `将撤销渠道账号 ${bindingId} 下外部用户 ${identity.externalUserId} → 平台用户 #${identity.userId} 的绑定。之后的入站消息不再解析为该用户。`,
      '确认撤销身份绑定', {
        type: 'warning', confirmButtonText: '撤销绑定', cancelButtonText: '取消',
        inputPlaceholder: '请说明撤销原因', inputType: 'textarea',
        inputPattern: /\S/, inputErrorMessage: '请填写撤销原因',
      },
    )
    const reason = prompt.value.trim()
    const fingerprint = JSON.stringify({ action: 'REVOKE_IDENTITY', bindingId,
      externalUserId: identity.externalUserId, reason })
    const requestId = requestIdFor('identity', fingerprint)
    await revokeChannelIdentity(bindingId, identity.externalUserId, { requestId, reason })
    pendingIdentityCommand = null
    await loadIdentities(bindingId)
    ElMessage.success('已撤销，并从服务端回读最新状态。')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error('撤销失败，请刷新状态后重试。')
  }
}

function applyDeliveryFilters() {
  deliveryCursors.value = [undefined]
  deliveryPageIndex.value = 0
  deliveryPage.value = null
  void loadDeliveries()
}

async function loadDeliveries() {
  const generation = ++deliveryGeneration
  const cursor = deliveryCursors.value[deliveryPageIndex.value]
  deliveriesLoading.value = true
  deliveryError.value = ''
  try {
    const page = await listChannelDeliveries({ ...pageQuery.value, cursor })
    if (!pageMounted || generation !== deliveryGeneration) return
    deliveryPage.value = page
    for (const item of page.items) delete runStateById[item.deliveryId]
  } catch {
    if (pageMounted && generation === deliveryGeneration) deliveryError.value = '投递查询失败；请检查筛选条件或权限后重试。'
  } finally {
    if (pageMounted && generation === deliveryGeneration) deliveriesLoading.value = false
  }
}

function nextDeliveryPage() {
  if (!deliveryPage.value?.hasMore || !deliveryPage.value.nextCursor) return
  deliveryCursors.value = [...deliveryCursors.value.slice(0, deliveryPageIndex.value + 1), deliveryPage.value.nextCursor]
  deliveryPageIndex.value++
  void loadDeliveries()
}

function previousDeliveryPage() {
  if (deliveryPageIndex.value < 1) return
  deliveryPageIndex.value--
  void loadDeliveries()
}

async function openDelivery(item: DeliverySummary) {
  const generation = ++detailGeneration
  deliveryDialog.value = true
  deliveryDetail.value = null
  deliveryDetailError.value = ''
  deliveryDetailLoading.value = true
  try {
    const detail = await getChannelDelivery(item.deliveryId)
    if (!pageMounted || generation !== detailGeneration) return
    deliveryDetail.value = detail
    runStateById[item.deliveryId] = detail.runState || '未关联/未观测'
  } catch {
    if (pageMounted && generation === detailGeneration) deliveryDetailError.value = '投递详情读取失败，请保留筛选后重试。'
  } finally {
    if (pageMounted && generation === detailGeneration) deliveryDetailLoading.value = false
  }
}
</script>

<style scoped>
.channel-page { display: flex; flex-direction: column; gap: 16px; }
.page-head, .section-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.page-head h2, .section-head h3 { margin: 0 0 6px; }
.section-head { margin: 4px 0 14px; }
.section-head p, .page-head p { margin: 0; }
.identity-section { margin-top: 30px; border-top: 1px solid var(--el-border-color); padding-top: 20px; }
.identity-add { margin-top: 12px; }
.filter-grid { display: grid; grid-template-columns: repeat(4, minmax(150px, 1fr)); gap: 10px; margin-bottom: 14px; }
.page-controls { display: flex; align-items: center; justify-content: space-between; margin-top: 14px; }
.detail-note { margin-top: 16px; }
@media (max-width: 900px) { .filter-grid { grid-template-columns: repeat(2, minmax(140px, 1fr)); } }
@media (max-width: 600px) { .filter-grid { grid-template-columns: 1fr; } .section-head, .page-controls { align-items: flex-start; flex-direction: column; } }
</style>

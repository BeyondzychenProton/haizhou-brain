<template>
  <section class="recovery-panel">
    <header class="panel-head">
      <div>
        <h2>异常运行核查</h2>
        <p class="muted">查看待核查 Run 的安全执行事实；终止只记录平台处置，不会停止旧进程或外部动作。</p>
      </div>
      <el-button :loading="loadingRows" @click="loadRows()">刷新列表</el-button>
    </header>

    <el-alert
      class="boundary-note"
      type="warning"
      :closable="false"
      title="租约过期、心跳停止或进程列表中查无 Worker，都不能单独证明外部请求已停止。"
      description="只有在运维系统确认旧 Worker、子执行和未决外部请求均已停止后，才能录入证据引用并提交终止记录。此页面不会远程强杀或重放运行。"
    />

    <el-form class="filters" inline @submit.prevent="loadRows()">
      <el-form-item label="状态">
        <el-select v-model="stateFilter" @change="loadRows()">
          <el-option label="待核查" value="RECOVERY_REQUIRED" />
          <el-option label="已终止" value="TERMINATED" />
        </el-select>
      </el-form-item>
      <el-form-item label="属主用户 ID">
        <el-input-number v-model="userIdFilter" :min="1" :precision="0" :controls="false" clearable />
      </el-form-item>
      <el-form-item label="员工 ID">
        <el-input-number v-model="employeeIdFilter" :min="1" :precision="0" :controls="false" clearable />
      </el-form-item>
      <el-form-item label="创建日期">
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          range-separator="至"
        />
      </el-form-item>
      <el-button type="primary" :loading="loadingRows" @click="loadRows()">查询</el-button>
    </el-form>

    <el-alert v-if="pageError" class="page-error" type="error" :closable="false" :title="pageError" />
    <el-alert v-if="pageNotice" class="page-notice" type="info" :closable="false" :title="pageNotice" />

    <el-table v-loading="loadingRows" :data="rows" row-key="runId" @row-click="inspectRun">
      <el-table-column prop="runId" label="Run ID" min-width="210" show-overflow-tooltip />
      <el-table-column prop="sessionId" label="Session" min-width="180" show-overflow-tooltip />
      <el-table-column prop="ownerUserId" label="属主" width="86" />
      <el-table-column prop="employeeId" label="员工" width="86" />
      <el-table-column label="profile" width="165">
        <template #default="scope">{{ scope.row.runtimeProfile }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="scope">{{ stateLabel(scope.row.state) }}</template>
      </el-table-column>
      <el-table-column prop="failureCode" label="故障码" min-width="160" show-overflow-tooltip />
      <el-table-column label="创建时间" min-width="175">
        <template #default="scope">{{ formatTime(scope.row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="待核查时间" min-width="145">
        <template #default="scope">{{ recoveryAge(scope.row) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="105" fixed="right">
        <template #default="scope">
          <el-button link type="primary" @click.stop="inspectRun(scope.row)">核查详情</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="没有符合条件的运行" />
      </template>
    </el-table>

    <div v-if="hasMore" class="load-more">
      <el-button :loading="loadingMore" @click="loadRows(true)">加载下一页</el-button>
    </div>

    <el-drawer v-model="detailVisible" title="运行核查详情" size="min(860px, 96vw)" :destroy-on-close="false">
      <div v-if="loadingDetail" class="detail-loading">正在读取最新核查事实…</div>
      <el-alert v-else-if="detailError" type="error" :closable="false" :title="detailError" />
      <template v-else-if="detail">
        <div class="detail-actions">
          <span class="run-state" :class="detail.state === 'TERMINATED' ? 'is-terminal' : 'is-pending'">
            {{ stateLabel(detail.state) }}
          </span>
          <el-button :loading="loadingDetail" @click="refreshCurrentDetail">重新读取详情</el-button>
        </div>

        <el-alert
          v-if="detail.state === 'TERMINATED'"
          class="terminal-note"
          type="success"
          :closable="false"
          title="平台已记录 Run 终态 TERMINATED。"
          description="此状态代表待核查 Run 已结束并释放平台活动槽；它本身不能证明旧 Worker、子执行或第三方动作确已停止。"
        />

        <el-descriptions :column="2" border>
          <el-descriptions-item label="Run ID">{{ detail.runId }}</el-descriptions-item>
          <el-descriptions-item label="Session ID">{{ detail.sessionId }}</el-descriptions-item>
          <el-descriptions-item label="属主用户">{{ detail.ownerUserId }}</el-descriptions-item>
          <el-descriptions-item label="员工 / 冻结版本">{{ detail.employeeId }} / {{ detail.definitionVersionId }}</el-descriptions-item>
          <el-descriptions-item label="冻结 profile">{{ detail.runtimeProfile }}</el-descriptions-item>
          <el-descriptions-item label="故障码">{{ detail.failureCode || '—' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(detail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ formatTime(detail.startedAt) }}</el-descriptions-item>
          <el-descriptions-item label="首次待核查时间">
            {{ detail.recoveryRequiredAt ? formatTime(detail.recoveryRequiredAt) : '未知（历史记录无可用事件）' }}
          </el-descriptions-item>
          <el-descriptions-item label="详情观测时间">{{ formatTime(detail.observedAt) }}</el-descriptions-item>
        </el-descriptions>

        <h3>最新执行 attempt</h3>
        <el-empty v-if="!detail.latestAttempt" description="没有可用 attempt 记录" />
        <el-descriptions v-else :column="2" border>
          <el-descriptions-item label="attempt">{{ detail.latestAttempt.attemptId }} (#{{ detail.latestAttempt.attemptNo }})</el-descriptions-item>
          <el-descriptions-item label="Worker 标签">{{ detail.latestAttempt.workerLabel }}</el-descriptions-item>
          <el-descriptions-item label="attempt 状态">{{ detail.latestAttempt.state }}</el-descriptions-item>
          <el-descriptions-item label="当前 fence">{{ detail.latestAttempt.fenceToken }}</el-descriptions-item>
          <el-descriptions-item label="心跳时间">{{ formatTime(detail.latestAttempt.heartbeatAt) }}</el-descriptions-item>
          <el-descriptions-item label="租约截止时间">{{ formatTime(detail.latestAttempt.leaseExpiresAt) }}</el-descriptions-item>
          <el-descriptions-item label="attempt 开始">{{ formatTime(detail.latestAttempt.startedAt) }}</el-descriptions-item>
          <el-descriptions-item label="attempt 结束">{{ formatTime(detail.latestAttempt.finishedAt) }}</el-descriptions-item>
        </el-descriptions>

        <h3>平台活动槽与队列事实</h3>
        <p class="muted">以下为 {{ formatTime(detail.sessionActivity.observedAt) }} 读取到的持久 Run 状态；刷新只读取事实，不会认领或启动队列。</p>
        <el-alert
          :type="detail.sessionActivity.slotOccupied ? 'warning' : 'success'"
          :closable="false"
          :title="detail.sessionActivity.slotOccupied ? 'Session 仍有活动或排队 Run' : '当前未发现活动或排队 Run，平台活动槽为空'"
        />
        <el-table v-if="detail.sessionActivity.activeOrQueuedRuns.length" :data="detail.sessionActivity.activeOrQueuedRuns" row-key="runId" class="session-facts">
          <el-table-column prop="runId" label="Run ID" min-width="200" show-overflow-tooltip />
          <el-table-column label="状态" width="130">
            <template #default="scope">{{ stateLabel(scope.row.state) }}</template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="175">
            <template #default="scope">{{ formatTime(scope.row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="队列位置" width="100">
            <template #default="scope">{{ scope.row.state === 'QUEUED' ? scope.row.queuePosition : '—' }}</template>
          </el-table-column>
        </el-table>

        <h3>安全状态时间线</h3>
        <el-timeline v-if="detail.publicEventSummary.length">
          <el-timeline-item v-for="event in detail.publicEventSummary" :key="`${event.eventType}-${event.occurredAt}`" :timestamp="formatTime(event.occurredAt)">
            {{ eventLabel(event.eventType) }}
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="没有可展示的安全状态事件" />

        <h3>终止审计记录</h3>
        <el-table v-if="detail.recoveryActions.length" :data="detail.recoveryActions" row-key="requestId">
          <el-table-column prop="requestId" label="requestId" min-width="200" show-overflow-tooltip />
          <el-table-column prop="actorUserId" label="操作者" width="90" />
          <el-table-column prop="expectedFenceToken" label="fence" width="90" />
          <el-table-column prop="stopEvidenceReferenceLabel" label="停止证据" min-width="180" />
          <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
          <el-table-column label="记录时间" min-width="175">
            <template #default="scope">{{ formatTime(scope.row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="尚无终止审计记录" />

        <template v-if="detail.state === 'RECOVERY_REQUIRED'">
          <h3>确认外部停止证据后终止</h3>
          <el-alert
            class="eligibility-note"
            :type="detail.terminationEligibility.allowed ? 'warning' : 'error'"
            :closable="false"
            :title="terminationEligibilityText(detail)"
          />
          <el-checkbox v-model="stopEvidenceConfirmed" :disabled="!!currentPending || !detailFresh || !detail.terminationEligibility.allowed">
            我已在外部运维记录中确认此 Run、attempt 和当前 fence 对应的旧 Worker、子执行及未决外部请求均已停止。
          </el-checkbox>
          <el-form label-position="top" class="termination-form">
            <el-form-item label="受控外部停止证据引用">
              <el-input
                v-model="evidenceReference"
                maxlength="512"
                show-word-limit
                placeholder="填写工单或受控运维记录编号，不要粘贴凭据或证据正文"
                :disabled="!!currentPending"
              />
            </el-form-item>
            <el-form-item label="处置原因">
              <el-input
                v-model="reason"
                type="textarea"
                :rows="3"
                maxlength="500"
                show-word-limit
                placeholder="说明核查结论和处置依据"
                :disabled="!!currentPending"
              />
            </el-form-item>
          </el-form>
          <p v-if="currentPending" class="muted">本次 requestId 已固定：{{ currentPending.payload.requestId }}。重试将逐字重放同一请求。</p>
          <el-button
            type="danger"
            :loading="submitting"
            :disabled="!canSubmit || submitting"
            @click="submitTermination"
          >
            {{ currentPending?.unknownOutcome ? '用相同 requestId 重试' : '记录终止处置' }}
          </el-button>
          <p class="muted">服务端只检查状态、profile 和 fence，并写入审计及终态；页面确认和引用本身不是可自动验证的停止证明。</p>
        </template>
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { AxiosError } from 'axios'
import {
  getRecoveryRun,
  listRecoveryRuns,
  terminateRecoveryRun,
  type RecoveryRunDetail,
  type RecoveryRunPage,
  type RecoveryRunQuery,
  type RecoveryRunState,
  type RecoveryRunSummary,
  type RecoveryTerminationRequest,
} from '../../api/runRecovery'
import { canSubmitTermination, createLatestRequestGeneration, recoveryActionRecorded, terminationEligibilityText } from '../../presenters/runRecoveryPresenter'

interface PendingTermination {
  payload: RecoveryTerminationRequest
  unknownOutcome: boolean
}

const rows = ref<RecoveryRunSummary[]>([])
const stateFilter = ref<RecoveryRunState>('RECOVERY_REQUIRED')
const userIdFilter = ref<number | undefined>()
const employeeIdFilter = ref<number | undefined>()
const dateRange = ref<string[]>([])
const nextCursor = ref<string | null>(null)
const hasMore = ref(false)
const loadingRows = ref(false)
const loadingMore = ref(false)
const pageError = ref('')
const pageNotice = ref('')
const detailVisible = ref(false)
const selectedRunId = ref<string | null>(null)
const detail = ref<RecoveryRunDetail | null>(null)
const detailFresh = ref(false)
const loadingDetail = ref(false)
const detailError = ref('')
const stopEvidenceConfirmed = ref(false)
const evidenceReference = ref('')
const reason = ref('')
const submitting = ref(false)
const pendingByRunId = ref<Record<string, PendingTermination>>({})
const detailRequests = createLatestRequestGeneration()

const currentPending = computed(() => selectedRunId.value
  ? pendingByRunId.value[selectedRunId.value] ?? null
  : null)

const canSubmit = computed(() => {
  if (!detail.value || !detailFresh.value) return false
  return canSubmitTermination(detail.value, stopEvidenceConfirmed.value,
    currentPending.value?.payload.stopEvidenceReference ?? evidenceReference.value,
    currentPending.value?.payload.reason ?? reason.value)
})

function setPending(runId: string, value: PendingTermination | null) {
  const updated = { ...pendingByRunId.value }
  if (value) updated[runId] = value
  else delete updated[runId]
  pendingByRunId.value = updated
}

function queryParams(cursor?: string): RecoveryRunQuery {
  const query: RecoveryRunQuery = { state: stateFilter.value, limit: 20 }
  if (userIdFilter.value) query.userId = userIdFilter.value
  if (employeeIdFilter.value) query.employeeId = employeeIdFilter.value
  if (dateRange.value.length === 2) {
    const [from, to] = dateRange.value
    query.createdFrom = new Date(`${from}T00:00:00`).toISOString()
    query.createdTo = new Date(`${to}T23:59:59.999`).toISOString()
  }
  if (cursor) query.cursor = cursor
  return query
}

async function loadRows(append = false) {
  if (loadingRows.value || loadingMore.value) return
  if (append && (!hasMore.value || !nextCursor.value)) return
  if (append) loadingMore.value = true
  else loadingRows.value = true
  pageError.value = ''
  pageNotice.value = ''
  try {
    const page: RecoveryRunPage = await listRecoveryRuns(queryParams(append ? nextCursor.value ?? undefined : undefined))
    rows.value = append ? [...rows.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
  } catch (error) {
    if (handleAuthorizationLoss(error)) return
    pageError.value = safeErrorMessage(error, '运行列表读取失败，请刷新重试。')
  } finally {
    loadingRows.value = false
    loadingMore.value = false
  }
}

async function inspectRun(row: RecoveryRunSummary) {
  selectedRunId.value = row.runId
  detail.value = null
  detailFresh.value = false
  detailError.value = ''
  stopEvidenceConfirmed.value = false
  const pending = pendingByRunId.value[row.runId]
  evidenceReference.value = pending?.payload.stopEvidenceReference ?? ''
  reason.value = pending?.payload.reason ?? ''
  stopEvidenceConfirmed.value = !!pending
  detailVisible.value = true
  await loadDetail()
}

async function refreshCurrentDetail() {
  await loadDetail()
}

async function loadDetail() {
  const runId = selectedRunId.value
  if (!runId) return
  const generation = detailRequests.next()
  const previous = detail.value?.runId === runId ? detail.value : null
  loadingDetail.value = true
  detailFresh.value = false
  detailError.value = ''
  try {
    const current = await getRecoveryRun(runId)
    if (!detailRequests.isCurrent(generation) || selectedRunId.value !== runId) return
    applyFreshDetail(runId, current, previous)
  } catch (error) {
    if (!detailRequests.isCurrent(generation) || selectedRunId.value !== runId) return
    if (handleAuthorizationLoss(error)) return
    detailError.value = safeErrorMessage(error, '详情读取失败；原核查资料仍保留，请恢复查询后再操作。')
  } finally {
    if (detailRequests.isCurrent(generation)) loadingDetail.value = false
  }
}

function applyFreshDetail(runId: string, current: RecoveryRunDetail, previous: RecoveryRunDetail | null) {
  const pending = pendingByRunId.value[runId]
  if (pending && recoveryActionRecorded(current, pending.payload.requestId)) {
    setPending(runId, null)
    if (selectedRunId.value === runId) {
      pageNotice.value = '已通过同一 requestId 的审计记录确认终止请求已提交。平台终态不代表旧执行自动停止。'
      stopEvidenceConfirmed.value = false
      evidenceReference.value = ''
      reason.value = ''
    }
  } else if (previous && (previous.state !== current.state
    || previous.terminationEligibility.expectedFenceToken !== current.terminationEligibility.expectedFenceToken)) {
    setPending(runId, null)
    if (selectedRunId.value === runId) {
      stopEvidenceConfirmed.value = false
      evidenceReference.value = ''
      reason.value = ''
      pageNotice.value = '运行状态或 fence 已变化，已清除本地确认；请重新核查当前事实。'
    }
  }
  detail.value = current
  detailFresh.value = true
}

function clearForAuthorizationLoss() {
  rows.value = []
  nextCursor.value = null
  hasMore.value = false
  detail.value = null
  detailFresh.value = false
  selectedRunId.value = null
  detailVisible.value = false
  pendingByRunId.value = {}
  stopEvidenceConfirmed.value = false
  evidenceReference.value = ''
  reason.value = ''
  detailError.value = ''
  pageError.value = '管理员权限已失效，页面中的核查资料和待提交内容已清除。'
}

function handleAuthorizationLoss(error: unknown): boolean {
  const status = statusOf(error)
  if (status !== 401 && status !== 403) return false
  clearForAuthorizationLoss()
  return true
}

async function submitTermination() {
  const runId = selectedRunId.value
  const displayed = detail.value
  if (!runId || !displayed || submitting.value || !canSubmit.value) return
  submitting.value = true
  pageError.value = ''
  pageNotice.value = ''

  let fresh: RecoveryRunDetail
  try {
    fresh = await getRecoveryRun(runId)
  } catch (error) {
    if (handleAuthorizationLoss(error)) return
    pageError.value = safeErrorMessage(error, '提交前无法重新核查详情；尚未发送终止请求。')
    submitting.value = false
    return
  }

  if (selectedRunId.value !== runId) {
    submitting.value = false
    return
  }
  const currentPendingRequest = pendingByRunId.value[runId]
  if (currentPendingRequest && recoveryActionRecorded(fresh, currentPendingRequest.payload.requestId)) {
    applyFreshDetail(runId, fresh, displayed)
    submitting.value = false
    return
  }
  if (displayed.state !== fresh.state
    || displayed.terminationEligibility.expectedFenceToken !== fresh.terminationEligibility.expectedFenceToken) {
    applyFreshDetail(runId, fresh, displayed)
    pageNotice.value = '提交前发现状态或 fence 已变化，未发送终止请求；确认已清除，请重新核查。'
    submitting.value = false
    return
  }
  applyFreshDetail(runId, fresh, displayed)

  const existingPending = pendingByRunId.value[runId]
  const payload = existingPending?.payload ?? {
    expectedFenceToken: fresh.terminationEligibility.expectedFenceToken ?? 0,
    requestId: crypto.randomUUID(),
    stopEvidenceReference: evidenceReference.value.trim(),
    reason: reason.value.trim(),
  }
  if (!canSubmitTermination(fresh, stopEvidenceConfirmed.value,
    payload.stopEvidenceReference, payload.reason)) {
    pageError.value = '终止前置条件不完整；请确认停止证据并补齐引用和原因。'
    submitting.value = false
    return
  }
  if (!existingPending) setPending(runId, { payload, unknownOutcome: false })

  try {
    const response = await terminateRecoveryRun(runId, payload)
    setPending(runId, null)
    stopEvidenceConfirmed.value = false
    evidenceReference.value = ''
    reason.value = ''
    pageNotice.value = response.state === 'TERMINATED'
      ? '平台已记录终态 TERMINATED。旧进程或外部动作是否停止仍以外部运维证据为准。'
      : '终止响应状态异常，请重新读取平台事实。'
    await loadRows()
    if (selectedRunId.value === runId) await loadDetail()
  } catch (error) {
    if (handleAuthorizationLoss(error)) return
    const status = statusOf(error)
    if (status === 409) {
      setPending(runId, null)
      stopEvidenceConfirmed.value = false
      evidenceReference.value = ''
      reason.value = ''
      pageError.value = '运行状态或 fence 冲突，当前操作已废弃；请刷新详情重新核查。'
      await loadDetail()
    } else if (status === 400) {
      setPending(runId, null)
      pageError.value = '服务端拒绝了请求参数；请检查证据引用和处置原因后重新核查。'
    } else {
      setPending(runId, { payload, unknownOutcome: true })
      pageError.value = `提交结果未知。保留 requestId ${payload.requestId} 和原请求内容；先刷新详情核对审计记录，再用相同 requestId 重试。`
    }
  } finally {
    submitting.value = false
  }
}

function statusOf(error: unknown): number | undefined {
  return (error as AxiosError | undefined)?.response?.status
}

function safeErrorMessage(error: unknown, fallback: string): string {
  const status = statusOf(error)
  if (status === 404) return '该 Run 已不存在，请刷新列表。'
  if (status === 503) return '核查依赖暂不可用；保留当前页面内容，恢复后重新读取。'
  if (status === 409) return '运行状态冲突；请重新读取详情。'
  return fallback
}

function stateLabel(state: string): string {
  const labels: Record<string, string> = {
    QUEUED: '排队中', RUNNING: '运行中', WAITING_TOOL: '等待工具', WAITING_CONFIRMATION: '等待审批',
    CANCELLING: '取消中', RECOVERY_REQUIRED: '待核查', TERMINATED: '已终止', SUCCEEDED: '已完成',
    FAILED: '失败', CANCELLED: '已取消', EXPIRED: '已过期',
  }
  return labels[state] ?? state
}

function eventLabel(eventType: string): string {
  const labels: Record<string, string> = {
    RUN_QUEUED: 'Run 已进入队列', RUN_STARTED: 'Run 已开始', RUN_COMPLETED: 'Run 已完成',
    RUN_FAILED: 'Run 执行失败', RUN_CANCELLED: 'Run 已取消', RUN_EXPIRED: 'Run 已过期',
    RUN_RECOVERY_REQUIRED: 'Run 进入待核查状态', RUN_RECOVERY_TERMINATED: '管理员已记录终止处置',
  }
  return labels[eventType] ?? '运行状态已更新'
}

function recoveryAge(run: RecoveryRunSummary): string {
  if (!run.recoveryRequiredAt || run.recoveryRequiredAtStatus === 'UNKNOWN') return '未知'
  const minutes = Math.max(0, Math.floor((Date.now() - Date.parse(run.recoveryRequiredAt)) / 60_000))
  if (minutes < 60) return `${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  const remainder = minutes % 60
  if (hours < 24) return `${hours} 小时 ${remainder} 分钟`
  return `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`
}

function formatTime(value: string | null): string {
  if (!value) return '—'
  const timestamp = Date.parse(value)
  if (!Number.isFinite(timestamp)) return '未知'
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium' }).format(timestamp)
}

onMounted(() => loadRows())
</script>

<style scoped>
.recovery-panel { display: grid; gap: 18px; }
.panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; }
.panel-head h2 { margin: 0 0 6px; }
.muted { color: var(--el-text-color-secondary); line-height: 1.55; }
.boundary-note, .page-error, .page-notice, .terminal-note, .eligibility-note { margin: 2px 0; }
.filters { padding: 14px 16px 0; border: 1px solid var(--el-border-color-light); border-radius: 8px; }
.load-more { display: flex; justify-content: center; padding: 6px 0; }
.detail-loading { padding: 24px 0; color: var(--el-text-color-secondary); }
.detail-actions { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.run-state { padding: 5px 10px; border-radius: 12px; font-weight: 600; }
.is-terminal { color: var(--el-color-success-dark-2); background: var(--el-color-success-light-9); }
.is-pending { color: var(--el-color-warning-dark-2); background: var(--el-color-warning-light-9); }
h3 { margin: 24px 0 10px; }
.session-facts { margin-top: 12px; }
.eligibility-note { margin-bottom: 14px; }
.termination-form { margin: 14px 0 4px; }
@media (max-width: 720px) {
  .panel-head { align-items: stretch; flex-direction: column; }
  .filters { display: flex; flex-direction: column; align-items: stretch; }
}
</style>

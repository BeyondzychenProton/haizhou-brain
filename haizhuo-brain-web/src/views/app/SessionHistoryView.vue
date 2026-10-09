<template>
  <AppLayout>
    <div class="page-head">
      <div>
        <h2>全部会话</h2>
        <p class="muted">按创建时间浏览历史会话、运行记录和完整结果。</p>
      </div>
      <el-button @click="router.push('/app/employees')">返回员工</el-button>
    </div>

    <div class="filters">
      <el-select v-model="statusFilter" clearable placeholder="全部状态" @change="resetSessions">
        <el-option label="进行中" value="ACTIVE" />
        <el-option label="已关闭" value="CLOSED" />
      </el-select>
      <el-select v-model="employeeFilter" clearable filterable placeholder="全部员工" @change="resetSessions">
        <el-option v-for="employee in employees" :key="employee.id" :label="employee.name" :value="employee.id" />
      </el-select>
      <el-button @click="resetSessions">刷新</el-button>
    </div>

    <el-alert v-if="sessionError" type="error" :closable="false" show-icon title="历史会话加载失败">
      <template #default><el-button link @click="resetSessions">重试</el-button></template>
    </el-alert>
    <el-skeleton v-if="sessionLoading && !sessions.length" :rows="5" animated />
    <el-empty v-else-if="!sessionLoading && !sessionError && !sessions.length" description="没有符合条件的会话" />
    <el-table v-if="sessions.length" :data="sessions" row-key="sessionId" highlight-current-row @row-click="inspectSession">
      <el-table-column label="数字员工" min-width="180">
        <template #default="scope">{{ employeeName(scope.row.employeeId) }}</template>
      </el-table-column>
      <el-table-column label="创建时间" min-width="190">
        <template #default="scope">{{ format(scope.row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="最近活动" min-width="190">
        <template #default="scope">{{ format(scope.row.lastActiveAt) }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column label="操作" width="110">
        <template #default="scope"><el-button link type="primary" @click.stop="inspectSession(scope.row)">查看记录</el-button></template>
      </el-table-column>
    </el-table>
    <div v-if="sessionHasMore" class="load-more">
      <el-button :loading="sessionLoading" @click="loadMoreSessions">加载更多</el-button>
    </div>

    <el-drawer v-model="drawerOpen" size="min(940px, 92vw)" :title="inspectedSession ? `${employeeName(inspectedSession.employeeId)} · 历史详情` : '历史详情'" destroy-on-close>
      <div v-if="inspectedSession" class="history-drawer">
        <el-tabs v-model="activeTab">
          <el-tab-pane label="运行记录" name="runs">
            <div class="drawer-toolbar">
              <el-select v-model="runStateFilter" clearable placeholder="全部运行状态" @change="resetRuns">
                <el-option v-for="state in runStates" :key="state" :label="stateLabel(state)" :value="state" />
              </el-select>
              <el-button @click="resetRuns">刷新</el-button>
            </div>
            <el-alert v-if="runError" type="error" :closable="false" title="运行记录加载失败">
              <template #default><el-button link @click="resetRuns">重试</el-button></template>
            </el-alert>
            <el-skeleton v-if="runLoading && !runs.length" :rows="3" animated />
            <el-empty v-else-if="!runLoading && !runError && !runs.length" description="该会话还没有运行记录" />
            <el-table v-if="runs.length" :data="runs" row-key="runId" highlight-current-row @row-click="inspectRun">
              <el-table-column label="创建时间" min-width="180">
                <template #default="scope">{{ format(scope.row.createdAt) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="140">
                <template #default="scope"><el-tag :type="runTagType(scope.row.state)">{{ stateLabel(scope.row.state) }}</el-tag></template>
              </el-table-column>
              <el-table-column label="执行者" min-width="160">
                <template #default="scope">{{ scope.row.executorRoleId }} · v{{ scope.row.executorDefinitionVersionId }}</template>
              </el-table-column>
              <el-table-column label="操作" width="110">
                <template #default="scope"><el-button link type="primary" @click.stop="inspectRun(scope.row)">查看详情</el-button></template>
              </el-table-column>
            </el-table>
            <div v-if="runHasMore" class="load-more">
              <el-button :loading="runLoading" @click="loadMoreRuns">加载更多运行</el-button>
            </div>

            <section v-if="selectedRun" class="run-detail">
              <div class="detail-heading">
                <div><h3>运行详情</h3><p class="muted">{{ selectedRun.runId }} · {{ stateLabel(selectedRun.state) }}</p></div>
                <el-button link @click="router.push({ name: 'session', params: { sessionId: selectedRun.sessionId } })">打开会话</el-button>
              </div>
              <el-alert v-if="detailError" type="error" :closable="false" title="运行详情加载失败">
                <template #default><el-button link @click="retryRunDetails">重试</el-button></template>
              </el-alert>
              <el-skeleton v-if="detailLoading" :rows="4" animated />
              <template v-else>
                <div v-if="selectedRun.state === 'SUCCEEDED' && runResult" class="result-card">
                  <div class="result-heading">
                    <el-tag v-if="runResult.legacySummary" type="warning">历史摘要，非完整正文</el-tag>
                    <el-tag v-else type="success">完整结果</el-tag>
                    <span class="muted">{{ format(runResult.createdAt) }} · {{ runResult.byteSize }} bytes</span>
                  </div>
                  <pre class="result-body">{{ sanitizeDisplayText(runResult.body) }}</pre>
                </div>
                <el-empty v-else-if="selectedRun.state === 'SUCCEEDED' && resultError" description="完整结果暂不可用，可重试读取；运行状态仍为成功。">
                  <el-button @click="reloadRunResult">重试读取结果</el-button>
                </el-empty>
                <el-empty v-else description="该运行没有可展示的成功结果。运行状态按持久事实显示。" />

                <section v-if="selectedRun.state === 'SUCCEEDED'" class="feedback-box">
                  <h4>运行反馈</h4>
                  <template v-if="runResult && !runResult.legacySummary">
                    <el-input v-model="feedbackComment" type="textarea" :rows="2" maxlength="1000" show-word-limit
                      placeholder="可选，补充评价原因" :disabled="feedbackSubmitting || !!pendingFeedback" />
                    <div class="feedback-actions">
                      <el-button :disabled="feedbackSubmitting || !!pendingFeedback" @click="submitFeedback(1)">满意</el-button>
                      <el-button :disabled="feedbackSubmitting || !!pendingFeedback" @click="submitFeedback(0)">不满意</el-button>
                    </div>
                    <p v-if="feedbackNotice" :class="feedbackNoticeType">{{ feedbackNotice }}</p>
                    <el-button v-if="pendingFeedback" :loading="feedbackSubmitting" @click="retryPendingFeedback">
                      使用同一请求重试
                    </el-button>
                  </template>
                  <el-alert v-else type="info" :closable="false" title="仅可对有完整正文的成功运行提交新反馈；既有反馈仍可查看。" />

                  <el-alert v-if="feedbackHistoryError" type="error" :closable="false" title="反馈历史加载失败">
                    <template #default><el-button link @click="resetFeedbackHistory">重试</el-button></template>
                  </el-alert>
                  <el-skeleton v-if="feedbackHistoryLoading && !feedbackHistory.length" :rows="2" animated />
                  <el-empty v-else-if="!feedbackHistoryLoading && !feedbackHistoryError && !feedbackHistory.length"
                    description="尚无历史反馈" :image-size="55" />
                  <el-table v-if="feedbackHistory.length" :data="feedbackHistory" size="small" row-key="feedbackId">
                    <el-table-column label="评价" width="100">
                      <template #default="scope">{{ scope.row.value === 1 ? '满意' : scope.row.value === 0 ? '不满意' : scope.row.value }}</template>
                    </el-table-column>
                    <el-table-column prop="comment" label="说明" min-width="180" show-overflow-tooltip />
                    <el-table-column label="时间" min-width="180"><template #default="scope">{{ format(scope.row.createdAt) }}</template></el-table-column>
                    <el-table-column label="导出状态" min-width="200">
                      <template #default="scope">{{ exportDispositionLabel(scope.row.exportDisposition) }}</template>
                    </el-table-column>
                  </el-table>
                  <div v-if="feedbackHistoryHasMore" class="load-more">
                    <el-button :loading="feedbackHistoryLoading" @click="loadMoreFeedbackHistory">加载更多反馈</el-button>
                  </div>
                </section>

                <div class="tool-history">
                  <h4>工具记录</h4>
                  <el-table v-if="toolSummaries.length" :data="toolSummaries" size="small" row-key="toolExecutionId">
                    <el-table-column prop="toolName" label="工具" min-width="150" />
                    <el-table-column prop="state" label="状态" min-width="130" />
                    <el-table-column prop="approvalDecision" label="审批" min-width="100" />
                    <el-table-column label="更新时间" min-width="180"><template #default="scope">{{ format(scope.row.updatedAt) }}</template></el-table-column>
                  </el-table>
                  <el-empty v-else description="没有工具记录" :image-size="55" />
                </div>
              </template>
              <RunArtifactPanel v-if="selectedRun.state === 'SUCCEEDED'" :run-id="selectedRun.runId"
                :export-allowed="canExportMarkdown" :export-disabled-reason="artifactExportDisabledReason" />
            </section>
          </el-tab-pane>

          <el-tab-pane label="结果与引用" name="results">
            <p class="muted">仅列出同一会话中可引用的完整用户结果。提交时后端会再次核对属主、完整性和体积。</p>
            <el-alert v-if="resultListError" type="error" :closable="false" title="结果列表加载失败">
              <template #default><el-button link @click="resetResults">重试</el-button></template>
            </el-alert>
            <el-skeleton v-if="resultListLoading && !referenceResults.length" :rows="3" animated />
            <el-empty v-else-if="!resultListLoading && !resultListError && !referenceResults.length" description="没有可引用的完整结果" />
            <el-table v-if="referenceResults.length" :data="referenceResults" row-key="resultId">
              <el-table-column label="选择" width="65">
                <template #default="scope">
                  <el-checkbox :model-value="selectedReferenceIds.includes(scope.row.resultId)" :disabled="!canToggleReference(scope.row)" @change="toggleReference(scope.row, $event)" />
                </template>
              </el-table-column>
              <el-table-column label="时间" min-width="180"><template #default="scope">{{ format(scope.row.createdAt) }}</template></el-table-column>
              <el-table-column prop="runId" label="来源运行" min-width="210" />
              <el-table-column label="执行者" min-width="140"><template #default="scope">{{ scope.row.executorRoleId || 'coordinator' }}</template></el-table-column>
              <el-table-column label="大小" width="110"><template #default="scope">{{ scope.row.byteSize }} bytes</template></el-table-column>
              <el-table-column label="操作" width="90"><template #default="scope"><el-button link @click="viewReference(scope.row)">预览</el-button></template></el-table-column>
            </el-table>
            <div v-if="resultListHasMore" class="load-more">
              <el-button :loading="resultListLoading" @click="loadMoreResults">加载更多结果</el-button>
            </div>
            <div v-if="selectedReferenceIds.length" class="reference-footer">
              <span>已选 {{ selectedReferenceIds.length }} 项，{{ selectedReferenceBytes }} / 524288 bytes</span>
              <el-button type="primary" :disabled="!inspectedSession" @click="continueWithReferences">在会话中使用这些引用</el-button>
            </div>
            <section v-if="viewedReference" class="reference-preview">
              <h3>结果预览</h3>
              <el-tag v-if="viewedReference.legacySummary" type="warning">历史摘要，非完整正文，不可引用</el-tag>
              <pre class="result-body">{{ sanitizeDisplayText(viewedReference.body) }}</pre>
            </section>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-drawer>
  </AppLayout>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import AppLayout from '../../layouts/AppLayout.vue'
import RunArtifactPanel from './RunArtifactPanel.vue'
import * as appApi from '../../api/app'
import * as historyApi from '../../api/sessionHistory'
import { sanitizeDisplayText } from '../../utils/sanitizeDisplayText'
import type { Employee, RunResult } from '../../api/app'
import type { ResultHistoryItem, RunFeedbackItem, RunHistoryItem, SessionHistoryItem } from '../../api/sessionHistory'
import { isMarkdownArtifactExportAllowed } from '../../presenters/runArtifactPolicy'

const router = useRouter()
const employees = ref<Employee[]>([])
const sessions = ref<SessionHistoryItem[]>([])
const sessionCursor = ref<string | null>(null)
const sessionHasMore = ref(false)
const sessionLoading = ref(false)
const sessionError = ref(false)
const statusFilter = ref('')
const employeeFilter = ref<number | ''>('')
let sessionGeneration = 0

const drawerOpen = ref(false)
const inspectedSession = ref<SessionHistoryItem | null>(null)
const activeTab = ref<'runs' | 'results'>('runs')
const runs = ref<RunHistoryItem[]>([])
const runCursor = ref<string | null>(null)
const runHasMore = ref(false)
const runLoading = ref(false)
const runError = ref(false)
const runStateFilter = ref('')
const selectedRun = ref<RunHistoryItem | null>(null)
const detailLoading = ref(false)
const detailError = ref(false)
const resultError = ref(false)
const runResult = ref<RunResult | null>(null)
const toolSummaries = ref<historyApi.HistoricalToolSummary[]>([])
const feedbackComment = ref('')
const feedbackHistory = ref<RunFeedbackItem[]>([])
const feedbackHistoryCursor = ref<string | null>(null)
const feedbackHistoryHasMore = ref(false)
const feedbackHistoryLoading = ref(false)
const feedbackHistoryError = ref(false)
const feedbackSubmitting = ref(false)
const feedbackNotice = ref('')
const feedbackNoticeType = ref<'success-text' | 'warning-text'>('success-text')
const pendingFeedback = ref<{ clientRequestId: string; value: 0 | 1; comment?: string } | null>(null)
let runGeneration = 0
let detailGeneration = 0
let feedbackHistoryGeneration = 0

const referenceResults = ref<ResultHistoryItem[]>([])
const resultCursor = ref<string | null>(null)
const resultListHasMore = ref(false)
const resultListLoading = ref(false)
const resultListError = ref(false)
const selectedReferenceIds = ref<string[]>([])
const viewedReference = ref<RunResult | null>(null)
const selectedReferenceBytes = computed(() => referenceResults.value
  .filter(item => selectedReferenceIds.value.includes(item.resultId))
  .reduce((total, item) => total + item.byteSize, 0))
let resultGeneration = 0

const runStates = ['QUEUED', 'RUNNING', 'WAITING_TOOL', 'WAITING_CONFIRMATION', 'CANCELLING', 'RECOVERY_REQUIRED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TERMINATED', 'EXPIRED']
const canExportMarkdown = computed(() => isMarkdownArtifactExportAllowed(selectedRun.value, runResult.value))
const artifactExportDisabledReason = computed(() => {
  const run = selectedRun.value
  const result = runResult.value
  if (run && result?.runId === run.runId && result.legacySummary)
    return '当前运行只有历史摘要，无法导出完整 Markdown；已有成果物仍可查看和下载。'
  if (resultError.value)
    return '完整正式结果读取失败，重试读取后才能导出；已有成果物仍可查看和下载。'
  return '读取到完整正式结果后才能生成 Markdown；已有成果物仍可查看和下载。'
})

async function loadEmployees() {
  try { employees.value = await appApi.listEmployees() } catch { employees.value = [] }
}

async function resetSessions() {
  const generation = ++sessionGeneration
  sessions.value = []
  sessionCursor.value = null
  sessionHasMore.value = false
  sessionError.value = false
  await loadSessionsPage(generation, null, true)
}

async function loadMoreSessions() {
  if (!sessionHasMore.value || sessionLoading.value) return
  await loadSessionsPage(sessionGeneration, sessionCursor.value, false)
}

async function loadSessionsPage(generation: number, cursor: string | null, replace: boolean) {
  sessionLoading.value = true
  try {
    const page = await historyApi.listSessionHistoryPage({
      employeeId: employeeFilter.value || undefined,
      status: statusFilter.value || undefined,
      cursor,
      limit: 20,
    })
    if (generation !== sessionGeneration) return
    sessions.value = replace ? page.items : [...sessions.value, ...page.items]
    sessionCursor.value = page.nextCursor
    sessionHasMore.value = page.hasMore
    sessionError.value = false
  } catch {
    if (generation === sessionGeneration) sessionError.value = true
  } finally {
    if (generation === sessionGeneration) sessionLoading.value = false
  }
}

async function inspectSession(session: SessionHistoryItem) {
  const generation = ++runGeneration
  ++detailGeneration
  ++resultGeneration
  inspectedSession.value = session
  drawerOpen.value = true
  activeTab.value = 'runs'
  runs.value = []
  runCursor.value = null
  runHasMore.value = false
  runError.value = false
  selectedRun.value = null
  runResult.value = null
  toolSummaries.value = []
  feedbackHistory.value = []
  feedbackHistoryCursor.value = null
  feedbackHistoryHasMore.value = false
  feedbackHistoryError.value = false
  feedbackNotice.value = ''
  pendingFeedback.value = null
  feedbackSubmitting.value = false
  feedbackHistoryLoading.value = false
  ++feedbackHistoryGeneration
  selectedReferenceIds.value = []
  viewedReference.value = null
  referenceResults.value = []
  resultCursor.value = null
  resultListHasMore.value = false
  await Promise.all([loadRunsPage(generation, null, true), loadResultsPage(resultGeneration, null, true)])
}

async function resetRuns() {
  if (!inspectedSession.value) return
  const generation = ++runGeneration
  ++detailGeneration
  ++feedbackHistoryGeneration
  runs.value = []
  runCursor.value = null
  runHasMore.value = false
  runError.value = false
  selectedRun.value = null
  detailLoading.value = false
  runResult.value = null
  toolSummaries.value = []
  feedbackHistory.value = []
  feedbackHistoryCursor.value = null
  feedbackHistoryHasMore.value = false
  feedbackHistoryLoading.value = false
  feedbackHistoryError.value = false
  feedbackSubmitting.value = false
  pendingFeedback.value = null
  feedbackNotice.value = ''
  await loadRunsPage(generation, null, true)
}

async function loadMoreRuns() {
  if (!inspectedSession.value || !runHasMore.value || runLoading.value) return
  await loadRunsPage(runGeneration, runCursor.value, false)
}

async function loadRunsPage(generation: number, cursor: string | null, replace: boolean) {
  if (!inspectedSession.value) return
  runLoading.value = true
  try {
    const page = await historyApi.listRunHistoryPage(inspectedSession.value.sessionId, {
      state: runStateFilter.value || undefined,
      cursor,
      limit: 20,
    })
    if (generation !== runGeneration || !inspectedSession.value) return
    runs.value = replace ? page.items : [...runs.value, ...page.items]
    runCursor.value = page.nextCursor
    runHasMore.value = page.hasMore
    runError.value = false
  } catch {
    if (generation === runGeneration) runError.value = true
  } finally {
    if (generation === runGeneration) runLoading.value = false
  }
}

async function inspectRun(run: RunHistoryItem) {
  const generation = ++detailGeneration
  selectedRun.value = run
  detailLoading.value = true
  detailError.value = false
  resultError.value = false
  runResult.value = null
  toolSummaries.value = []
  feedbackComment.value = ''
  feedbackHistory.value = []
  feedbackHistoryCursor.value = null
  feedbackHistoryHasMore.value = false
  feedbackHistoryError.value = false
  feedbackNotice.value = ''
  pendingFeedback.value = null
  feedbackSubmitting.value = false
  feedbackHistoryLoading.value = false
  ++feedbackHistoryGeneration
  const jobs: Promise<unknown>[] = [historyApi.listHistoricalToolSummaries(run.runId).then(value => {
    if (generation === detailGeneration) toolSummaries.value = value
  })]
  if (run.state === 'SUCCEEDED') {
    jobs.push(appApi.getRunResult(run.runId).then(value => {
      if (generation === detailGeneration && value.runId === run.runId) runResult.value = value
    }).catch(() => {
      if (generation === detailGeneration) resultError.value = true
    }))
    jobs.push(loadFeedbackPage(run, generation, null, true))
  }
  const results = await Promise.allSettled(jobs)
  if (generation !== detailGeneration) return
  detailError.value = results.some(result => result.status === 'rejected')
  detailLoading.value = false
}

async function reloadRunResult() {
  if (selectedRun.value) await inspectRun(selectedRun.value)
}

function retryRunDetails() {
  if (selectedRun.value) void inspectRun(selectedRun.value)
}

async function submitFeedback(value: 0 | 1) {
  const target = selectedRun.value
  if (!target || target.state !== 'SUCCEEDED' || !runResult.value || runResult.value.legacySummary
      || feedbackSubmitting.value || pendingFeedback.value) return
  const request = {
    clientRequestId: createFeedbackRequestId(),
    value,
    ...(feedbackComment.value ? { comment: feedbackComment.value } : {}),
  }
  await sendFeedback(target, request)
}

async function retryPendingFeedback() {
  const target = selectedRun.value
  const request = pendingFeedback.value
  if (!target || !request || target.state !== 'SUCCEEDED' || feedbackSubmitting.value) return
  await sendFeedback(target, request)
}

async function sendFeedback(target: RunHistoryItem, request: { clientRequestId: string; value: 0 | 1; comment?: string }) {
  const generation = detailGeneration
  feedbackSubmitting.value = true
  try {
    const response = await historyApi.submitHistoricalRunFeedback(target.runId, request)
    if (generation !== detailGeneration || selectedRun.value?.runId !== target.runId) return
    pendingFeedback.value = null
    feedbackNotice.value = exportDispositionLabel(response.exportDisposition)
    feedbackNoticeType.value = response.exportDisposition === 'ACCEPTED_NOT_CONFIRMED' ? 'success-text' : 'warning-text'
    await loadFeedbackPage(target, generation, null, true)
  } catch {
    if (generation === detailGeneration && selectedRun.value?.runId === target.runId) {
      pendingFeedback.value = request
      feedbackNotice.value = '提交结果暂时未知；重试会复用同一请求编号，避免生成重复反馈。'
      feedbackNoticeType.value = 'warning-text'
    }
  } finally {
    if (generation === detailGeneration) feedbackSubmitting.value = false
  }
}

async function resetFeedbackHistory() {
  if (!selectedRun.value) return
  await loadFeedbackPage(selectedRun.value, detailGeneration, null, true)
}

async function loadMoreFeedbackHistory() {
  if (!selectedRun.value || !feedbackHistoryHasMore.value || feedbackHistoryLoading.value) return
  await loadFeedbackPage(selectedRun.value, detailGeneration, feedbackHistoryCursor.value, false)
}

async function loadFeedbackPage(target: RunHistoryItem, generation: number, cursor: string | null, replace: boolean) {
  const historyGeneration = ++feedbackHistoryGeneration
  feedbackHistoryLoading.value = true
  try {
    const page = await historyApi.listHistoricalRunFeedback(target.runId, { cursor, limit: 20 })
    if (generation !== detailGeneration || historyGeneration !== feedbackHistoryGeneration
        || selectedRun.value?.runId !== target.runId) return
    feedbackHistory.value = replace ? page.items : [...feedbackHistory.value, ...page.items]
    feedbackHistoryCursor.value = page.nextCursor
    feedbackHistoryHasMore.value = page.hasMore
    feedbackHistoryError.value = false
  } catch {
    if (generation === detailGeneration && historyGeneration === feedbackHistoryGeneration
        && selectedRun.value?.runId === target.runId) feedbackHistoryError.value = true
  } finally {
    if (generation === detailGeneration && historyGeneration === feedbackHistoryGeneration
        && selectedRun.value?.runId === target.runId) feedbackHistoryLoading.value = false
  }
}

async function resetResults() {
  if (!inspectedSession.value) return
  const generation = ++resultGeneration
  referenceResults.value = []
  resultCursor.value = null
  resultListHasMore.value = false
  resultListError.value = false
  selectedReferenceIds.value = []
  viewedReference.value = null
  await loadResultsPage(generation, null, true)
}

async function loadMoreResults() {
  if (!inspectedSession.value || !resultListHasMore.value || resultListLoading.value) return
  await loadResultsPage(resultGeneration, resultCursor.value, false)
}

async function loadResultsPage(generation: number, cursor: string | null, replace: boolean) {
  if (!inspectedSession.value) return
  resultListLoading.value = true
  try {
    const page = await historyApi.listResultHistoryPage(inspectedSession.value.sessionId, { cursor, limit: 20 })
    if (generation !== resultGeneration || !inspectedSession.value) return
    referenceResults.value = replace ? page.items : [...referenceResults.value, ...page.items]
    resultCursor.value = page.nextCursor
    resultListHasMore.value = page.hasMore
    resultListError.value = false
  } catch {
    if (generation === resultGeneration) resultListError.value = true
  } finally {
    if (generation === resultGeneration) resultListLoading.value = false
  }
}

function canToggleReference(item: ResultHistoryItem) {
  const selected = selectedReferenceIds.value.includes(item.resultId)
  return selected || (selectedReferenceIds.value.length < 8 && selectedReferenceBytes.value + item.byteSize <= 512 * 1024)
}

function toggleReference(item: ResultHistoryItem, checked: string | number | boolean) {
  if (checked) {
    if (!canToggleReference(item)) return
    selectedReferenceIds.value = [...selectedReferenceIds.value, item.resultId]
  } else {
    selectedReferenceIds.value = selectedReferenceIds.value.filter(id => id !== item.resultId)
  }
}

async function viewReference(item: ResultHistoryItem) {
  const generation = ++detailGeneration
  viewedReference.value = null
  try {
    const result = await appApi.getRunResult(item.runId)
    if (generation !== detailGeneration || result.runId !== item.runId || result.resultId !== item.resultId
        || result.bodySha256 !== item.bodySha256) return
    viewedReference.value = result
  } catch {
    ElMessage.error('完整结果暂时无法读取，请稍后重试。')
  }
}

function continueWithReferences() {
  if (!inspectedSession.value || !selectedReferenceIds.value.length) return
  router.push({ name: 'session', params: { sessionId: inspectedSession.value.sessionId },
    query: { referencedResultIds: selectedReferenceIds.value } })
}

function employeeName(id: number) { return employees.value.find(employee => employee.id === id)?.name ?? `员工 ${id}` }
function format(value: string) { return value ? new Date(value).toLocaleString('zh-CN') : '—' }
function stateLabel(state: string) {
  const labels: Record<string, string> = { QUEUED: '排队中', RUNNING: '运行中', WAITING_TOOL: '等待工具',
    WAITING_CONFIRMATION: '等待审批', CANCELLING: '取消中', RECOVERY_REQUIRED: '待核查', SUCCEEDED: '成功',
    FAILED: '失败', CANCELLED: '已取消', TERMINATED: '已终止', EXPIRED: '已过期' }
  return labels[state] ?? state
}
function runTagType(state: string) {
  if (state === 'SUCCEEDED') return 'success'
  if (['FAILED', 'CANCELLED', 'TERMINATED', 'EXPIRED'].includes(state)) return 'info'
  if (state === 'RECOVERY_REQUIRED') return 'danger'
  return 'warning'
}
function exportDispositionLabel(state: RunFeedbackItem['exportDisposition']) {
  if (state === 'ACCEPTED_NOT_CONFIRMED') return '平台已保存；已进入异步队列，远端保存未确认'
  if (state === 'NOT_ACCEPTED') return '观测队列未接受，平台已保存'
  return '导出状态未知，平台已保存'
}
function createFeedbackRequestId() {
  return globalThis.crypto?.randomUUID?.() ?? `feedback-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

onMounted(() => { void loadEmployees(); void resetSessions() })
onBeforeUnmount(() => { ++sessionGeneration; ++runGeneration; ++detailGeneration; ++resultGeneration })
</script>

<style scoped>
.filters,.drawer-toolbar,.feedback-actions,.reference-footer,.detail-heading,.result-heading{display:flex;align-items:center;gap:12px}
.filters{margin:12px 0 20px}.filters .el-select,.drawer-toolbar .el-select{width:220px}
.load-more{display:flex;justify-content:center;padding:18px}.history-drawer{display:flex;flex-direction:column;gap:16px}
.run-detail,.tool-history,.feedback-box,.reference-preview{margin-top:22px}.detail-heading{justify-content:space-between}
.detail-heading h3,.tool-history h4,.feedback-box h4,.reference-preview h3{margin:0 0 8px}
.result-card{border:1px solid var(--el-border-color);border-radius:8px;padding:14px}
.result-heading{justify-content:space-between;margin-bottom:12px}.result-body{max-height:55vh;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;background:var(--el-fill-color-light);padding:14px;border-radius:6px;font:inherit;line-height:1.55}
.feedback-box{display:grid;gap:10px}.feedback-actions{justify-content:flex-end}.reference-footer{justify-content:space-between;padding:14px 0;position:sticky;bottom:0;background:var(--el-bg-color);border-top:1px solid var(--el-border-color)}
.success-text{color:var(--el-color-success)}.warning-text{color:var(--el-color-warning)}.muted{color:var(--el-text-color-secondary)}
</style>

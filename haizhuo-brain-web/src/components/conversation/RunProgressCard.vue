<template>
  <el-card class="run-progress" shadow="never">
    <el-collapse v-model="expanded">
      <el-collapse-item name="progress">
        <template #title>
          <div class="run-progress__title">
            <strong>协作进度</strong>
            <span v-if="loading && !projection" class="muted">正在读取</span>
            <span v-else-if="loadError" class="run-progress__error-label">读取失败</span>
            <span v-else-if="projection?.available" class="muted">{{ projection.summary.workItemTotal }} 项工作</span>
          </div>
        </template>

        <div class="run-progress__body">
          <div v-if="loadError" class="run-progress__notice" role="status">
            <span>协作进度读取失败，保留最后一次成功结果。</span>
            <el-button link type="primary" :loading="loading" @click="refresh">重试</el-button>
          </div>
          <p v-if="loading && !projection" class="muted" role="status">正在读取协作进度…</p>
          <div v-else-if="!projection && !loadError" class="muted">暂无协作记录。</div>
          <div v-else-if="projection && !projection.available" class="muted">无可用进度记录。</div>

          <template v-if="projection?.available">
            <div v-if="projection.delegationBudget" class="run-progress__budget">
              调用预留 {{ projection.delegationBudget.invocationsReserved }}
              <template v-if="projection.delegationBudget.invocationLimit !== null">
                / {{ projection.delegationBudget.invocationLimit }}
              </template>
              <span> · 当前活动 {{ projection.delegationBudget.activeInvocations }}</span>
              <template v-if="projection.delegationBudget.parallelLimit !== null">
                / 并行上限 {{ projection.delegationBudget.parallelLimit }}
              </template>
              <span class="muted">（预留不代表调用成功）</span>
            </div>

            <div v-for="item in items" :key="item.workItemRef" class="run-progress__item">
              <div class="run-progress__item-head">
                <div>
                  <strong>{{ item.displayLabel }}</strong>
                  <span class="muted"> · {{ item.executorDisplayName }}</span>
                </div>
                <el-tag size="small" :type="stateTagType(item.state)">{{ progressStateLabel(item.state) }}</el-tag>
              </div>
              <div class="run-progress__item-meta">
                <span>修订 {{ item.assignmentRevision }}</span>
                <span v-if="item.required">必需工作项</span>
              </div>

              <div v-for="revision in revisionRows(item)" :key="revision.assignmentRevision"
                   class="run-progress__revision">
                <div class="run-progress__revision-info">
                  <span>修订 {{ revision.assignmentRevision }}</span>
                  <span>{{ progressStateLabel(revision.executionState) }}</span>
                  <span>{{ formatState(revision.formatStatus) }}</span>
                  <span>{{ formatState(revision.reviewStatus) }}</span>
                </div>
                <el-button v-if="canOpenWorkItemResult(revision, unavailableResultKeys, item.workItemRef)"
                           link type="primary" :loading="isResultLoading(item.workItemRef, revision.assignmentRevision)"
                           @click="openResult(item.workItemRef, revision.assignmentRevision)">
                  查看结果
                </el-button>
                <span v-else-if="revision.resultAvailability === 'PRIVATE'" class="muted">内部结果</span>
                <span v-else-if="revision.resultId || revision.resultAvailability === 'UNAVAILABLE'" class="muted">
                  结果暂不可用
                </span>
              </div>

              <div v-if="historyErrors[item.workItemRef]" class="run-progress__notice" role="status">
                <span>{{ historyErrors[item.workItemRef] }}</span>
                <el-button link type="primary" :loading="historyLoading[item.workItemRef]"
                           @click="loadHistory(item)">重试</el-button>
              </div>
              <el-button v-if="item.assignmentRevision > 1" link type="primary" size="small"
                         :loading="historyLoading[item.workItemRef]"
                         @click="toggleHistory(item)">
                {{ historyExpanded[item.workItemRef] ? '收起历史修订' : `查看历史修订（${item.assignmentRevision - 1}）` }}
              </el-button>
              <div v-if="historyExpanded[item.workItemRef] && !historyErrors[item.workItemRef]"
                   class="run-progress__history-note">
                当前修订显示在首行；以下记录仅用于查看历史状态。
              </div>
            </div>

            <el-button v-if="hasMoreItems" link type="primary" :loading="loadingMore" @click="loadMore">
              加载更多工作项
            </el-button>

            <div v-if="projection.team" class="run-progress__team">
              <div class="run-progress__item-head">
                <strong>协作组</strong>
                <el-tag size="small" :type="stateTagType(projection.team.state)">
                  {{ teamStateLabel(projection.team.state) }}
                </el-tag>
              </div>
              <p v-if="projection.team.safeStopReasonCode" class="muted">
                核查原因：{{ projection.team.safeStopReasonCode }}
              </p>
              <div v-for="member in projection.team.memberSummaries" :key="member.roleId" class="run-progress__member">
                <span>{{ member.displayName }}</span>
                <span class="muted">{{ memberStateLabel(member.state) }}</span>
              </div>
              <div v-for="budget in projection.team.actionBudgets" :key="budget.actionType" class="run-progress__member">
                <span>{{ actionLabel(budget.actionType) }}</span>
                <span class="muted">已预留 {{ budget.reserved }}<template v-if="budget.limit !== null"> / {{ budget.limit }}</template></span>
              </div>
            </div>
          </template>
        </div>
      </el-collapse-item>
    </el-collapse>

    <el-dialog v-model="resultDialogOpen" title="工作项结果" width="min(760px, 92vw)" destroy-on-close>
      <p v-if="resultLoading" class="muted" role="status">正在读取修订结果…</p>
      <div v-else-if="resultError" class="run-progress__notice" role="status">
        <span>{{ resultError }}</span>
        <el-button v-if="retryableResultError" link type="primary" :loading="resultLoading" @click="retryResult">
          重试
        </el-button>
      </div>
      <div v-else-if="result" class="run-progress__result-body">
        <MarkdownMessage v-if="result.mediaType === 'text/markdown'" :content="result.body" sanitize-sensitive />
        <pre v-else>{{ sanitizeDisplayText(result.body) }}</pre>
      </div>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import MarkdownMessage from './MarkdownMessage.vue'
import { sanitizeDisplayText } from '../../utils/sanitizeDisplayText'
import {
  getRunProgress,
  getRunWorkItem,
  getRunWorkItemResult,
  getRunWorkItems,
  type RunProgressProjection,
  type RunWorkItemDetail,
  type RunWorkItemResult,
  type WorkItemRevisionSummary,
  type WorkItemSummary,
} from '../../api/runProgress'
import {
  canOpenWorkItemResult,
  progressResultFailureText,
  progressStateLabel,
  reloadLoadedWorkItemPages,
  workItemResultKey,
} from '../../presenters/runProgressPresenter'

const props = withDefaults(defineProps<{
  runId: string
  active?: boolean
  refreshRunId?: string
  refreshVersion?: number
}>(), { active: false, refreshRunId: '', refreshVersion: 0 })

const expanded = ref<string[]>([])
const projection = ref<RunProgressProjection>()
const items = ref<WorkItemSummary[]>([])
const loading = ref(false)
const loadError = ref(false)
const loadingMore = ref(false)
const hasMoreItems = ref(false)
const pageInitialized = ref(false)
let loadedPageCount = 0
let pageRefreshNeeded = false
const nextCursor = ref<string | null>(null)
const workItemDetails = ref<Record<string, RunWorkItemDetail>>({})
const historyExpanded = ref<Record<string, boolean>>({})
const historyLoading = ref<Record<string, boolean>>({})
const historyErrors = ref<Record<string, string>>({})
const unavailableResultKeys = ref<Set<string>>(new Set())
const resultDialogOpen = ref(false)
const resultLoading = ref(false)
const resultError = ref('')
const retryableResultError = ref(false)
const result = ref<RunWorkItemResult>()
const selectedResult = ref<{ workItemRef: string; assignmentRevision: number }>()

let requestGeneration = 0
let refreshPromise: Promise<void> | null = null
let refreshRunId = ''
let refreshDirty = false
let refreshTimer: number | undefined

watch(() => props.runId, () => {
  requestGeneration++
  projection.value = undefined
  items.value = []
  loading.value = false
  loadError.value = false
  loadingMore.value = false
  hasMoreItems.value = false
  pageInitialized.value = false
  loadedPageCount = 0
  pageRefreshNeeded = false
  nextCursor.value = null
  workItemDetails.value = {}
  historyExpanded.value = {}
  historyLoading.value = {}
  historyErrors.value = {}
  unavailableResultKeys.value = new Set()
  resultDialogOpen.value = false
  result.value = undefined
  void refresh()
}, { immediate: true })

watch(() => [props.refreshRunId, props.refreshVersion], () => {
  if (props.runId && props.refreshRunId === props.runId) void refresh()
})

watch(() => props.active, active => {
  if (refreshTimer !== undefined) window.clearInterval(refreshTimer)
  refreshTimer = undefined
  if (active && props.runId) refreshTimer = window.setInterval(() => { void refresh() }, 3000)
}, { immediate: true })

onBeforeUnmount(() => {
  requestGeneration++
  if (refreshTimer !== undefined) window.clearInterval(refreshTimer)
})

function refresh(): Promise<void> {
  const targetRunId = props.runId
  if (!targetRunId) return Promise.resolve()
  if (refreshPromise && refreshRunId === targetRunId) {
    refreshDirty = true
    return refreshPromise
  }
  const generation = requestGeneration
  refreshRunId = targetRunId
  refreshDirty = false
  loading.value = true
  const pending = (async () => {
    do {
      refreshDirty = false
      try {
        const response = await getRunProgress(targetRunId, projection.value?.version)
        if (!current(targetRunId, generation)) return
        const projectionChanged = !!response.projection
          && response.projection.version !== projection.value?.version
        if (response.projection) applyProjection(response.projection)
        if (pageInitialized.value && (projectionChanged || pageRefreshNeeded)) {
          if (loadingMore.value) {
            pageRefreshNeeded = true
            loadError.value = false
          } else {
            try {
              await refreshLoadedPages(targetRunId, generation)
              if (!current(targetRunId, generation)) return
              pageRefreshNeeded = false
              loadError.value = false
            } catch {
              if (!current(targetRunId, generation)) return
              pageRefreshNeeded = true
              loadError.value = true
            }
          }
        } else {
          loadError.value = false
        }
      } catch {
        if (!current(targetRunId, generation)) return
        loadError.value = true
      }
    } while (refreshDirty && current(targetRunId, generation))
  })()
  const tracked = pending.finally(() => {
    if (refreshPromise === tracked) {
      refreshPromise = null
      refreshRunId = ''
      loading.value = false
    }
  })
  refreshPromise = tracked
  return tracked
}

function applyProjection(next: RunProgressProjection) {
  projection.value = next
  if (!pageInitialized.value) {
    items.value = [...next.workItemsPreview]
    hasMoreItems.value = next.hasMoreWorkItems
    return
  }
  const merged = new Map(items.value.map(item => [item.workItemRef, item]))
  for (const item of next.workItemsPreview) merged.set(item.workItemRef, item)
  items.value = [...merged.values()]
}

async function loadMore() {
  if (loadingMore.value || !props.runId || !hasMoreItems.value) return
  const targetRunId = props.runId
  const generation = requestGeneration
  const firstPage = !pageInitialized.value
  loadingMore.value = true
  try {
    const page = await getRunWorkItems(targetRunId, pageInitialized.value ? nextCursor.value : null, 100)
    if (!current(targetRunId, generation)) return
    if (firstPage) {
      items.value = [...page.items]
      loadedPageCount = 1
    } else {
      const merged = new Map(items.value.map(item => [item.workItemRef, item]))
      for (const item of page.items) merged.set(item.workItemRef, item)
      items.value = [...merged.values()]
      loadedPageCount++
    }
    pageInitialized.value = true
    nextCursor.value = page.nextCursor
    hasMoreItems.value = page.hasMore
  } catch {
    if (current(targetRunId, generation)) loadError.value = true
  } finally {
    if (current(targetRunId, generation)) {
      loadingMore.value = false
      if (pageRefreshNeeded) void refresh()
    }
  }
}

async function refreshLoadedPages(targetRunId: string, generation: number) {
  const pageCount = loadedPageCount
  if (pageCount < 1) return
  loadingMore.value = true
  try {
    const refreshed = await reloadLoadedWorkItemPages(pageCount,
      (cursor, limit) => getRunWorkItems(targetRunId, cursor, limit))
    if (!current(targetRunId, generation)) return
    items.value = refreshed.items
    loadedPageCount = refreshed.loadedPageCount
    nextCursor.value = refreshed.nextCursor
    hasMoreItems.value = refreshed.hasMore
  } finally {
    if (current(targetRunId, generation)) loadingMore.value = false
  }
}

async function loadHistory(item: WorkItemSummary) {
  const targetRunId = props.runId
  const generation = requestGeneration
  historyLoading.value = { ...historyLoading.value, [item.workItemRef]: true }
  historyErrors.value = { ...historyErrors.value, [item.workItemRef]: '' }
  try {
    const detail = await getRunWorkItem(targetRunId, item.workItemRef)
    if (!current(targetRunId, generation)) return
    workItemDetails.value = { ...workItemDetails.value, [item.workItemRef]: detail }
  } catch {
    if (current(targetRunId, generation))
      historyErrors.value = { ...historyErrors.value, [item.workItemRef]: '修订记录读取失败，请重试。' }
  } finally {
    if (current(targetRunId, generation))
      historyLoading.value = { ...historyLoading.value, [item.workItemRef]: false }
  }
}

async function toggleHistory(item: WorkItemSummary) {
  const next = !historyExpanded.value[item.workItemRef]
  historyExpanded.value = { ...historyExpanded.value, [item.workItemRef]: next }
  if (next && !workItemDetails.value[item.workItemRef]) await loadHistory(item)
}

function revisionRows(item: WorkItemSummary): WorkItemRevisionSummary[] {
  if (historyExpanded.value[item.workItemRef]) {
    const revisions = workItemDetails.value[item.workItemRef]?.revisions
    if (revisions?.length) return [...revisions].sort((left, right) => right.assignmentRevision - left.assignmentRevision)
  }
  return [item.latestRevision]
}

function isResultLoading(workItemRef: string, assignmentRevision: number): boolean {
  return resultLoading.value && selectedResult.value?.workItemRef === workItemRef
    && selectedResult.value.assignmentRevision === assignmentRevision
}

async function openResult(workItemRef: string, assignmentRevision: number) {
  selectedResult.value = { workItemRef, assignmentRevision }
  result.value = undefined
  resultError.value = ''
  retryableResultError.value = false
  resultDialogOpen.value = true
  await readResult()
}

async function retryResult() {
  await readResult()
}

async function readResult() {
  const selected = selectedResult.value
  if (!selected || !props.runId) return
  const targetRunId = props.runId
  const generation = requestGeneration
  resultLoading.value = true
  resultError.value = ''
  try {
    const loaded = await getRunWorkItemResult(targetRunId, selected.workItemRef, selected.assignmentRevision)
    if (!current(targetRunId, generation) || !sameResult(selected)) return
    if (loaded.runId !== targetRunId || loaded.workItemRef !== selected.workItemRef
        || loaded.assignmentRevision !== selected.assignmentRevision) {
      resultError.value = '结果读取失败，请重试。'
      retryableResultError.value = false
      return
    }
    result.value = loaded
  } catch (error) {
    if (!current(targetRunId, generation) || !sameResult(selected)) return
    const status = (error as { response?: { status?: number } } | undefined)?.response?.status
    resultError.value = progressResultFailureText(status)
    retryableResultError.value = status !== 404
    if (status === 404) {
      const key = workItemResultKey(selected.workItemRef, selected.assignmentRevision)
      unavailableResultKeys.value = new Set([...unavailableResultKeys.value, key])
    }
  } finally {
    if (current(targetRunId, generation) && sameResult(selected)) resultLoading.value = false
  }
}

function sameResult(selected: { workItemRef: string; assignmentRevision: number }): boolean {
  return selectedResult.value?.workItemRef === selected.workItemRef
    && selectedResult.value.assignmentRevision === selected.assignmentRevision
}

function current(targetRunId: string, generation: number): boolean {
  return props.runId === targetRunId && requestGeneration === generation
}

function stateTagType(state: string): 'success' | 'warning' | 'danger' | 'info' {
  if (['ACCEPTED', 'COMPLETED', 'IDLE'].includes(state)) return 'success'
  if (['RECOVERY_REQUIRED', 'UNKNOWN', 'NOT_STARTED'].includes(state)) return 'warning'
  if (['STOPPED', 'REJECTED', 'FAILED'].includes(state)) return 'danger'
  return 'info'
}

function teamStateLabel(state: string): string {
  return ({ RUNNING: '执行中', COMPLETED: '已收尾', RECOVERY_REQUIRED: '待核查' } as Record<string, string>)[state]
    ?? '状态待核查'
}

function memberStateLabel(state: string): string {
  return ({ RUNNING: '执行中', IDLE: '本轮已结束', STOPPED: '已停止', UNKNOWN: '状态暂无记录' } as Record<string, string>)[state]
    ?? '状态暂无记录'
}

function actionLabel(actionType: string): string {
  return ({ TASK_CREATED: '创建任务', MESSAGE_RECIPIENT: '消息接收方' } as Record<string, string>)[actionType]
    ?? '协作动作'
}

function formatState(state: string): string {
  return ({
    PENDING: '待检查', VALID: '格式通过', INVALID: '格式未通过', UNKNOWN: '状态待核查',
    ACCEPTED: '已验收', REVISION_REQUESTED: '需修订', REJECTED: '已拒绝',
  } as Record<string, string>)[state] ?? '状态待核查'
}
</script>

<style scoped>
.run-progress { margin: 12px 0 18px; border-radius: 12px; }
.run-progress__title { display: flex; align-items: center; gap: 10px; }
.run-progress__error-label { color: #b42318; font-size: 13px; }
.run-progress__body { display: grid; gap: 12px; }
.run-progress__budget, .run-progress__item-meta, .run-progress__revision-info,
.run-progress__member, .run-progress__notice { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.run-progress__budget { padding: 10px 12px; border-radius: 8px; background: #f6f8fc; }
.run-progress__item, .run-progress__team { padding: 12px; border: 1px solid #e7ebf2; border-radius: 10px; }
.run-progress__item-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.run-progress__item-meta, .run-progress__revision-info, .run-progress__member { color: #667085; font-size: 13px; }
.run-progress__item-meta { margin-top: 5px; }
.run-progress__revision { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 10px; padding-top: 9px; border-top: 1px solid #eef1f5; }
.run-progress__history-note { margin-top: 8px; color: #667085; font-size: 12px; }
.run-progress__notice { justify-content: space-between; color: #b42318; }
.run-progress__result-body { max-height: min(68vh, 720px); overflow: auto; overflow-wrap: anywhere; }
.run-progress__result-body pre { white-space: pre-wrap; overflow-wrap: anywhere; }
.muted { color: #667085; font-size: 13px; }
</style>

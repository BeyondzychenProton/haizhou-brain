<template>
  <AppLayout>
    <div class="session-page">
      <div class="page-head">
        <div>
          <el-button link @click="router.push('/app/employees')">← 返回</el-button>
          <h2>{{ session?.employeeName || '数字员工会话' }}</h2>
          <p class="muted">历史消息会保留；普通消息按顺序排队，运行中引导在下一次模型推理前生效。</p>
        </div>
        <el-button @click="inspector = true" :disabled="!run">运行详情</el-button>
      </div>
      <div class="conversation">
        <el-empty v-if="!messages.length" description="输入第一条消息，开始工作" />
        <div v-for="item in messages" :key="item.key" :class="['message', item.role]">
          <div class="bubble">{{ item.text }}<span v-if="item.pending" class="stream-caret">▋</span></div>
        </div>
        <div v-if="active" class="progress">
          {{ stateLabel(run?.state) }}<span v-if="run?.queuePosition">，队列第 {{ run.queuePosition }} 位</span>
          <span v-if="streamState === 'connecting'"> · 正在连接实时更新</span>
          <span v-else-if="streamState === 'interrupted' || streamState === 'polling'"> · 连接中断，正在同步</span>
        </div>
      </div>
      <div v-if="pendingTools.length" class="approvals">
        <el-alert type="warning" :closable="false" :title="`${pendingTools.length} 个工具调用等待你确认`"
                  description="批准后会继续执行；拒绝后本次运行会以自然语言收尾。" />
        <el-card v-for="tool in pendingTools" :key="tool.toolExecutionId" shadow="never" class="approval-card">
          <div class="approval-head">
            <b>{{ tool.toolName }}</b>
            <span class="muted">{{ formatTime(tool.createdAt) }}</span>
          </div>
          <pre class="approval-input">{{ prettyJson(tool.inputJson) }}</pre>
          <div class="approval-actions">
            <el-button type="primary" size="small" :loading="decidingId === tool.toolExecutionId"
                       @click="decide(tool, true)">批准</el-button>
            <el-button type="danger" plain size="small" :loading="decidingId === tool.toolExecutionId"
                       @click="decide(tool, false)">拒绝</el-button>
          </div>
        </el-card>
      </div>
      <el-collapse v-else-if="finishedTools.length" class="approvals">
        <el-collapse-item :title="`本次运行的工具调用记录（${finishedTools.length} 条）`">
          <div v-for="tool in finishedTools" :key="tool.toolExecutionId" class="done-tool">
            <el-tag size="small" :type="tool.approvalDecision === 'REJECTED' ? 'danger' : 'info'">
              {{ tool.toolName }} · {{ tool.state }}<template v-if="tool.approvalDecision"> · {{ tool.approvalDecision }}</template>
            </el-tag>
            <span class="muted">{{ formatTime(tool.updatedAt) }}</span>
          </div>
        </el-collapse-item>
      </el-collapse>

      <div v-if="run?.state === 'RUNNING'" class="guidance">
        <el-input v-model="guidance" maxlength="4000" placeholder="运行中引导：将在当前工具完成后的下一次模型推理前生效" />
        <el-button :disabled="!guidance.trim()" @click="sendGuidance">发送引导</el-button>
        <el-button type="danger" plain @click="cancel">打断当前运行</el-button>
      </div>
      <div v-else-if="run?.state === 'QUEUED'" class="guidance">
        <span class="muted">该消息正在排队，轮到它时会自动执行。</span>
        <el-button type="danger" plain @click="cancel">取消排队消息</el-button>
      </div>
      <div v-else-if="run?.state === 'WAITING_TOOL' || run?.state === 'WAITING_CONFIRMATION'" class="guidance">
        <span class="muted">{{ stateLabel(run.state) }}</span>
        <el-button type="danger" plain @click="cancel">取消本次运行</el-button>
      </div>
      <div class="composer">
        <el-input v-model="input" type="textarea" :rows="3" maxlength="4000" show-word-limit placeholder="输入消息；运行中消息会自动排队" @keydown.ctrl.enter="send" />
        <el-button type="primary" :loading="sending" :disabled="!input.trim()" @click="send">发送</el-button>
      </div>
      <el-drawer v-model="inspector" title="运行详情" size="420px">
        <el-descriptions v-if="run" :column="1" border>
          <el-descriptions-item label="Run ID">{{ run.runId }}</el-descriptions-item>
          <el-descriptions-item label="Session ID">{{ run.sessionId }}</el-descriptions-item>
          <el-descriptions-item label="Definition Version">{{ run.definitionVersionId }}</el-descriptions-item>
          <el-descriptions-item label="State">{{ run.state }}</el-descriptions-item>
          <el-descriptions-item label="实时连接">{{ streamStateLabel }}</el-descriptions-item>
          <el-descriptions-item label="队列位置">{{ run.queuePosition || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-timeline class="event-list">
          <el-timeline-item v-for="event in events" :key="`${event.runId}-${event.sequenceNo}`" :timestamp="event.createdAt">
            #{{ event.sequenceNo }} {{ event.type }}<div>{{ event.content }}</div>
          </el-timeline-item>
        </el-timeline>
      </el-drawer>
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '../../layouts/AppLayout.vue'
import * as api from '../../api/app'
import type { Run, RunEvent, Session, ToolExecution } from '../../api/app'
import { openRunStream, type RunStreamEvent } from '../../api/runStream'
import { mergeConversationEvent, presentTimeline, type ConversationItem } from '../../presenters/runEventPresenter'
import { ElMessage, ElMessageBox } from 'element-plus'
import { formatTime, isRetryable, notifyError } from '../../utils/notify'

const ACTIVE_STATES = new Set(['QUEUED', 'RUNNING', 'WAITING_TOOL', 'WAITING_CONFIRMATION', 'CANCELLING'])
const route = useRoute()
const router = useRouter()
const session = ref<Session>()
const run = ref<Run>()
const runs = ref<Run[]>([])
const events = ref<RunEvent[]>([])
const toolExecutions = ref<ToolExecution[]>([])
const messages = ref<ConversationItem[]>([])
const input = ref('')
const guidance = ref('')
const sending = ref(false)
const inspector = ref(false)
const decidingId = ref('')
const streamState = ref<'idle' | 'connecting' | 'live' | 'interrupted' | 'polling'>('idle')
const seenStreamEventIds = new Set<string>()
let closeStream: (() => void) | undefined
let fallbackTimer: number | undefined
let reconnectTimer: number | undefined
let refreshTimer: number | undefined
let statusTimer: number | undefined
let transportGeneration = 0
let reconnectAttempt = 0

const active = computed(() => !!run.value && ACTIVE_STATES.has(run.value.state))
const streamStateLabel = computed(() => ({
  idle: '未连接',
  connecting: '连接中',
  live: '实时',
  interrupted: '中断后补读',
  polling: '轮询降级',
})[streamState.value])
const sessionId = () => String(route.params.sessionId)
const lastSequence = (runId = run.value?.runId) => events.value
  .filter(event => !runId || event.runId === runId)
  .reduce((maximum, event) => Math.max(maximum, event.sequenceNo), 0)

async function loadTimeline() {
  const pending = messages.value.filter(item => item.pending)
  const rebuilt = presentTimeline(await api.timeline(sessionId()))
  for (const item of pending) {
    if (!rebuilt.some(candidate => candidate.key === item.key)) rebuilt.push(item)
  }
  messages.value = rebuilt
}

async function loadRuns(): Promise<Run | undefined> {
  runs.value = await api.listRuns(sessionId())
  for (const state of ['RUNNING', 'WAITING_TOOL', 'WAITING_CONFIRMATION', 'CANCELLING', 'QUEUED']) {
    const candidate = runs.value.find(item => item.state === state)
    if (candidate) return candidate
  }
  return undefined
}

function appendPersistentEvents(next: RunEvent[], expectedRunId = run.value?.runId) {
  if (!expectedRunId) return
  const known = new Set(events.value
    .filter(event => event.runId === expectedRunId)
    .map(event => `${event.runId}:${event.sequenceNo}`))
  for (const event of next.sort((left, right) => left.sequenceNo - right.sequenceNo)) {
    if (event.runId !== expectedRunId || run.value?.runId !== expectedRunId) continue
    const key = `${event.runId}:${event.sequenceNo}`
    if (known.has(key)) continue
    known.add(key)
    events.value.push(event)
    messages.value = mergeConversationEvent(messages.value, event)
  }
  events.value.sort((left, right) => left.sequenceNo - right.sequenceNo)
}

async function syncPersistentEvents(runId: string) {
  if (run.value?.runId !== runId) return
  let batch: RunEvent[]
  do {
    batch = await api.getEvents(runId, lastSequence(runId), 200)
    if (run.value?.runId !== runId) return
    appendPersistentEvents(batch, runId)
  } while (batch.length === 200 && run.value?.runId === runId)
}

async function loadToolExecutions() {
  const currentRunId = run.value?.runId
  if (!currentRunId) {
    toolExecutions.value = []
    return
  }
  try {
    const loaded = await api.listToolExecutions(currentRunId)
    if (run.value?.runId === currentRunId) toolExecutions.value = loaded
  } catch {
    if (run.value?.runId === currentRunId) toolExecutions.value = []
  }
}

function stopTransport() {
  transportGeneration++
  closeStream?.()
  closeStream = undefined
  if (fallbackTimer) window.clearTimeout(fallbackTimer)
  if (reconnectTimer) window.clearTimeout(reconnectTimer)
  if (refreshTimer) window.clearTimeout(refreshTimer)
  if (statusTimer) window.clearTimeout(statusTimer)
  fallbackTimer = undefined
  reconnectTimer = undefined
  refreshTimer = undefined
  statusTimer = undefined
  reconnectAttempt = 0
  streamState.value = 'idle'
}

async function activateRun(nextRun: Run) {
  if (run.value?.runId === nextRun.runId && streamState.value !== 'idle') return
  stopTransport()
  run.value = nextRun
  events.value = []
  toolExecutions.value = []
  seenStreamEventIds.clear()
  await Promise.all([syncPersistentEvents(nextRun.runId), loadToolExecutions()])
  const generation = transportGeneration
  connectRealtime(nextRun.runId, generation)
}

async function refreshCurrentRun(runId: string) {
  if (run.value?.runId !== runId) return
  const latest = await api.getRun(runId)
  if (run.value?.runId !== runId) return
  run.value = latest
  await loadToolExecutions()
  if (ACTIVE_STATES.has(latest.state)) return

  stopTransport()
  run.value = latest
  await loadTimeline()
  const inProgress = await loadRuns()
  if (inProgress) await activateRun(inProgress)
}

function scheduleRunRefresh(runId: string) {
  if (refreshTimer) window.clearTimeout(refreshTimer)
  refreshTimer = window.setTimeout(() => {
    refreshTimer = undefined
    void refreshCurrentRun(runId).catch(error => {
      if (!isRetryable(error)) stopTransport()
    })
  }, 80)
}

function handleStreamEvent(event: RunStreamEvent, runId: string) {
  if (run.value?.runId !== runId || event.runId !== runId) return

  if (event.durability === 'durable' && event.runSequence != null) {
    appendPersistentEvents([{
      runId: event.runId,
      sequenceNo: event.runSequence,
      type: event.type,
      content: event.payload.text ?? event.payload.delta ?? '',
      createdAt: event.occurredAt,
    }], runId)
    scheduleRunRefresh(runId)
    return
  }
  if (seenStreamEventIds.has(event.eventId)) return
  seenStreamEventIds.add(event.eventId)
  if (seenStreamEventIds.size > 2048) {
    const oldest = seenStreamEventIds.values().next().value
    if (oldest) seenStreamEventIds.delete(oldest)
  }
  messages.value = mergeConversationEvent(messages.value, event)
}

function connectRealtime(runId: string, generation: number) {
  if (generation !== transportGeneration || run.value?.runId !== runId || !active.value) return
  if (typeof EventSource === 'undefined') {
    streamState.value = 'polling'
    scheduleFallback(runId, generation, 0)
    return
  }

  closeStream?.()
  streamState.value = 'connecting'
  closeStream = openRunStream(runId, lastSequence(runId), {
    onOpen: () => {
      if (generation !== transportGeneration || run.value?.runId !== runId) return
      streamState.value = 'live'
      reconnectAttempt = 0
      if (fallbackTimer) window.clearTimeout(fallbackTimer)
      fallbackTimer = undefined
      scheduleStatusRefresh(runId, generation)
    },
    onEvent: event => handleStreamEvent(event, runId),
    onDisconnect: () => {
      void recoverDisconnectedStream(runId, generation)
    },
    onMalformedEvent: raw => console.warn('Ignored malformed run stream event', raw),
  })
}

async function recoverDisconnectedStream(runId: string, generation: number) {
  if (generation !== transportGeneration || run.value?.runId !== runId || !active.value) return
  closeStream = undefined
  if (statusTimer) window.clearTimeout(statusTimer)
  statusTimer = undefined
  streamState.value = 'interrupted'

  try {
    await syncPersistentEvents(runId)
    await refreshCurrentRun(runId)
  } catch (error) {
    if (!isRetryable(error)) {
      stopTransport()
      return
    }
  }

  if (generation !== transportGeneration || run.value?.runId !== runId || !active.value) return
  scheduleFallback(runId, generation, 0)
  scheduleReconnect(runId, generation)
}

function scheduleReconnect(runId: string, generation: number) {
  if (reconnectTimer || generation !== transportGeneration || !active.value) return
  const delay = Math.min(1000 * 2 ** reconnectAttempt, 10000)
  reconnectAttempt++
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = undefined
    connectRealtime(runId, generation)
  }, delay)
}

function scheduleStatusRefresh(runId: string, generation: number, delay = 5000) {
  if (statusTimer || generation !== transportGeneration || !active.value) return
  statusTimer = window.setTimeout(async () => {
    statusTimer = undefined
    if (generation !== transportGeneration || run.value?.runId !== runId || !isStreamLive()) return
    try {
      await refreshCurrentRun(runId)
    } catch (error) {
      if (!isRetryable(error)) {
        stopTransport()
        return
      }
    }
    if (generation === transportGeneration && run.value?.runId === runId && active.value && isStreamLive()) {
      scheduleStatusRefresh(runId, generation)
    }
  }, delay)
}

function isStreamLive() { return streamState.value === 'live' }

function scheduleFallback(runId: string, generation: number, delay = 1200) {
  if (fallbackTimer || generation !== transportGeneration || !active.value) return
  fallbackTimer = window.setTimeout(async () => {
    fallbackTimer = undefined
    if (generation !== transportGeneration || run.value?.runId !== runId || streamState.value === 'live') return
    try {
      await syncPersistentEvents(runId)
      await refreshCurrentRun(runId)
      if (generation === transportGeneration && run.value?.runId === runId && active.value && !isStreamLive()) {
        streamState.value = typeof EventSource === 'undefined' ? 'polling' : 'interrupted'
        scheduleFallback(runId, generation)
      }
    } catch (error) {
      if (!isRetryable(error)) {
        stopTransport()
        return
      }
      scheduleFallback(runId, generation)
    }
  }, delay)
}

async function load() {
  session.value = await api.getSession(sessionId())
  await loadTimeline()
  const inProgress = await loadRuns()
  if (inProgress) await activateRun(inProgress)
}

async function send() {
  const value = input.value.trim()
  if (!value) return
  sending.value = true
  try {
    const created = await api.createRun(sessionId(), value)
    input.value = ''
    await loadTimeline()
    const inProgress = await loadRuns()
    if (!run.value || !ACTIVE_STATES.has(run.value.state)) await activateRun(inProgress || created)
  } finally {
    sending.value = false
  }
}

async function sendGuidance() {
  if (!run.value || !guidance.value.trim()) return
  const runId = run.value.runId
  await api.guideRun(runId, guidance.value.trim())
  guidance.value = ''
  await syncPersistentEvents(runId)
}

async function cancel() {
  if (!run.value) return
  const runId = run.value.runId
  run.value = await api.cancelRun(runId)
  await syncPersistentEvents(runId)
  await refreshCurrentRun(runId)
}

const pendingTools = computed(() => toolExecutions.value.filter(
  item => item.state === 'APPROVAL_REQUIRED' && (!item.approvalDecision || item.approvalDecision === 'PENDING')))
const finishedTools = computed(() => toolExecutions.value.filter(item => !pendingTools.value.includes(item)))

async function decide(tool: ToolExecution, approve: boolean) {
  if (!run.value) return
  let reason: string | undefined
  if (!approve) {
    try {
      const prompt = await ElMessageBox.prompt('拒绝原因（选填，最多 500 字）', `拒绝「${tool.toolName}」`, {
        inputType: 'textarea',
        inputPlaceholder: '可以留空',
        inputValidator: value => String(value ?? '').length > 500 ? '不能超过 500 个字符' : true,
      })
      reason = String(prompt.value ?? '').trim() || undefined
    } catch {
      return
    }
  }
  decidingId.value = tool.toolExecutionId
  try {
    const result = await api.decideToolExecution(run.value.runId, tool.toolExecutionId, approve, reason)
    if (!result.decided) ElMessage.info('这个工具调用已经处理过了')
    else ElMessage.success(approve ? '已批准，正在继续执行' : '已拒绝')
    await Promise.all([loadToolExecutions(), refreshCurrentRun(run.value.runId)])
  } catch (error) {
    notifyError(error, approve ? '批准失败' : '拒绝失败')
  } finally {
    decidingId.value = ''
  }
}

function prettyJson(value: string): string {
  if (!value) return '（无入参）'
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

function stateLabel(state?: string) {
  return ({
    QUEUED: '正在排队',
    RUNNING: '正在处理',
    WAITING_TOOL: '正在等待工具执行',
    WAITING_CONFIRMATION: '正在等待人工确认',
    CANCELLING: '正在等待安全检查点取消',
    SUCCEEDED: '已完成',
    FAILED: '执行失败',
    CANCELLED: '已取消',
    EXPIRED: '已过期',
  } as Record<string, string>)[state || ''] || state
}

onMounted(() => void load())
onBeforeUnmount(stopTransport)
</script>

<style scoped>
.approvals { margin: 16px 0; }
.approval-card { margin-top: 10px; }
.approval-head { display: flex; justify-content: space-between; align-items: baseline; }
.approval-input { margin: 10px 0; padding: 12px; background: #f8fafc; border-radius: 8px; font-size: 12px;
  white-space: pre-wrap; word-break: break-all; max-height: 220px; overflow: auto; }
.approval-actions { display: flex; gap: 10px; }
.done-tool { display: flex; gap: 10px; align-items: center; padding: 4px 0; }
.stream-caret { color: #409eff; animation: blink 1s steps(1) infinite; }
@keyframes blink { 50% { opacity: 0; } }
</style>

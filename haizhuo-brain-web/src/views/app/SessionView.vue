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
        <div v-for="item in messages" :key="item.key" :class="['message', item.role]"><div class="bubble">{{ item.text }}</div></div>
        <div v-if="active" class="progress">{{ stateLabel(run?.state) }}<span v-if="run?.queuePosition">，队列第 {{ run.queuePosition }} 位</span></div>
      </div>
      <div v-if="run?.state === 'RUNNING'" class="guidance">
        <el-input v-model="guidance" maxlength="4000" placeholder="运行中引导：将在当前工具完成后的下一次模型推理前生效" />
        <el-button :disabled="!guidance.trim()" @click="sendGuidance">发送引导</el-button>
        <el-button type="danger" plain @click="cancel">打断当前运行</el-button>
      </div>
      <div v-else-if="run?.state === 'QUEUED'" class="guidance">
        <span class="muted">该消息正在排队，轮到它时会自动执行。</span>
        <el-button type="danger" plain @click="cancel">取消排队消息</el-button>
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
          <el-descriptions-item label="队列位置">{{ run.queuePosition || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-timeline class="event-list"><el-timeline-item v-for="event in events" :key="event.sequenceNo" :timestamp="event.createdAt">#{{ event.sequenceNo }} {{ event.type }}<div>{{ event.content }}</div></el-timeline-item></el-timeline>
      </el-drawer>
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '../../layouts/AppLayout.vue'
import * as api from '../../api/app'
import type { Run, RunEvent, Session } from '../../api/app'
import { present, type ConversationItem } from '../../presenters/runEventPresenter'

const route = useRoute(); const router = useRouter()
const session = ref<Session>(); const run = ref<Run>(); const runs = ref<Run[]>([]); const events = ref<RunEvent[]>([])
const messages = ref<ConversationItem[]>([]); const input = ref(''); const guidance = ref('')
const sending = ref(false); const inspector = ref(false); let timer: number | undefined
const activeStates = ['QUEUED', 'RUNNING', 'WAITING_CONFIRMATION', 'CANCELLING']
const active = computed(() => !!run.value && activeStates.includes(run.value.state))
const sessionId = () => String(route.params.sessionId)
async function loadTimeline() { messages.value = (await api.timeline(sessionId())).map(present).filter(Boolean) as ConversationItem[] }
async function loadRuns() {
  runs.value = await api.listRuns(sessionId())
  return runs.value.find(item => item.state === 'RUNNING' || item.state === 'CANCELLING') || runs.value.find(item => item.state === 'QUEUED')
}
async function load() {
  session.value = await api.getSession(sessionId()); await loadTimeline()
  const inProgress = await loadRuns()
  if (inProgress) { run.value = inProgress; events.value = []; await poll() }
}
async function send() {
  const value = input.value.trim(); if (!value) return; sending.value = true
  try {
    const created = await api.createRun(sessionId(), value); input.value = ''; await loadTimeline()
    const inProgress = await loadRuns()
    if (!run.value || !activeStates.includes(run.value.state)) { run.value = inProgress || created; events.value = []; await poll() }
  } finally { sending.value = false }
}
async function sendGuidance() { if (!run.value || !guidance.value.trim()) return; await api.guideRun(run.value.runId, guidance.value.trim()); guidance.value = ''; await loadTimeline() }
async function cancel() {
  if (!run.value) return
  run.value = await api.cancelRun(run.value.runId); await loadTimeline()
  const inProgress = await loadRuns()
  if (!active.value && inProgress) { run.value = inProgress; events.value = []; await poll() }
}
async function poll() {
  if (!run.value) return
  const pollingRunId = run.value.runId
  const next = await api.getEvents(pollingRunId, events.value.at(-1)?.sequenceNo || 0)
  if (next.length) events.value.push(...next)
  const latest = await api.getRun(pollingRunId)
  if (run.value?.runId !== pollingRunId) return
  run.value = latest; await loadTimeline()
  const inProgress = await loadRuns()
  if (!active.value && inProgress) { run.value = inProgress; events.value = []; timer = window.setTimeout(poll, 0); return }
  if (active.value) timer = window.setTimeout(poll, 800)
}
function stateLabel(state?: string) { return ({ QUEUED: '正在排队', RUNNING: '正在处理', CANCELLING: '正在等待安全检查点取消', SUCCEEDED: '已完成', FAILED: '执行失败', CANCELLED: '已取消' } as Record<string, string>)[state || ''] || state }
onMounted(load); onBeforeUnmount(() => { if (timer) clearTimeout(timer) })
</script>

<template>
  <section class="operations-panel">
    <header class="panel-head">
      <div>
        <h2>运维总览</h2>
        <p class="muted">展示当前实例实际装配状态。配置启用和组件已装载不代表外部服务健康。</p>
      </div>
      <el-button :loading="loading" @click="load">刷新总览</el-button>
    </header>

    <el-alert
      class="boundary-note"
      type="info"
      :closable="false"
      title="远端观测状态只有完成探测后才会显示健康。"
      description="评分接口的 accepted 仅表示评分进入本地队列，不代表 Langfuse 已持久化；本页面不会触发 Run、模型或渠道发送。"
    />

    <RuntimeReadinessPanel />

    <el-alert v-if="state === 'unavailable'" type="error" :closable="false" title="运维状态暂不可用，请稍后刷新。" />
    <el-alert v-else-if="state === 'permission-lost'" type="warning" :closable="false" title="当前账号已失去运维页面权限，请重新确认管理员身份。" />
    <el-skeleton v-else-if="state === 'loading'" :rows="5" animated />

    <template v-else-if="overview">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="读取时间">{{ formatTime(overview.observedAt) }}</el-descriptions-item>
        <el-descriptions-item label="当前实例标签">{{ overview.instanceLabel || '未配置安全实例标签' }}</el-descriptions-item>
        <el-descriptions-item label="AgentScope Java">{{ overview.runtime.agentScopeVersion }}</el-descriptions-item>
        <el-descriptions-item label="Agent Runtime 装载">{{ yesNo(overview.runtime.loaded) }}</el-descriptions-item>
      </el-descriptions>

      <h3>运行门槛</h3>
      <el-table :data="gateRows" row-key="name">
        <el-table-column prop="label" label="门槛" min-width="220" />
        <el-table-column label="当前配置" width="150">
          <template #default="scope">
            <el-tag :type="scope.row.enabled ? 'success' : 'info'">{{ scope.row.enabled ? '已启用' : '关闭' }}</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <h3>Worker 与调度器</h3>
      <el-table :data="overview.workers" row-key="kind">
        <el-table-column label="类型" min-width="150">
          <template #default="scope">{{ workerLabel(scope.row.kind) }}</template>
        </el-table-column>
        <el-table-column label="配置启用" width="120">
          <template #default="scope">{{ yesNo(scope.row.configuredEnabled) }}</template>
        </el-table-column>
        <el-table-column label="本实例已装载" width="145">
          <template #default="scope">{{ yesNo(scope.row.loaded) }}</template>
        </el-table-column>
        <el-table-column prop="safeInstanceLabel" label="实例标签" min-width="150">
          <template #default="scope">{{ scope.row.safeInstanceLabel || '未配置' }}</template>
        </el-table-column>
        <el-table-column label="租约 / 回收节奏" min-width="170">
          <template #default="scope">
            {{ scope.row.leaseTtlSeconds == null ? '—' : `${scope.row.leaseTtlSeconds}s` }} /
            {{ scope.row.reclaimEveryTicks ?? '—' }}
          </template>
        </el-table-column>
        <el-table-column label="最近实测" min-width="165">
          <template #default="scope">{{ scope.row.lastObservedAt ? formatTime(scope.row.lastObservedAt) : '未实测' }}</template>
        </el-table-column>
      </el-table>

      <h3>可观测性</h3>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="配置启用">{{ yesNo(overview.observability.configuredEnabled) }}</el-descriptions-item>
        <el-descriptions-item label="SDK 已装载">{{ yesNo(overview.observability.loaded) }}</el-descriptions-item>
        <el-descriptions-item label="采集正文">{{ yesNo(overview.observability.captureContentEnabled) }}</el-descriptions-item>
        <el-descriptions-item label="探测状态">
          <el-tag :type="probeTagType(overview.observability.probeStatus)">{{ probeLabel(overview.observability.probeStatus) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="最近探测">{{ overview.observability.lastProbeAt ? formatTime(overview.observability.lastProbeAt) : '没有实测记录' }}</el-descriptions-item>
        <el-descriptions-item label="安全错误码">{{ overview.observability.safeErrorCode || '—' }}</el-descriptions-item>
        <el-descriptions-item label="评分队列深度">{{ overview.observability.scoreQueueDepth ?? '未知' }}</el-descriptions-item>
      </el-descriptions>

      <h3>受保护的平台链接</h3>
      <el-empty v-if="overview.links.length === 0" description="部署尚未配置受保护的平台链接" />
      <ul v-else class="operations-links">
        <li v-for="link in safeLinks" :key="`${link.kind}:${link.url}`">
          <a :href="link.url" target="_blank" rel="noopener noreferrer">{{ link.label }}</a>
          <span class="muted"> · {{ link.kind }}</span>
        </li>
      </ul>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { operationsOverview, type OperationsOverview } from '../../api/operations'
import RuntimeReadinessPanel from './RuntimeReadinessPanel.vue'

type ViewState = 'loading' | 'ready' | 'unavailable' | 'permission-lost'
const state = ref<ViewState>('loading')
const loading = ref(false)
const overview = ref<OperationsOverview | null>(null)
let currentRequest: AbortController | null = null
let requestRevision = 0

const gateLabels: Record<string, string> = {
  'run-worker': 'Run 执行 Worker',
  'channel-worker': '渠道投递 Worker',
  'session-render-v3': 'Session 渲染 v3',
}
const gateRows = computed(() => Object.entries(overview.value?.runtime.profileGates ?? {})
  .map(([name, enabled]) => ({ name, label: gateLabels[name] ?? name, enabled })))
const safeLinks = computed(() => (overview.value?.links ?? []).filter(link => {
  try {
    return new URL(link.url).protocol === 'https:'
  } catch {
    return false
  }
}))

async function load() {
  currentRequest?.abort()
  const request = new AbortController()
  currentRequest = request
  const revision = ++requestRevision
  loading.value = true
  state.value = overview.value ? 'ready' : 'loading'
  try {
    overview.value = await operationsOverview(request.signal)
    if (revision === requestRevision) state.value = 'ready'
  } catch (error) {
    if (request.signal.aborted || revision !== requestRevision) return
    const statusCode = (error as { response?: { status?: number } }).response?.status
    state.value = statusCode === 401 || statusCode === 403 ? 'permission-lost' : 'unavailable'
  } finally {
    if (revision === requestRevision) loading.value = false
  }
}

function yesNo(value: boolean) { return value ? '是' : '否' }
function formatTime(value: string) { return new Date(value).toLocaleString() }
function workerLabel(kind: string) { return kind === 'RUN' ? 'Run 执行' : kind === 'CHANNEL_DELIVERY' ? '渠道投递' : kind }
function probeLabel(status: string) {
  return ({ HEALTHY: '健康（已实测）', UNAVAILABLE: '不可用', UNKNOWN: '未知（尚未实测）', NOT_CONFIGURED: '未配置' } as Record<string, string>)[status] ?? status
}
function probeTagType(status: string) {
  return status === 'HEALTHY' ? 'success' : status === 'UNAVAILABLE' ? 'danger' : 'info'
}

onMounted(load)
onBeforeUnmount(() => currentRequest?.abort())
</script>

<style scoped>
.operations-panel h3 { margin: 24px 0 12px; }
.operations-links { margin: 0; padding-left: 20px; }
.operations-links a { color: var(--el-color-primary); }
</style>

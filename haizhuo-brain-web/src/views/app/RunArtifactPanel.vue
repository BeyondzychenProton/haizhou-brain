<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  createMarkdownArtifact,
  downloadRunArtifact,
  listRunArtifacts,
  type RunArtifactItem,
} from '../../api/runArtifacts'

const props = withDefaults(defineProps<{
  runId: string
  exportAllowed?: boolean
  exportDisabledReason?: string
}>(), {
  exportAllowed: false,
  exportDisabledReason: '只有读取到完整正式结果后才能导出 Markdown。',
})
const items = ref<RunArtifactItem[]>([])
const busy = ref(false)
const exportingRunIds = ref(new Set<string>())
const exporting = computed(() => exportingRunIds.value.has(props.runId))
const downloadingId = ref('')
const errorMessage = ref('')
const featureUnavailable = ref(false)
const pendingRequestIds = new Map<string, string>()
let refreshGeneration = 0
let runGeneration = 0
const canExport = computed(() => props.exportAllowed && !busy.value && !exporting.value && !featureUnavailable.value)

function responseCode(error: unknown): string {
  return (error as { response?: { data?: { code?: string } } }).response?.data?.code ?? ''
}

function requestId(runId: string): string {
  let value = pendingRequestIds.get(runId)
  if (!value) {
    value = `markdown-${crypto.randomUUID()}`
    pendingRequestIds.set(runId, value)
  }
  return value
}

async function refresh(): Promise<void> {
  const runId = props.runId
  const generation = ++refreshGeneration
  if (!runId) {
    busy.value = false
    items.value = []
    return
  }
  busy.value = true
  errorMessage.value = ''
  try {
    const page = await listRunArtifacts(runId, null, 20)
    if (generation !== refreshGeneration || runId !== props.runId) return
    items.value = page.items
    featureUnavailable.value = false
  } catch (error) {
    if (generation !== refreshGeneration || runId !== props.runId) return
    const code = responseCode(error)
    featureUnavailable.value = code === 'ARTIFACT_FEATURE_NOT_AVAILABLE'
    errorMessage.value = featureUnavailable.value
      ? '成果物功能当前未启用。'
      : '成果物列表读取失败，请检查运行状态后重试。'
  } finally {
    if (generation === refreshGeneration && runId === props.runId) busy.value = false
  }
}

async function exportMarkdown(): Promise<void> {
  if (!canExport.value) return
  const runId = props.runId
  const id = requestId(runId)
  const generation = runGeneration
  if (exportingRunIds.value.has(runId)) return
  exportingRunIds.value = new Set([...exportingRunIds.value, runId])
  errorMessage.value = ''
  try {
    await createMarkdownArtifact(runId, id)
    if (pendingRequestIds.get(runId) === id) pendingRequestIds.delete(runId)
    if (generation === runGeneration && runId === props.runId) await refresh()
  } catch (error) {
    if (generation !== runGeneration || runId !== props.runId) return
    const code = responseCode(error)
    featureUnavailable.value = code === 'ARTIFACT_FEATURE_NOT_AVAILABLE'
    errorMessage.value = featureUnavailable.value
      ? '成果物功能当前未启用。'
      : '导出未确认；可重试，重试会复用同一请求标识。'
  } finally {
    const next = new Set(exportingRunIds.value)
    next.delete(runId)
    exportingRunIds.value = next
  }
}

async function download(item: RunArtifactItem): Promise<void> {
  if (item.state !== 'AVAILABLE' || downloadingId.value) return
  const runId = props.runId
  const generation = runGeneration
  downloadingId.value = item.artifactId
  errorMessage.value = ''
  try {
    await downloadRunArtifact(item.artifactId)
  } catch {
    if (generation === runGeneration && runId === props.runId) {
      await refresh()
      errorMessage.value = '文件暂不可用，列表已保留；可刷新后重试。'
    }
  } finally {
    if (generation === runGeneration && runId === props.runId) downloadingId.value = ''
  }
}

function formatSize(size: number): string {
  return size < 1024 ? `${size} B` : `${(size / 1024).toFixed(1)} KB`
}

watch(() => props.runId, () => {
  runGeneration += 1
  refreshGeneration += 1
  items.value = []
  errorMessage.value = ''
  featureUnavailable.value = false
  downloadingId.value = ''
  busy.value = false
  if (props.runId) void refresh()
})
onMounted(() => void refresh())
</script>

<template>
  <section class="artifact-panel" aria-labelledby="artifact-title">
    <header class="artifact-panel__header">
      <div>
        <h3 id="artifact-title">成果物</h3>
        <p>可将本次运行的完整正式结果导出为 Markdown 附件。</p>
      </div>
      <button type="button" :disabled="!canExport" :aria-describedby="!props.exportAllowed ? 'artifact-export-requirement' : undefined" @click="exportMarkdown">
        {{ exporting ? '正在导出…' : '导出 Markdown' }}
      </button>
      <button type="button" :disabled="busy" @click="refresh">刷新</button>
    </header>

    <p v-if="!props.exportAllowed" id="artifact-export-requirement" class="artifact-panel__message" role="status">
      {{ props.exportDisabledReason }}
    </p>

    <p v-if="errorMessage" class="artifact-panel__message" role="status">{{ errorMessage }}</p>
    <p v-else-if="busy && !items.length" class="artifact-panel__message" role="status">正在读取成果物…</p>
    <p v-else-if="busy" class="artifact-panel__message" role="status">正在刷新，已读取的成果物仍可下载。</p>
    <p v-else-if="items.length === 0" class="artifact-panel__empty">暂无成果物。</p>

    <ul v-if="items.length" class="artifact-panel__list">
      <li v-for="item in items" :key="item.artifactId" class="artifact-panel__item">
        <div>
          <strong>{{ item.title }}.md</strong>
          <span>{{ formatSize(item.byteSize) }} · {{ item.state === 'AVAILABLE' ? '可下载' : '文件暂不可用' }}</span>
        </div>
        <button type="button" :disabled="item.state !== 'AVAILABLE' || !!downloadingId"
                @click="download(item)">
          {{ downloadingId === item.artifactId ? '准备下载…' : '下载' }}
        </button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.artifact-panel { display: grid; gap: 12px; padding: 16px; border: 1px solid #dce3ea; border-radius: 12px; background: #fff; }
.artifact-panel__header, .artifact-panel__item { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.artifact-panel__header h3 { margin: 0; font-size: 15px; }
.artifact-panel__header p, .artifact-panel__item span, .artifact-panel__empty, .artifact-panel__message { margin: 4px 0 0; color: #64748b; font-size: 13px; }
.artifact-panel__list { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.artifact-panel__item { padding-top: 10px; border-top: 1px solid #edf1f5; }
.artifact-panel__item div { display: grid; gap: 2px; min-width: 0; }
.artifact-panel__item strong { overflow-wrap: anywhere; font-size: 14px; }
button { border: 1px solid #cbd5e1; border-radius: 8px; padding: 7px 12px; background: #f8fafc; color: #0f172a; cursor: pointer; }
button:disabled { cursor: not-allowed; opacity: .55; }
</style>

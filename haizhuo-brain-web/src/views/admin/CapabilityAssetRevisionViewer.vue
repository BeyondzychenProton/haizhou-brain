<template>
  <el-drawer v-model="drawerOpen" :title="`资产修订 · ${capabilityCode}`" size="88%" destroy-on-close>
    <div class="revision-layout">
      <aside class="history-pane">
        <div class="pane-heading">
          <strong>不可变修订</strong>
          <el-button size="small" :loading="historyLoading" @click="loadFirstPage">刷新</el-button>
        </div>
        <el-alert v-if="historyError" type="error" :closable="false" show-icon :title="historyError" />
        <el-skeleton v-if="historyLoading && !revisions.length" :rows="4" animated />
        <el-empty v-else-if="!revisions.length" description="尚无已发布修订" />
        <el-table v-else :data="revisions" size="small" highlight-current-row @row-click="onHistoryRow">
          <el-table-column label="修订" min-width="72">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="selectRevision(row.capabilityRevisionId)">
                {{ row.revision }}<span v-if="row.capabilityRevisionId === initialRevisionId"> · 当前</span>
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="发布时间" min-width="145">
            <template #default="{ row }">{{ formatDate(row.reviewedAt) }}</template>
          </el-table-column>
          <el-table-column label="文件" width="72" align="right">
            <template #default="{ row }">{{ row.fileCount }}</template>
          </el-table-column>
          <el-table-column label="大小" width="92" align="right">
            <template #default="{ row }">{{ formatBytes(row.totalBytes) }}</template>
          </el-table-column>
        </el-table>
        <el-button v-if="hasMore" class="load-more" :loading="loadingMore" @click="loadMore">加载更多修订</el-button>
      </aside>

      <main class="revision-content">
        <el-alert type="info" :closable="false"
                  title="修订正文为只读文本；查看、比较不会改动草稿或已发布版本。" />
        <el-alert v-if="revisionError" class="content-alert" type="error" :closable="false" show-icon
                  :title="revisionError">
          <template #default>
            <el-button link type="primary" @click="retrySelectedRevision">重试读取</el-button>
          </template>
        </el-alert>
        <el-skeleton v-if="revisionLoading" :rows="6" animated />
        <template v-else-if="selectedRevision">
          <el-descriptions :column="2" border size="small" class="revision-meta">
            <el-descriptions-item label="修订">{{ selectedRevision.revision }}</el-descriptions-item>
            <el-descriptions-item label="发布者 ID">{{ selectedRevision.reviewedBy }}</el-descriptions-item>
            <el-descriptions-item label="发布时间">{{ formatDate(selectedRevision.reviewedAt) }}</el-descriptions-item>
            <el-descriptions-item label="类型">{{ selectedRevision.type === 'SKILL' ? 'Skill' : '知识' }}</el-descriptions-item>
            <el-descriptions-item label="内容哈希" :span="2"><code>{{ selectedRevision.assetHash }}</code></el-descriptions-item>
          </el-descriptions>

          <div class="file-toolbar">
            <el-select v-model="selectedFilePath" placeholder="选择文件" style="min-width: 280px">
              <el-option v-for="file in selectedRevision.files" :key="file.relativePath"
                         :label="`${file.relativePath} · ${formatBytes(file.byteSize)}`" :value="file.relativePath" />
            </el-select>
            <el-button :disabled="!selectedFile" @click="copySelectedFile">复制原文</el-button>
            <el-switch v-model="showRaw" active-text="原始文本" inactive-text="Markdown 预览" />
          </div>
          <el-descriptions v-if="selectedFile" :column="2" border size="small" class="file-meta">
            <el-descriptions-item label="文件路径">{{ selectedFile.relativePath }}</el-descriptions-item>
            <el-descriptions-item label="媒体类型">{{ selectedFile.mediaType }}</el-descriptions-item>
            <el-descriptions-item label="字节数">{{ formatBytes(selectedFile.byteSize) }}</el-descriptions-item>
            <el-descriptions-item label="SHA-256"><code>{{ selectedFile.sha256 }}</code></el-descriptions-item>
          </el-descriptions>
          <el-empty v-if="!selectedFile" description="该修订没有可展示的文本文件" />
          <div v-else class="file-body">
            <pre v-if="showRaw || selectedFile.mediaType !== 'text/markdown; charset=utf-8'" class="raw-file">{{ selectedFile.content }}</pre>
            <MarkdownMessage v-else :content="selectedFile.content" />
          </div>
        </template>
        <el-empty v-else-if="!revisionLoading && !revisionError" description="从左侧选择一个修订" />

        <section class="compare-pane">
          <div class="pane-heading">
            <strong>修订比较</strong>
            <span class="muted">比较原始文件清单与正文</span>
          </div>
          <div class="compare-selectors">
            <el-select v-model="compareLeftId" placeholder="较早修订" clearable @change="() => loadComparison()">
              <el-option v-for="item in revisions" :key="`left-${item.capabilityRevisionId}`"
                         :label="`修订 ${item.revision}`" :value="item.capabilityRevisionId" />
            </el-select>
            <span>对比</span>
            <el-select v-model="compareRightId" placeholder="较新修订" clearable @change="() => loadComparison()">
              <el-option v-for="item in revisions" :key="`right-${item.capabilityRevisionId}`"
                         :label="`修订 ${item.revision}`" :value="item.capabilityRevisionId" />
            </el-select>
          </div>
          <el-skeleton v-if="comparisonLoading" :rows="2" animated />
          <el-alert v-else-if="compareError" type="error" :closable="false" show-icon :title="compareError" />
          <el-empty v-else-if="!comparisonReady" description="请选择两个不同的修订" />
          <template v-else>
            <el-empty v-if="!fileChanges.length" description="两个修订的文件内容相同" />
            <el-table v-else :data="fileChanges" size="small" border>
              <el-table-column prop="relativePath" label="文件" min-width="220" />
              <el-table-column label="变化" width="100">
                <template #default="{ row }">{{ changeLabel(row.kind) }}</template>
              </el-table-column>
              <el-table-column label="字节数" width="150">
                <template #default="{ row }">{{ formatByteChange(row.previousBytes, row.currentBytes) }}</template>
              </el-table-column>
              <el-table-column label="SHA-256" min-width="270" show-overflow-tooltip>
                <template #default="{ row }">
                  <span>旧：{{ row.previousHash ?? '—' }}</span><br />
                  <span>新：{{ row.currentHash ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="正文差异" width="112">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openFileChange(row)">
                    {{ row.kind === 'CHANGED' ? '查看文本差异' : '查看文件原文' }}
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
            <div v-if="selectedDiffPath" class="diff-view">
              <div class="pane-heading">
                <strong>{{ selectedDiffPath }}</strong>
                <el-button link @click="selectedDiffPath = ''">关闭差异</el-button>
              </div>
              <el-alert v-if="selectedChange && selectedChange.kind !== 'CHANGED'" type="info" :closable="false"
                        :title="selectedChange.kind === 'ADDED' ? '该文件仅存在于右侧修订；正文区已加载新文件原文。' : '该文件仅存在于左侧修订；正文区已加载旧文件原文。'" />
              <pre v-else class="diff-text"><span v-for="(line, index) in textDiff" :key="`${index}-${line.kind}`"
                   :class="`diff-${line.kind}`">{{ diffPrefix(line.kind) }}{{ line.text }}
</span></pre>
            </div>
          </template>
        </section>
      </main>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import * as assetApi from '../../api/capabilityAssets'
import type { CapabilityAssetFile, CapabilityAssetRevision, CapabilityAssetRevisionSummary } from '../../api/capabilityAssets'
import MarkdownMessage from '../../components/conversation/MarkdownMessage.vue'
import { compareAssetFiles, makeTextDiff } from '../../presenters/capabilityAssetDiff'
import type { AssetFileChange, TextDiffLine } from '../../presenters/capabilityAssetDiff'
import { notifyError, notifySuccess } from '../../utils/notify'

const props = defineProps<{
  modelValue: boolean
  capabilityCode: string
  initialRevisionId: number | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const drawerOpen = computed({ get: () => props.modelValue, set: (value: boolean) => emit('update:modelValue', value) })
const revisions = ref<CapabilityAssetRevisionSummary[]>([])
const revisionCache = ref(new Map<number, CapabilityAssetRevision>())
const selectedRevisionId = ref<number | null>(null)
const selectedFilePath = ref('')
const compareLeftId = ref<number | null>(null)
const compareRightId = ref<number | null>(null)
const selectedDiffPath = ref('')
const historyLoading = ref(false)
const loadingMore = ref(false)
const revisionLoading = ref(false)
const comparisonLoading = ref(false)
const hasMore = ref(false)
const nextCursor = ref<string | null>(null)
const historyError = ref('')
const revisionError = ref('')
const compareError = ref('')
const showRaw = ref(false)
let viewGeneration = 0
let comparisonGeneration = 0

const selectedRevision = computed(() => selectedRevisionId.value == null
  ? null : revisionCache.value.get(selectedRevisionId.value) ?? null)
const selectedFile = computed<CapabilityAssetFile | null>(() =>
  selectedRevision.value?.files.find(file => file.relativePath === selectedFilePath.value) ?? null)
const comparisonReady = computed(() => compareLeftId.value != null && compareRightId.value != null
  && compareLeftId.value !== compareRightId.value
  && revisionCache.value.has(compareLeftId.value) && revisionCache.value.has(compareRightId.value))
const comparison = computed(() => comparisonReady.value
  ? [revisionCache.value.get(compareLeftId.value!)!, revisionCache.value.get(compareRightId.value!)!] as const
  : null)
const fileChanges = computed<AssetFileChange[]>(() => comparison.value
  ? compareAssetFiles(comparison.value[0].files, comparison.value[1].files) : [])
const selectedChange = computed(() => fileChanges.value.find(change => change.relativePath === selectedDiffPath.value) ?? null)
const previousDiffFile = computed(() => comparison.value?.[0].files.find(file => file.relativePath === selectedChange.value?.relativePath) ?? null)
const currentDiffFile = computed(() => comparison.value?.[1].files.find(file => file.relativePath === selectedChange.value?.relativePath) ?? null)
const textDiff = computed<TextDiffLine[]>(() => selectedChange.value
  ? makeTextDiff(previousDiffFile.value?.content ?? '', currentDiffFile.value?.content ?? '') : [])

watch(() => props.modelValue, open => {
  if (open) void loadFirstPage()
  else {
    viewGeneration++
    comparisonGeneration++
    historyLoading.value = false
    loadingMore.value = false
    revisionLoading.value = false
    comparisonLoading.value = false
  }
})

watch(() => props.capabilityCode, () => {
  if (!props.modelValue) return
  resetView()
  void loadFirstPage()
})

async function loadFirstPage() {
  const generation = ++viewGeneration
  comparisonGeneration++
  loadingMore.value = false
  revisionLoading.value = false
  comparisonLoading.value = false
  historyLoading.value = true
  historyError.value = ''
  try {
    const page = await assetApi.listCapabilityAssetRevisions(props.capabilityCode, undefined, 20)
    if (!isCurrent(generation)) return
    revisions.value = page.items
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
    const currentId = props.initialRevisionId
    const firstId = page.items[0]?.capabilityRevisionId ?? null
    if (currentId != null) void selectRevision(currentId)
    else if (firstId != null) void selectRevision(firstId)
    if (page.items.length > 1) {
      compareLeftId.value ??= page.items[1].capabilityRevisionId
      compareRightId.value ??= page.items[0].capabilityRevisionId
      void loadComparison()
    }
  } catch (error) {
    if (isCurrent(generation)) {
      historyError.value = '修订历史加载失败，已有查看内容仍保留。'
      notifyError(error, historyError.value)
    }
  } finally {
    if (isCurrent(generation)) historyLoading.value = false
  }
}

async function loadMore() {
  const cursor = nextCursor.value
  if (!cursor || loadingMore.value) return
  const generation = viewGeneration
  loadingMore.value = true
  try {
    const page = await assetApi.listCapabilityAssetRevisions(props.capabilityCode, cursor, 20)
    if (!isCurrent(generation) || cursor !== nextCursor.value) return
    const existing = new Set(revisions.value.map(item => item.capabilityRevisionId))
    revisions.value = [...revisions.value, ...page.items.filter(item => !existing.has(item.capabilityRevisionId))]
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
  } catch (error) {
    if (isCurrent(generation)) {
      historyError.value = '加载更多修订失败，可重试。'
      notifyError(error, historyError.value)
    }
  } finally {
    if (isCurrent(generation)) loadingMore.value = false
  }
}

function onHistoryRow(row: CapabilityAssetRevisionSummary) {
  void selectRevision(row.capabilityRevisionId)
}

async function selectRevision(id: number, retry = false) {
  selectedRevisionId.value = id
  revisionError.value = ''
  if (!retry && revisionCache.value.has(id)) {
    const cached = revisionCache.value.get(id)!
    selectedFilePath.value = cached.files[0]?.relativePath ?? ''
    showRaw.value = false
    return
  }
  const generation = viewGeneration
  const code = props.capabilityCode
  revisionLoading.value = true
  try {
    const revision = await assetApi.getCapabilityAssetRevision(code, id)
    if (!isCurrent(generation) || props.capabilityCode !== code || selectedRevisionId.value !== id) return
    if (revision.capabilityCode !== code || revision.capabilityRevisionId !== id) {
      revisionError.value = '服务端返回的修订归属与当前资产不一致。'
      return
    }
    revisionCache.value.set(id, revision)
    selectedFilePath.value = revision.files[0]?.relativePath ?? ''
    showRaw.value = false
  } catch (error) {
    if (isCurrent(generation) && selectedRevisionId.value === id) {
      revisionError.value = '修订正文读取失败。'
      notifyError(error, revisionError.value)
    }
  } finally {
    if (isCurrent(generation) && selectedRevisionId.value === id) revisionLoading.value = false
  }
}

async function loadComparison() {
  const comparisonRequest = ++comparisonGeneration
  const ids = [compareLeftId.value, compareRightId.value].filter((id): id is number => id != null && id > 0)
  if (ids.length !== 2 || ids[0] === ids[1]) {
    selectedDiffPath.value = ''
    compareError.value = ''
    comparisonLoading.value = false
    return
  }
  const generation = viewGeneration
  const code = props.capabilityCode
  comparisonLoading.value = true
  compareError.value = ''
  try {
    await Promise.all(ids.map(id => ensureRevision(id, code, generation)))
    if (!isCurrent(generation) || comparisonRequest !== comparisonGeneration
      || compareLeftId.value !== ids[0] || compareRightId.value !== ids[1]) return
    if (revisionCache.value.has(ids[0]) && revisionCache.value.has(ids[1])) {
      selectedDiffPath.value = ''
    }
  } catch (error) {
    if (isCurrent(generation) && comparisonRequest === comparisonGeneration) {
      compareError.value = '比较所需的修订正文读取失败。'
      notifyError(error, compareError.value)
    }
  } finally {
    if (isCurrent(generation) && comparisonRequest === comparisonGeneration) comparisonLoading.value = false
  }
}

async function ensureRevision(id: number, code: string, generation: number) {
  if (revisionCache.value.has(id)) return
  const revision = await assetApi.getCapabilityAssetRevision(code, id)
  if (!isCurrent(generation) || props.capabilityCode !== code) return
  if (revision.capabilityCode !== code || revision.capabilityRevisionId !== id)
    throw new Error('Revision does not belong to this asset')
  revisionCache.value.set(id, revision)
}

function openFileChange(change: AssetFileChange) {
  selectedDiffPath.value = change.relativePath
  if (change.kind !== 'CHANGED') void showComparedFile(change.relativePath, change.kind)
}

async function showComparedFile(path: string, kind: 'ADDED' | 'REMOVED') {
  const revisionId = kind === 'ADDED' ? compareRightId.value : compareLeftId.value
  if (revisionId == null) return
  await selectRevision(revisionId)
  if (selectedRevisionId.value === revisionId
    && selectedRevision.value?.files.some(file => file.relativePath === path)) selectedFilePath.value = path
}

async function copySelectedFile() {
  if (!selectedFile.value) return
  try {
    if (!navigator.clipboard?.writeText) throw new Error('Clipboard API is unavailable')
    await navigator.clipboard.writeText(selectedFile.value.content)
    notifySuccess('已复制原文')
  } catch (error) {
    notifyError(error, '复制失败，请选择原始文本后手动复制')
  }
}

function resetView() {
  viewGeneration++
  comparisonGeneration++
  revisions.value = []
  revisionCache.value = new Map()
  selectedRevisionId.value = null
  selectedFilePath.value = ''
  compareLeftId.value = null
  compareRightId.value = null
  selectedDiffPath.value = ''
  nextCursor.value = null
  hasMore.value = false
  historyError.value = ''
  revisionError.value = ''
  compareError.value = ''
  historyLoading.value = false
  loadingMore.value = false
  revisionLoading.value = false
  comparisonLoading.value = false
  showRaw.value = false
}

function retrySelectedRevision() {
  if (selectedRevisionId.value != null) void selectRevision(selectedRevisionId.value, true)
}

function isCurrent(generation: number) {
  return generation === viewGeneration && props.modelValue
}

function formatDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`
  return `${(value / (1024 * 1024)).toFixed(2)} MiB`
}

function formatByteChange(previous: number | null, current: number | null) {
  return `${previous == null ? '—' : formatBytes(previous)} → ${current == null ? '—' : formatBytes(current)}`
}

function changeLabel(kind: AssetFileChange['kind']) {
  return kind === 'ADDED' ? '新增' : kind === 'REMOVED' ? '删除' : '变化'
}

function diffPrefix(kind: TextDiffLine['kind']) {
  return kind === 'removed' ? '− ' : kind === 'added' ? '+ ' : '  '
}
</script>

<style scoped>
.revision-layout { display: grid; grid-template-columns: minmax(310px, 34%) minmax(0, 1fr); gap: 20px; min-height: 65vh; }
.history-pane { min-width: 0; padding-right: 14px; border-right: 1px solid var(--el-border-color-lighter); }
.revision-content { min-width: 0; }
.pane-heading, .file-toolbar, .compare-selectors { display: flex; align-items: center; gap: 10px; }
.pane-heading { justify-content: space-between; margin: 4px 0 12px; }
.load-more { width: 100%; margin-top: 10px; }
.content-alert, .revision-meta { margin-top: 14px; }
.file-meta { margin: 12px 0; }
.file-toolbar { flex-wrap: wrap; margin: 18px 0 10px; }
.file-body { max-height: 46vh; overflow: auto; padding: 14px; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; }
.raw-file, .diff-text { margin: 0; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.65 ui-monospace, SFMono-Regular, Consolas, monospace; }
.compare-pane { margin-top: 24px; padding-top: 18px; border-top: 1px solid var(--el-border-color-lighter); }
.compare-selectors { margin-bottom: 12px; }
.compare-selectors .el-select { width: 220px; }
.diff-view { margin-top: 14px; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; }
.diff-view .pane-heading { padding: 0 12px; }
.diff-text { max-height: 38vh; padding: 12px; background: #f8fafc; }
.diff-removed { display: block; color: #b42318; background: #fef3f2; }
.diff-added { display: block; color: #067647; background: #ecfdf3; }
.diff-same { display: block; color: #475467; }
.muted { color: var(--el-text-color-secondary); font-size: 12px; }
@media (max-width: 900px) { .revision-layout { grid-template-columns: 1fr; } .history-pane { border-right: 0; padding-right: 0; } }
</style>

<template>
  <section class="render-shell">
    <div ref="scroller" class="render-scroll" @scroll="onScroll">
      <div v-if="loading && !displayProjection?.items.length" class="render-loading">正在恢复会话…</div>
      <el-alert v-else-if="error" type="warning" :closable="false" show-icon title="实时恢复暂不可用">
        <template #default><span>当前展示保留已读取内容，可稍后重试。</span></template>
      </el-alert>
      <div v-if="canLoadOlder" class="older-row">
        <el-button text :loading="loading" @click="loadOlderKeepingAnchor">加载更早消息</el-button>
      </div>
      <div v-if="displayProjection?.draftRecoveryStatus === 'DEGRADED'" class="recovery-note">
        过程展示不完整，已提交的部分内容保留；请以正式结果或运行核查为准。
      </div>
      <article v-for="item in displayProjection?.items ?? []" :key="item.messageId"
        class="render-item" :class="item.kind.toLowerCase()" :data-message-id="item.messageId">
        <div v-if="item.kind === 'USER_INPUT'" class="render-user">
          <pre>{{ sanitizeDisplayText(item.text) }}</pre>
        </div>
        <div v-else-if="item.kind === 'ROOT_FINAL'" class="render-assistant">
          <div class="render-heading">
            <span>{{ item.executorRoleId || '数字员工' }}</span>
            <span v-if="item.legacySummary" class="partial-label">历史摘要</span>
          </div>
          <MarkdownMessage v-if="!item.mediaType || item.mediaType === 'text/markdown'" :content="item.text" sanitize-sensitive />
          <pre v-else class="plain-result">{{ sanitizeDisplayText(item.text) }}</pre>
          <el-button v-if="item.resultLoadState === 'failed'" link type="primary" @click="emit('retryResult', item.runId)">
            重试读取完整结果
          </el-button>
        </div>
        <details v-else-if="item.kind === 'ASSISTANT_DRAFT'" class="render-draft" :open="isActive(item.phase)">
          <summary>
            {{ item.executorRoleId || '执行过程' }} · {{ phaseLabel(item.phase) }}
            <span v-if="item.partial" class="partial-label">部分内容</span>
          </summary>
          <div v-for="block in item.blocks" :key="block.blockId" class="draft-block" :data-block-id="block.blockId">
            <MarkdownMessage v-if="block.type === 'TEXT'" :content="block.text" sanitize-sensitive />
            <pre v-else>{{ sanitizeDisplayText(block.text) }}</pre>
          </div>
        </details>
        <div v-else class="render-status">
          <span>{{ phaseLabel(item.itemType) }}</span>
          <p>{{ sanitizeDisplayText(item.text) }}</p>
        </div>
      </article>
      <div v-if="displayProjection && !displayProjection.items.length && !loading" class="render-empty">
        这个会话还没有消息。
      </div>
    </div>
    <div v-if="unreadCount > 0 && !followingLatest" class="latest-chip">
      <el-button size="small" round @click="scrollToLatest">有 {{ unreadCount }} 条新内容 · 回到最新</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import MarkdownMessage from './MarkdownMessage.vue'
import type { RenderProjection } from '../../presenters/sessionRenderPresenter'
import { sanitizeDisplayText } from '../../utils/sanitizeDisplayText'

const props = defineProps<{
  projection: RenderProjection | null
  loading: boolean
  error: unknown
  canLoadOlder: boolean
  loadOlder: () => Promise<boolean>
}>()
const emit = defineEmits<{ retryResult: [runId: string] }>()

const scroller = ref<HTMLElement>()
const displayProjection = shallowRef<RenderProjection | null>(null)
const unreadCount = ref(0)
const followingLatest = ref(true)
let flushTimer: number | undefined
let scheduledAnchor: { messageId: string | null; top: number; height: number; bottom: boolean } | undefined
let lastFlushAt = 0
const unreadMessageIds = new Set<string>()

watch(() => props.projection, next => scheduleDisplay(next), { immediate: true, flush: 'pre' })
onBeforeUnmount(() => { if (flushTimer !== undefined) window.clearTimeout(flushTimer) })

function scheduleDisplay(next: RenderProjection | null) {
  captureAnchor()
  const existingFinals = new Map((displayProjection.value?.items ?? [])
    .filter(item => item.kind === 'ROOT_FINAL').map(item => [item.messageId,
      item.bodySha256 ?? item.resultId ?? item.text]))
  const canonicalChanged = next?.items.some(item => item.kind === 'ROOT_FINAL'
    && item.bodySource === 'canonical-result'
    && existingFinals.get(item.messageId) !== (item.bodySha256 ?? item.resultId ?? item.text))
  const delay = canonicalChanged ? 0 : Math.max(0, 50 - (Date.now() - lastFlushAt))
  if (flushTimer !== undefined) window.clearTimeout(flushTimer)
  flushTimer = window.setTimeout(() => {
    flushTimer = undefined
    const oldProjection = displayProjection.value
    displayProjection.value = next
    lastFlushAt = Date.now()
    const changedMessageIds = unreadChanges(oldProjection, next)
    void nextTick(() => restoreAnchor(changedMessageIds))
  }, delay)
}

function captureAnchor() {
  const element = scroller.value
  if (!element) return
  const rect = element.getBoundingClientRect()
  const bottom = element.scrollHeight - element.scrollTop - element.clientHeight <= 80
  const visible = [...element.querySelectorAll<HTMLElement>('[data-message-id]')]
    .find(item => item.getBoundingClientRect().bottom > rect.top)
  scheduledAnchor = {
    messageId: visible?.dataset.messageId ?? null,
    top: visible ? visible.getBoundingClientRect().top - rect.top : 0,
    height: element.scrollHeight,
    bottom,
  }
}

function restoreAnchor(changedMessageIds: string[]) {
  const element = scroller.value
  const anchor = scheduledAnchor
  scheduledAnchor = undefined
  if (!element || !anchor) return
  if (anchor.bottom) {
    element.scrollTop = element.scrollHeight
    followingLatest.value = true
    unreadMessageIds.clear()
    unreadCount.value = 0
    return
  }
  if (anchor.messageId) {
    const candidate = [...element.querySelectorAll<HTMLElement>('[data-message-id]')]
      .find(item => item.dataset.messageId === anchor.messageId)
    if (candidate) {
      const rect = element.getBoundingClientRect()
      element.scrollTop += candidate.getBoundingClientRect().top - rect.top - anchor.top
    }
  }
  followingLatest.value = false
  for (const messageId of changedMessageIds) unreadMessageIds.add(messageId)
  unreadCount.value = unreadMessageIds.size
}

function onScroll() {
  const element = scroller.value
  if (!element) return
  followingLatest.value = element.scrollHeight - element.scrollTop - element.clientHeight <= 80
  if (followingLatest.value) {
    unreadMessageIds.clear()
    unreadCount.value = 0
  }
}

function scrollToLatest() {
  const element = scroller.value
  if (!element) return
  element.scrollTop = element.scrollHeight
  followingLatest.value = true
  unreadMessageIds.clear()
  unreadCount.value = 0
}

function unreadChanges(previous: RenderProjection | null, next: RenderProjection | null): string[] {
  if (!previous || !next || previous.sessionId !== next.sessionId) return []
  const oldItems = new Map(previous.items.map(item => [item.messageId, item]))
  const changed: string[] = []
  next.items.forEach((item, index) => {
    const old = oldItems.get(item.messageId)
    if (!old) {
      // 历史分页会在列表前方插入旧内容，只有旧可见尾项之后的新增内容才算新动态。
      if (index >= previous.items.length) changed.push(item.messageId)
      return
    }
    if (old.text !== item.text || old.phase !== item.phase
        || old.partial !== item.partial || old.bodySource !== item.bodySource
        || old.pending !== item.pending || old.resultId !== item.resultId
        || old.resultLoadState !== item.resultLoadState) changed.push(item.messageId)
  })
  return changed
}

async function loadOlderKeepingAnchor() {
  const element = scroller.value
  if (!element) return
  const oldHeight = element.scrollHeight
  const oldTop = element.scrollTop
  if (await props.loadOlder()) {
    await nextTick()
    element.scrollTop = oldTop + element.scrollHeight - oldHeight
  }
}

function isActive(phase?: string | null) {
  return ['GENERATING', 'WAITING_TOOL', 'WAITING_CONFIRMATION', 'CANCELLING'].includes(phase ?? '')
}

function phaseLabel(phase?: string | null) {
  const labels: Record<string, string> = {
    GENERATING: '生成中', generating: '生成中', COMPLETE: '已完成', FAILED: '失败', CANCELLED: '已取消',
    CANCELLING: '取消处理中', WAITING_TOOL: '等待工具', WAITING_CONFIRMATION: '等待确认',
    RECOVERY_REQUIRED: '待核查', RUN_RECOVERY_REQUIRED: '待核查', RUN_QUEUED: '排队中',
    RUN_STARTED: '已开始', RUN_FAILED: '失败', RUN_CANCELLED: '已取消', RUN_COMPLETED: '已完成',
  }
  return labels[phase ?? ''] ?? phase ?? '状态更新'
}
</script>

<style scoped>
.render-shell { position: relative; min-height: 220px; height: 100%; }
.render-scroll { height: 100%; min-height: 220px; overflow: auto; padding: 16px 20px 28px; scroll-behavior: auto; }
.render-item { margin: 0 0 16px; scroll-margin-top: 12px; }
.render-user { margin-left: auto; max-width: min(78%, 760px); padding: 11px 14px; border-radius: 14px 14px 4px 14px; background: #eaf2ff; }
.render-user pre, .plain-result { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; }
.render-assistant { position: relative; max-width: 900px; padding: 12px 14px; border: 1px solid #e4e9f0; border-radius: 12px; background: #fff; }
.render-heading { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 8px; color: #667085; font-size: 12px; }
.render-draft { max-width: 900px; padding: 10px 13px; border-left: 3px solid #b7c6df; border-radius: 4px 10px 10px 4px; background: #f7f9fc; color: #667085; }
.render-draft summary { cursor: pointer; font-size: 12px; }
.draft-block { margin-top: 8px; color: #475467; }
.render-status, .recovery-note { max-width: 900px; padding: 9px 12px; border-radius: 8px; background: #fff8e6; color: #805b12; font-size: 13px; }
.render-status p { margin: 4px 0 0; white-space: pre-wrap; }
.recovery-note { margin: 0 auto 12px; background: #fff3e7; }
.partial-label { color: #a15c00; font-size: 12px; }
.render-loading, .render-empty { padding: 24px; color: #667085; text-align: center; }
.older-row { display: flex; justify-content: center; margin: 0 0 12px; }
.latest-chip { position: absolute; right: 18px; bottom: 12px; }
</style>

<template>
  <section class="readiness-panel" aria-labelledby="readiness-title">
    <header class="panel-head">
      <div>
        <h3 id="readiness-title">功能开放门槛</h3>
        <p class="muted">配置状态和本部署的验证证据分开显示。查询不会打开门槛或触发业务执行。</p>
      </div>
      <el-button :loading="loading" @click="load">刷新门槛</el-button>
    </header>

    <el-alert v-if="state === 'unavailable'" type="error" :closable="false" title="门槛状态暂不可用，请稍后刷新。" />
    <el-alert v-else-if="state === 'permission-lost'" type="warning" :closable="false" title="当前账号已失去管理员权限，请重新确认身份。" />
    <el-skeleton v-else-if="state === 'loading'" :rows="4" animated />

    <template v-else-if="readiness">
      <el-table :data="readiness.items" row-key="gateId" size="small">
        <el-table-column label="能力门槛" min-width="205">
          <template #default="scope">{{ gateLabel(scope.row.gateId) }}</template>
        </el-table-column>
        <el-table-column label="配置" width="100">
          <template #default="scope">{{ scope.row.configured ? '已配置' : '未配置' }}</template>
        </el-table-column>
        <el-table-column label="验证记录" min-width="160">
          <template #default="scope">
            <el-tag :type="scope.row.verificationMatched ? 'success' : 'info'">
              {{ scope.row.verificationMatched ? '匹配本部署' : '无匹配记录' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="125">
          <template #default="scope">
            <el-tag :type="stateTagType(scope.row.state)">{{ stateLabel(scope.row.state) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="开放" width="85">
          <template #default="scope">{{ scope.row.enabled ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="缺少前提" min-width="320">
          <template #default="scope">
            <ul class="requirements">
              <li v-for="requirement in scope.row.requirements" :key="requirement">{{ requirement }}</li>
            </ul>
          </template>
        </el-table-column>
      </el-table>
      <p class="muted checked-at">读取时间：{{ formatTime(readiness.items[0]?.checkedAt) }}</p>
    </template>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { runtimeReadiness, type RuntimeReadiness, type RuntimeGateState } from '../../api/runtimeReadiness'

type ViewState = 'loading' | 'ready' | 'unavailable' | 'permission-lost'
const state = ref<ViewState>('loading')
const loading = ref(false)
const readiness = ref<RuntimeReadiness | null>(null)
let currentRequest: AbortController | null = null
let requestRevision = 0

const labels: Record<string, string> = {
  'tool.resource-authorization': '工具资源级授权',
  'channel.real-im': '真实 IM 渠道',
  'mcp.enterprise-identity': '企业 MCP 身份',
  'artifact.markdown-export': 'Markdown 成果物导出',
  'artifact.content-blocks': '交互内容块',
  'artifact.generic-selection': '通用用户选择',
  'model.connection-directory': '模型连接与凭据版本',
  'profile.single-skilled': 'SINGLE_SKILLED profile',
  'profile.team-readonly': 'TEAM_READONLY profile',
  'profile.team-autonomous-readonly': 'TEAM_AUTONOMOUS_READONLY profile',
  'session.collaborative': '每轮协作模式',
  'session.autonomous': '每轮自治模式',
  'memory.long-term': '可写长期记忆',
  'rag.full': '完整 RAG',
  'meeting-room.production': '会议室生产接入',
}

async function load() {
  currentRequest?.abort()
  const request = new AbortController()
  currentRequest = request
  const revision = ++requestRevision
  loading.value = true
  state.value = readiness.value ? 'ready' : 'loading'
  try {
    readiness.value = await runtimeReadiness(request.signal)
    if (revision === requestRevision) state.value = 'ready'
  } catch (error) {
    if (request.signal.aborted || revision !== requestRevision) return
    const statusCode = (error as { response?: { status?: number } }).response?.status
    state.value = statusCode === 401 || statusCode === 403 ? 'permission-lost' : 'unavailable'
  } finally {
    if (revision === requestRevision) loading.value = false
  }
}

function gateLabel(gateId: string) { return labels[gateId] ?? gateId }
function stateLabel(value: RuntimeGateState) {
  return ({
    DESIGNED: '待实施',
    IMPLEMENTED_CLOSED: '已实现·关闭',
    VALIDATED: '已验证',
    ENABLED: '已开放',
    SUSPENDED: '已暂停',
  } satisfies Record<RuntimeGateState, string>)[value]
}
function stateTagType(value: RuntimeGateState) {
  return value === 'ENABLED' ? 'success' : value === 'SUSPENDED' ? 'danger' : 'info'
}
function formatTime(value?: string) { return value ? new Date(value).toLocaleString() : '未知' }

onMounted(load)
onBeforeUnmount(() => currentRequest?.abort())
</script>

<style scoped>
.readiness-panel { margin: 24px 0; }
.panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.panel-head h3 { margin: 0 0 6px; }
.requirements { margin: 0; padding-left: 18px; }
.requirements li + li { margin-top: 4px; }
.checked-at { margin: 8px 0 0; }
</style>

<template>
  <section>
    <div class="page-head">
      <div>
        <h3>可信 MCP 工具</h3>
        <p class="muted">登记连接后，以当前管理员身份发现候选；只有逐个审核的子工具才进入能力目录。</p>
      </div>
      <el-button @click="refresh">刷新</el-button>
    </div>
    <el-alert type="warning" :closable="false" class="block"
              title="连接测试不代表用户已有权限。模拟器仅限本机测试；真实服务的认证与资源判权仍须单独验收。" />

    <el-card class="block">
      <template #header>登记连接</template>
      <el-form :model="createForm" label-width="100px">
        <el-form-item label="连接编码"><el-input v-model="createForm.code" maxlength="64" placeholder="例如 demo-mcp" /></el-form-item>
        <el-form-item label="显示名称"><el-input v-model="createForm.displayName" maxlength="128" /></el-form-item>
        <el-form-item label="服务地址"><el-input v-model="createForm.endpoint" placeholder="https://已准入的服务/mcp" /></el-form-item>
        <el-form-item label="登记原因"><el-input v-model="createForm.reason" maxlength="500" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="creating" @click="create">登记</el-button></el-form-item>
      </el-form>
    </el-card>

    <el-skeleton v-if="loading" :rows="4" animated />
    <el-table v-else :data="connections" border stripe>
      <el-table-column prop="displayName" label="连接" min-width="140" />
      <el-table-column prop="code" label="编码" min-width="130" />
      <el-table-column prop="endpoint" label="目标" min-width="240" show-overflow-tooltip />
      <el-table-column label="状态" width="90"><template #default="{ row }">
        <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
      </template></el-table-column>
      <el-table-column label="操作" width="250"><template #default="{ row }">
        <el-button link type="primary" :disabled="!row.enabled" :loading="busyId === row.id" @click="discover(row)">发现工具</el-button>
        <el-button link type="primary" @click="showDiff(row)">查看差异</el-button>
        <el-button link :type="row.enabled ? 'danger' : 'primary'" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
      </template></el-table-column>
    </el-table>

    <el-card v-if="selectedConnection" class="block">
      <template #header>{{ selectedConnection.displayName }}：当前管理员的发现差异</template>
      <el-alert type="info" :closable="false" class="block" title="这里是已保存的历史记录，仅供核对变化；批准请先执行新的工具发现。" />
      <el-skeleton v-if="diffLoading" :rows="4" animated />
      <template v-else-if="discoveryDiff">
        <el-descriptions :column="2" border class="block">
          <el-descriptions-item label="上次发现">{{ snapshotLabel(discoveryDiff.previous) }}</el-descriptions-item>
          <el-descriptions-item label="最近发现">{{ snapshotLabel(discoveryDiff.current) }}</el-descriptions-item>
        </el-descriptions>
        <el-empty v-if="!discoveryDiff.current" description="该管理员在此连接下还没有成功保存的发现记录" />
        <el-empty v-else-if="!discoveryDiff.previous" description="只有一次发现，暂无可比较的上次记录" />
        <el-empty v-else-if="!discoveryDiff.changes.length" description="这两次发现的工具声明没有变化" />
        <el-table v-else :data="discoveryDiff.changes" border stripe>
          <el-table-column type="expand"><template #default="{ row }">
            <div class="diff-details">
              <div><strong>上次声明</strong><template v-if="row.before">
                <p>说明：{{ row.before.description || '未声明' }}</p>
                <p>只读提示：{{ row.before.readOnlyHint ? '是' : '否/未知' }}</p>
                <p>输入 Schema</p><pre class="schema-preview">{{ prettySchema(row.before.inputSchema) }}</pre>
                <p>输出 Schema</p><pre class="schema-preview">{{ prettySchema(row.before.outputSchema) }}</pre>
              </template><p v-else>无</p></div>
              <div><strong>最近声明</strong><template v-if="row.after">
                <p>说明：{{ row.after.description || '未声明' }}</p>
                <p>只读提示：{{ row.after.readOnlyHint ? '是' : '否/未知' }}</p>
                <p>输入 Schema</p><pre class="schema-preview">{{ prettySchema(row.after.inputSchema) }}</pre>
                <p>输出 Schema</p><pre class="schema-preview">{{ prettySchema(row.after.outputSchema) }}</pre>
              </template><p v-else>无</p></div>
            </div>
          </template></el-table-column>
          <el-table-column prop="name" label="远端工具名" min-width="180" />
          <el-table-column label="变化" width="100"><template #default="{ row }">
            <el-tag :type="row.type === 'ADDED' ? 'success' : row.type === 'REMOVED' ? 'danger' : 'warning'">{{ changeTypeLabel(row.type) }}</el-tag>
          </template></el-table-column>
          <el-table-column label="变化字段" min-width="250"><template #default="{ row }">{{ changedFieldLabels(row.changedFields) }}</template></el-table-column>
        </el-table>
      </template>
    </el-card>

    <el-card v-if="discovery && selectedConnection" class="block">
      <template #header>{{ selectedConnection.displayName }}：本次发现的候选工具</template>
      <el-alert type="info" :closable="false" class="block" title="这不是所有用户的工具全集；批准后其他用户的 Run 仍按各自的 tools/list 过滤。" />
      <el-table :data="discovery.tools" border stripe>
        <el-table-column prop="name" label="远端工具名" min-width="180" />
        <el-table-column prop="description" label="远端说明" min-width="220" show-overflow-tooltip />
        <el-table-column label="只读提示" width="100"><template #default="{ row }">{{ row.readOnlyHint ? '是' : '否/未知' }}</template></el-table-column>
        <el-table-column label="操作" width="100"><template #default="{ row }">
          <el-button link type="primary" @click="openApproval(row)">审核</el-button>
        </template></el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="approvalVisible" title="逐工具审核" width="620px">
      <el-alert type="warning" :closable="false" class="block" title="远端只读提示不能代替人工确认；写工具必须要求用户确认。" />
      <el-form :model="approval" label-width="110px">
        <el-form-item label="远端工具"><span>{{ selectedTool?.name }}</span></el-form-item>
        <el-form-item label="输入 Schema"><pre class="schema-preview">{{ prettySchema(selectedTool?.inputSchema) }}</pre></el-form-item>
        <el-form-item label="输出 Schema"><pre class="schema-preview">{{ prettySchema(selectedTool?.outputSchema) }}</pre></el-form-item>
        <el-form-item label="能力编码"><el-input v-model="approval.capabilityCode" placeholder="mcp.demo-mcp.private-note-read" /></el-form-item>
        <el-form-item label="修订"><el-input v-model="approval.revision" placeholder="1" /></el-form-item>
        <el-form-item label="模型工具名"><el-input v-model="approval.modelToolName" placeholder="demo_private_note_read" /></el-form-item>
        <el-form-item label="显示名称"><el-input v-model="approval.displayName" /></el-form-item>
        <el-form-item label="模型说明"><el-input v-model="approval.description" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="影响"><el-radio-group v-model="approval.readOnly"><el-radio :value="true">只读</el-radio><el-radio :value="false">写操作</el-radio></el-radio-group></el-form-item>
        <el-form-item label="需要确认"><el-switch v-model="approval.requiresConfirmation" :disabled="!approval.readOnly" /></el-form-item>
        <el-form-item label="审核原因"><el-input v-model="approval.reason" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="approvalVisible = false">取消</el-button><el-button type="primary" :loading="approving" @click="submitApproval">批准修订</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import * as adminApi from '../../api/admin'
import type { McpApproval, McpConnection, McpDiscovery, McpDiscoveryDiff, McpDiscoverySnapshotSummary, McpToolChange, McpToolDescriptor } from '../../api/admin'
import { notifyError, notifySuccess } from '../../utils/notify'

const connections = ref<McpConnection[]>([])
const loading = ref(false)
const creating = ref(false)
const busyId = ref(0)
const selectedConnection = ref<McpConnection | null>(null)
const discovery = ref<McpDiscovery | null>(null)
const discoveryDiff = ref<McpDiscoveryDiff | null>(null)
const diffLoading = ref(false)
let diffRequestId = 0
const selectedTool = ref<McpToolDescriptor | null>(null)
const approvalVisible = ref(false)
const approving = ref(false)
const createForm = reactive({ code: '', displayName: '', endpoint: '', reason: '' })
const approval = reactive<McpApproval>({ snapshotId: 0, capabilityCode: '', revision: '1',
  modelToolName: '', displayName: '', description: '', readOnly: true, requiresConfirmation: false, reason: '' })

watch(() => approval.readOnly, value => { if (!value) approval.requiresConfirmation = true })

async function refresh() {
  loading.value = true
  try {
    connections.value = await adminApi.mcpConnections()
    if (selectedConnection.value) {
      const current = connections.value.find(row => row.id === selectedConnection.value?.id)
      if (!current || !current.enabled || current.revision !== selectedConnection.value.revision) discovery.value = null
      selectedConnection.value = current ?? null
      if (current) {
        await loadDiff(current.id)
        if (discovery.value && discoveryDiff.value?.current?.snapshotId !== discovery.value.snapshotId) discovery.value = null
      } else discoveryDiff.value = null
    }
  }
  catch (error) { notifyError(error, 'MCP 连接加载失败') }
  finally { loading.value = false }
}

async function create() {
  if (!createForm.code.trim() || !createForm.displayName.trim() || !createForm.endpoint.trim() || !createForm.reason.trim()) {
    notifyError(new Error('请填写完整的连接信息和登记原因')); return
  }
  creating.value = true
  try {
    await adminApi.createMcpConnection(createForm.code.trim(), createForm.displayName.trim(), createForm.endpoint.trim(), createForm.reason.trim())
    Object.assign(createForm, { code: '', displayName: '', endpoint: '', reason: '' })
    notifySuccess('连接已登记；请发现并逐个审核工具')
    await refresh()
  } catch (error) { notifyError(error) }
  finally { creating.value = false }
}

async function toggle(row: McpConnection) {
  let reason: string
  try {
    const response = await ElMessageBox.prompt(`请输入${row.enabled ? '停用' : '启用'}原因`, '连接状态变更', { inputValidator: value => !!String(value).trim() || '原因不能为空' })
    reason = String(response.value).trim()
  } catch { return }
  try {
    await adminApi.setMcpConnectionStatus(row.id, !row.enabled, reason)
    await refresh()
    discovery.value = null
  }
  catch (error) { notifyError(error) }
}

async function discover(row: McpConnection) {
  busyId.value = row.id
  approvalVisible.value = false
  try {
    discovery.value = await adminApi.discoverMcpTools(row.id)
    selectedConnection.value = row
    await loadDiff(row.id)
  }
  catch (error) { discovery.value = null; notifyError(error, '逐用户工具发现失败') }
  finally { busyId.value = 0 }
}

async function showDiff(row: McpConnection) {
  discovery.value = null
  selectedConnection.value = row
  approvalVisible.value = false
  await loadDiff(row.id)
}

async function loadDiff(id: number) {
  const requestId = ++diffRequestId
  discoveryDiff.value = null
  diffLoading.value = true
  try {
    const result = await adminApi.mcpDiscoveryDiff(id)
    if (requestId === diffRequestId && selectedConnection.value?.id === id) discoveryDiff.value = result
  } catch (error) {
    if (requestId === diffRequestId) notifyError(error, '发现差异加载失败')
  } finally {
    if (requestId === diffRequestId) diffLoading.value = false
  }
}

function snapshotLabel(snapshot: McpDiscoverySnapshotSummary | null): string {
  if (!snapshot) return '无记录'
  return `${new Date(snapshot.discoveredAt).toLocaleString('zh-CN')} · 快照 #${snapshot.snapshotId} · 连接修订 ${snapshot.connectionRevision} · ${snapshot.toolCount} 个工具`
}

function changeTypeLabel(type: McpToolChange['type']): string {
  return { ADDED: '新增', REMOVED: '消失', CHANGED: '元数据变化' }[type]
}

function changedFieldLabels(fields: McpToolChange['changedFields']): string {
  const labels = { description: '说明', inputSchema: '输入 Schema', outputSchema: '输出 Schema', readOnlyHint: '只读提示' }
  return fields.map(field => labels[field]).join('、') || '—'
}

function openApproval(tool: McpToolDescriptor) {
  selectedTool.value = tool
  Object.assign(approval, { snapshotId: discovery.value?.snapshotId ?? 0, capabilityCode: '', revision: '1',
    modelToolName: '', displayName: tool.name, description: tool.description,
    readOnly: tool.readOnlyHint, requiresConfirmation: !tool.readOnlyHint, reason: '' })
  approvalVisible.value = true
}

async function submitApproval() {
  if (!selectedConnection.value || !selectedTool.value || !approval.reason.trim()) { notifyError(new Error('请填写审核原因')); return }
  approving.value = true
  try {
    await adminApi.approveMcpTool(selectedConnection.value.id, selectedTool.value.name, { ...approval })
    notifySuccess('子工具已批准，可以在员工草稿中选择')
    approvalVisible.value = false
  } catch (error) { notifyError(error) }
  finally { approving.value = false }
}

function prettySchema(schema: Record<string, unknown> | undefined): string {
  return schema && Object.keys(schema).length ? JSON.stringify(schema, null, 2) : '远端未声明'
}

onMounted(refresh)
</script>

<style scoped>
.schema-preview { max-height: 180px; max-width: 440px; overflow: auto; margin: 0; padding: 8px;
  background: #f5f7fa; white-space: pre-wrap; overflow-wrap: anywhere; }
.diff-details { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; padding: 12px; }
.diff-details > div { min-width: 0; }
.diff-details p { margin: 8px 0 4px; }
@media (max-width: 720px) { .diff-details { grid-template-columns: 1fr; } }
</style>

<template>
  <section>
    <div class="page-head">
      <div>
        <h3>能力目录</h3>
        <p class="muted">启停能力的执行入口；停用后草稿校验会判定为不可发布。</p>
      </div>
      <div class="toolbar">
        <el-input v-model="keyword" clearable placeholder="按名称或编码筛选" style="width: 220px" />
        <el-button @click="refresh">刷新</el-button>
      </div>
    </div>

    <el-skeleton v-if="loading" :rows="5" animated />
    <el-empty v-else-if="!filtered.length" description="没有匹配的能力" />
    <el-table v-else :data="filtered" border stripe>
      <el-table-column prop="displayName" label="能力" min-width="140" />
      <el-table-column prop="capabilityCode" label="编码" min-width="160" />
      <el-table-column prop="revision" label="修订" width="90" />
      <el-table-column prop="type" label="类型" width="100" />
      <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip />
      <el-table-column prop="businessAction" label="业务动作" width="130" />
      <el-table-column label="需确认" width="90" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.requiresConfirmation" size="small" type="warning">需要</el-tag>
          <span v-else class="muted">否</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="showDetails(row)">详情</el-button>
          <el-button link :type="row.enabled ? 'danger' : 'primary'" :loading="busyId === row.capabilityCode"
                     @click="toggle(row)">
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="detailVisible" :title="selected?.displayName || '工具详情'" size="520px">
      <template v-if="selected">
        <el-alert type="info" :closable="false" class="detail-block"
                  title="本地工具必须由代码白名单提供者注册；MCP 工具须先在 MCP 页面逐项审核。" />
        <el-descriptions :column="1" border>
          <el-descriptions-item label="能力编码">{{ selected.capabilityCode }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ typeLabel(selected.type) }}</el-descriptions-item>
          <el-descriptions-item label="修订">{{ selected.revision }}</el-descriptions-item>
          <el-descriptions-item label="模型工具名">{{ selected.toolName }}</el-descriptions-item>
          <el-descriptions-item label="业务动作">{{ selected.businessAction }}</el-descriptions-item>
          <el-descriptions-item label="执行实现">{{ selected.implementationKey }}</el-descriptions-item>
          <el-descriptions-item label="全局状态">{{ selected.enabled ? '启用' : '停用' }}</el-descriptions-item>
          <el-descriptions-item label="需要确认">{{ selected.requiresConfirmation ? '是' : '否' }}</el-descriptions-item>
          <el-descriptions-item label="说明">{{ selected.description || '未填写' }}</el-descriptions-item>
        </el-descriptions>
        <div class="schema-block">
          <div class="schema-title">输入 Schema</div>
          <pre class="schema-preview">{{ prettySchema(selected.inputSchema) }}</pre>
        </div>
      </template>
    </el-drawer>

    <CapabilityAssetPanel />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import * as adminApi from '../../api/admin'
import type { Capability } from '../../api/admin'
import { notifyError, notifySuccess } from '../../utils/notify'
import CapabilityAssetPanel from './CapabilityAssetPanel.vue'

const items = ref<Capability[]>([])
const loading = ref(false)
const keyword = ref('')
/** 正在提交的能力编码，用于给对应行加 loading，避免整表遮罩。 */
const busyId = ref('')
const detailVisible = ref(false)
const selected = ref<Capability>()

const filtered = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  if (!key) return items.value
  return items.value.filter(item =>
    item.displayName.toLowerCase().includes(key) ||
    item.capabilityCode.toLowerCase().includes(key) ||
    item.description.toLowerCase().includes(key))
})

async function refresh() {
  loading.value = true
  try {
    items.value = await adminApi.capabilities()
  } catch (error) {
    notifyError(error, '能力目录加载失败')
  } finally {
    loading.value = false
  }
}

async function toggle(row: Capability) {
  const next = !row.enabled
  // 后端要求每次变更都带操作原因，且不为空、不超过 500 字。
  let reason = ''
  try {
    const prompt = await ElMessageBox.prompt(
      `请输入「${next ? '启用' : '停用'}」${row.displayName} 的操作原因`,
      next ? '启用能力' : '停用能力',
      { inputPlaceholder: '操作原因（必填，最多 500 字）', inputValidator: validateReason }
    )
    reason = String(prompt.value ?? '').trim()
  } catch {
    return // 用户取消
  }
  if (!reason) return

  busyId.value = row.capabilityCode
  try {
    await adminApi.setCapabilityStatus(row.capabilityCode, next, reason)
    row.enabled = next
    notifySuccess(`已${next ? '启用' : '停用'}「${row.displayName}」`)
  } catch (error) {
    notifyError(error)
  } finally {
    busyId.value = ''
  }
}

function showDetails(row: Capability) {
  selected.value = row
  detailVisible.value = true
}

function typeLabel(type: Capability['type']): string {
  return ({ TOOL: '本地工具', MCP: 'MCP 工具', SKILL: 'Skill', KNOWLEDGE: '知识' } as Record<Capability['type'], string>)[type]
    || type
}

function prettySchema(schema: Record<string, unknown>): string {
  return Object.keys(schema).length ? JSON.stringify(schema, null, 2) : '未声明'
}

function validateReason(value: string): boolean | string {
  const text = String(value ?? '').trim()
  if (!text) return '操作原因不能为空'
  if (text.length > 500) return '操作原因不能超过 500 个字符'
  return true
}

onMounted(refresh)
defineExpose({ refresh })
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 10px;
}
.detail-block { margin-bottom: 16px; }
.schema-block { margin-top: 16px; }
.schema-title { font-weight: 600; margin-bottom: 8px; }
.schema-preview { max-height: 300px; overflow: auto; margin: 0; padding: 10px;
  background: #f5f7fa; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>

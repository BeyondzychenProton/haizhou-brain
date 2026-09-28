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
          <el-button link :type="row.enabled ? 'danger' : 'primary'" :loading="busyId === row.capabilityCode"
                     @click="toggle(row)">
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import * as adminApi from '../../api/admin'
import type { Capability } from '../../api/admin'
import { notifyError, notifySuccess } from '../../utils/notify'

const items = ref<Capability[]>([])
const loading = ref(false)
const keyword = ref('')
/** 正在提交的能力编码，用于给对应行加 loading，避免整表遮罩。 */
const busyId = ref('')

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
</style>

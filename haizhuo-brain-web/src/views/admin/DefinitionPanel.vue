<template>
  <section>
    <div class="page-head">
      <div>
        <h3>员工定义编排</h3>
        <p class="muted">编辑草稿 → 校验 → 发布。发布后生成不可变版本，草稿修订号会自增。</p>
      </div>
      <el-button @click="reloadAll">刷新</el-button>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" class="block" />

    <el-form label-width="110px" class="block">
      <el-form-item label="数字员工">
        <el-select v-model="employeeId" placeholder="请选择数字员工" style="width: 320px" @change="loadDraft">
          <el-option v-for="employee in employees" :key="employee.id" :label="employee.name" :value="employee.id" />
        </el-select>
      </el-form-item>
    </el-form>

    <el-skeleton v-if="loading" :rows="6" animated />
    <template v-else-if="form">
      <div class="meta">
        <span>草稿修订号：<b>{{ draftRevision }}</b></span>
        <span>最近更新：{{ formatTime(updatedAt) }}</span>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="系统指令" prop="instructions">
          <el-input v-model="form.instructions" type="textarea" :rows="6" maxlength="4000" show-word-limit />
        </el-form-item>
        <el-form-item label="模型供应商" prop="modelProvider">
          <el-input v-model="form.modelProvider" placeholder="如 dashscope / openai" style="width: 320px" />
        </el-form-item>
        <el-form-item label="模型名称" prop="modelName">
          <el-input v-model="form.modelName" placeholder="如 qwen-plus" style="width: 320px" />
        </el-form-item>
        <el-form-item label="能力" prop="selected">
          <el-checkbox-group v-if="catalog.length" v-model="form.selected">
            <el-checkbox v-for="capability in catalog" :key="valueOf(capability)" :value="valueOf(capability)">
              {{ capability.displayName }}
              <span class="muted">({{ capability.capabilityCode }} · {{ capability.revision }})</span>
              <el-tag v-if="capability.type === 'MCP'" size="small" type="warning">MCP · 由服务端判权</el-tag>
              <el-tag v-if="!capability.enabled" size="small" type="info">已停用</el-tag>
            </el-checkbox>
          </el-checkbox-group>
          <el-empty v-else description="能力目录为空" :image-size="60" />
        </el-form-item>
        <el-form-item label="变更原因" prop="reason">
          <el-input v-model="form.reason" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="保存草稿必填，最多 500 字" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存草稿</el-button>
          <el-button :loading="validating" @click="validate">校验</el-button>
          <el-button type="success" :loading="publishing" @click="publish">发布</el-button>
        </el-form-item>
      </el-form>

      <el-card v-if="selectedCapabilities.length" class="block">
        <template #header>拟发布工具契约（以保存后的草稿和服务端校验为准）</template>
        <el-table :data="selectedCapabilities" border size="small">
          <el-table-column prop="displayName" label="能力" min-width="130" />
          <el-table-column prop="toolName" label="模型工具名" min-width="160" />
          <el-table-column label="来源" width="90"><template #default="{ row }">{{ row.type === 'MCP' ? 'MCP' : '本地' }}</template></el-table-column>
          <el-table-column prop="businessAction" label="动作" min-width="100" />
          <el-table-column label="确认" width="90"><template #default="{ row }">{{ row.requiresConfirmation ? '必需' : '不需要' }}</template></el-table-column>
        </el-table>
      </el-card>

      <div v-if="validation" class="block">
        <el-alert v-if="validation.publishable" title="校验通过，可以发布" type="success" :closable="false" />
        <el-alert v-else title="校验未通过" type="error" :closable="false">
          <ul class="issues">
            <li v-for="issue in validation.issues" :key="issue.code + issue.message">
              <code>{{ issue.code }}</code> {{ issue.message }}
            </li>
          </ul>
        </el-alert>
      </div>

      <div v-if="published" class="block">
        <el-alert :title="`已发布版本 v${published.definition.version}`" type="success" :closable="false"
                  :description="`定义版本 ID ${published.definition.id}，能力 ${published.capabilities.length} 项，发布时间 ${formatTime(published.definition.publishedAt)}`" />
      </div>
    </template>
    <el-empty v-else-if="!loadError" description="请先选择一个数字员工" />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import * as adminApi from '../../api/admin'
import type { Capability, PublishedEmployee, ValidationResult } from '../../api/admin'
import * as appApi from '../../api/app'
import { formatTime, notifyError, notifySuccess, toMessage } from '../../utils/notify'

interface DraftForm {
  instructions: string
  modelProvider: string
  modelName: string
  selected: string[]
  reason: string
}

const employees = ref<appApi.Employee[]>([])
const catalog = ref<Capability[]>([])
const employeeId = ref<number>()
const form = ref<DraftForm>()
const formRef = ref<FormInstance>()
const draftRevision = ref(0)
const updatedAt = ref('')

const loading = ref(false)
const saving = ref(false)
const validating = ref(false)
const publishing = ref(false)
const loadError = ref('')
const validation = ref<ValidationResult>()
const published = ref<PublishedEmployee>()
const selectedCapabilities = computed(() => catalog.value.filter(item =>
  form.value?.selected.includes(valueOf(item))))

const rules: FormRules<DraftForm> = {
  instructions: [{ required: true, message: '请填写系统指令', trigger: 'blur' }],
  modelProvider: [{ required: true, message: '请填写模型供应商', trigger: 'blur' }],
  modelName: [{ required: true, message: '请填写模型名称', trigger: 'blur' }],
  selected: [{ type: 'array', min: 1, message: '请至少选择一项能力', trigger: 'change' }],
  reason: [{ required: true, message: '请填写变更原因', trigger: 'blur' },
           { max: 500, message: '变更原因不能超过 500 个字符', trigger: 'blur' }]
}

/** 用「编码|修订」作为复选值，避免同名能力的不同修订互相覆盖。 */
function valueOf(capability: Capability): string {
  return `${capability.capabilityCode}|${capability.revision}`
}

function toSelections(selected: string[]): adminApi.CapabilitySelection[] {
  return selected.map(value => {
    const separator = value.lastIndexOf('|')
    return { capabilityCode: value.slice(0, separator), revision: value.slice(separator + 1) }
  })
}

async function loadDraft() {
  if (!employeeId.value) return
  loading.value = true
  loadError.value = ''
  validation.value = undefined
  published.value = undefined
  try {
    const draft = await adminApi.getDraft(employeeId.value)
    draftRevision.value = draft.draftRevision
    updatedAt.value = draft.updatedAt
    form.value = {
      instructions: draft.instructions ?? '',
      modelProvider: draft.modelProvider ?? '',
      modelName: draft.modelName ?? '',
      selected: draft.capabilities.map(item => `${item.capabilityCode}|${item.revision}`),
      reason: ''
    }
  } catch (error) {
    form.value = undefined
    // 草稿加载失败直接在页内提示，避免再来一条全局 toast。
    loadError.value = toMessage(error, '草稿加载失败')
  } finally {
    loading.value = false
  }
}

async function reloadAll() {
  try {
    employees.value = await appApi.listEmployees()
    catalog.value = await adminApi.capabilities()
  } catch (error) {
    notifyError(error, '基础数据加载失败')
    return
  }
  await loadDraft()
}

watch(employeeId, () => { validation.value = undefined; published.value = undefined })

async function save() {
  if (!formRef.value || !form.value || !employeeId.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const draft = await adminApi.saveDraft(employeeId.value, {
      expectedDraftRevision: draftRevision.value,
      instructions: form.value.instructions.trim(),
      modelProvider: form.value.modelProvider.trim(),
      modelName: form.value.modelName.trim(),
      capabilities: toSelections(form.value.selected),
      reason: form.value.reason.trim()
    })
    draftRevision.value = draft.draftRevision
    updatedAt.value = draft.updatedAt
    form.value.reason = ''
    validation.value = undefined
    notifySuccess(`草稿已保存，修订号 ${draft.draftRevision}`)
  } catch (error) {
    notifyError(error, '草稿保存失败')
  } finally {
    saving.value = false
  }
}

async function validate() {
  if (!employeeId.value) return
  validating.value = true
  try {
    validation.value = await adminApi.validateDraft(employeeId.value)
    if (validation.value.publishable) notifySuccess('校验通过')
  } catch (error) {
    notifyError(error, '校验失败')
  } finally {
    validating.value = false
  }
}

async function publish() {
  if (!formRef.value || !form.value || !employeeId.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  publishing.value = true
  try {
    // requestId 是后端的幂等键：同一次发布重试必须复用同一个值。
    published.value = await adminApi.publishDraft(
      employeeId.value, draftRevision.value, crypto.randomUUID(), form.value.reason.trim())
    notifySuccess(`已发布 v${published.value.definition.version}`)
    await loadDraft()
  } catch (error) {
    notifyError(error, '发布失败')
  } finally {
    publishing.value = false
  }
}

onMounted(reloadAll)
</script>

<style scoped>
.block { margin-bottom: 18px; }
.meta { display: flex; gap: 24px; color: #667085; font-size: 13px; margin-bottom: 16px; }
.issues { margin: 0; padding-left: 18px; }
.el-checkbox { margin-right: 18px; }
</style>

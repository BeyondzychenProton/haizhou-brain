<template>
  <section>
    <div class="page-head">
      <div>
        <h3>员工定义编排</h3>
        <p class="muted">管理员可管理草稿、停用员工和历史发布版本。发布与启用是两个独立操作。</p>
      </div>
      <div class="head-actions">
        <el-button @click="reloadAll">刷新</el-button>
        <el-button type="primary" @click="createDialog = true">新建员工</el-button>
      </div>
    </div>

    <el-alert v-if="pageError" :title="pageError" type="error" :closable="false" class="block" />

    <el-card class="block">
      <div class="filter-bar">
        <el-input v-model="filters.query" clearable placeholder="搜索员工编码或名称" style="width: 260px"
                  @keyup.enter="resetEmployeePage" @clear="resetEmployeePage" />
        <el-select v-model="filters.enabled" clearable placeholder="启用状态" style="width: 150px" @change="resetEmployeePage">
          <el-option label="已启用" value="true" /><el-option label="已停用" value="false" />
        </el-select>
        <el-select v-model="filters.published" clearable placeholder="发布状态" style="width: 150px" @change="resetEmployeePage">
          <el-option label="已发布" value="true" /><el-option label="仅草稿" value="false" />
        </el-select>
        <el-button @click="resetEmployeePage">查询</el-button>
      </div>
      <el-table v-loading="employeesLoading" :data="employees" row-key="employeeId" border>
        <el-table-column prop="employeeCode" label="员工编码" min-width="145" />
        <el-table-column prop="displayName" label="名称" min-width="180" />
        <el-table-column label="状态" width="210">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '已启用' : '已停用' }}</el-tag>
            <el-tag v-if="!row.published" type="warning" class="tag-gap">草稿，普通用户不可见</el-tag>
            <el-tag v-else type="success" class="tag-gap">已发布</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前版本" width="100">
          <template #default="{ row }">{{ row.currentPublishedVersionId ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="rowVersion" label="并发修订" width="100" />
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="requestSelect(row)">{{ row.employeeId === employeeId ? '正在编辑' : '编辑' }}</el-button>
            <el-button link :type="row.enabled ? 'danger' : 'success'" @click="changeStatus(row)">
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-button :disabled="pageIndex === 0 || employeesLoading" @click="previousEmployees">上一页</el-button>
        <span>第 {{ pageIndex + 1 }} 页</span>
        <el-button :disabled="!employeePage?.hasMore || employeesLoading" @click="nextEmployees">下一页</el-button>
      </div>
    </el-card>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" class="block">
      <el-button v-if="employeeId" link type="primary" @click="loadDraft(employeeId)">读取最新草稿</el-button>
    </el-alert>

    <el-skeleton v-if="loading" :rows="8" animated />
    <template v-else-if="form && selectedEmployee">
      <el-card class="block">
        <template #header>
          <div class="section-head">
            <span>{{ selectedEmployee.displayName }} <small>（{{ selectedEmployee.employeeCode }}）</small></span>
            <div class="head-actions">
              <el-tag v-if="dirty" type="warning">有未保存修改</el-tag>
              <el-button @click="openVersions">历史版本</el-button>
            </div>
          </div>
        </template>
        <div class="meta">
          <span>草稿修订号：<b>{{ draftRevision }}</b></span>
          <span>最近更新：{{ formatTime(updatedAt) }}</span>
          <span>发布状态：{{ selectedEmployee.published ? `v${selectedEmployee.currentPublishedVersionId}` : '未发布' }}</span>
        </div>
        <el-form label-width="125px" class="metadata-form">
          <el-form-item label="员工名称">
            <el-input v-model="metadata.displayName" maxlength="128" show-word-limit />
            <el-button :disabled="metadata.displayName.trim() === selectedEmployee.displayName || !metadata.reason.trim()"
                       :loading="metadataSaving" @click="saveMetadata">保存名称</el-button>
          </el-form-item>
          <el-form-item label="变更原因">
            <el-input v-model="metadata.reason" maxlength="500" show-word-limit placeholder="名称、启停或发布操作的原因" />
          </el-form-item>
        </el-form>
      </el-card>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="125px" class="block">
        <el-form-item label="系统指令" prop="instructions">
          <el-input v-model="form.instructions" type="textarea" :rows="8" :maxlength="options?.instructionMaxLength ?? 12000" show-word-limit />
        </el-form-item>
        <el-form-item label="模型供应商" prop="modelProvider">
          <el-select v-model="form.modelProvider" style="width: 320px">
            <el-option label="OpenAI" value="openai" />
            <el-option label="OpenAI Compatible" value="openai-compatible" />
            <el-option label="DashScope" value="dashscope" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型名称" prop="modelName">
          <el-input v-model="form.modelName" maxlength="128" placeholder="例如已配置的模型名称" style="width: 360px" />
        </el-form-item>
        <el-form-item label="运行配置">
          <RuntimeConfigurationEditor v-if="options" v-model="form.configuration" :options="options" :employees="employees" />
          <el-skeleton v-else :rows="4" animated />
        </el-form-item>
        <el-form-item label="能力修订" prop="selected">
          <el-checkbox-group v-if="catalog.length" v-model="form.selected">
            <el-checkbox v-for="capability in catalog" :key="valueOf(capability)" :value="valueOf(capability)">
              {{ capability.displayName }}
              <span class="muted">({{ capability.capabilityCode }} · {{ capability.revision }})</span>
              <el-tag v-if="capability.type === 'MCP'" size="small" type="warning">MCP · 运行时仍由平台判权</el-tag>
              <el-tag v-if="!capability.enabled" size="small" type="info">已停用</el-tag>
            </el-checkbox>
          </el-checkbox-group>
          <el-empty v-else description="能力目录为空" :image-size="60" />
        </el-form-item>
        <el-form-item label="变更原因" prop="reason">
          <el-input v-model="form.reason" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="保存草稿和发布必填" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存草稿</el-button>
          <el-button :disabled="dirty" :loading="validating" @click="validate">校验已保存草稿</el-button>
          <el-button type="success" :disabled="!canPublish" :loading="publishing" @click="publish">发布新版本</el-button>
          <el-button :disabled="!validation || validation.draftRevision !== draftRevision || dirty" @click="openVersions">查看版本历史</el-button>
        </el-form-item>
      </el-form>

      <el-card v-if="selectedCapabilities.length" class="block">
        <template #header>拟发布能力（以保存草稿和服务端校验为准）</template>
        <el-table :data="selectedCapabilities" border size="small">
          <el-table-column prop="displayName" label="能力" min-width="130" />
          <el-table-column prop="toolName" label="模型工具名" min-width="160" />
          <el-table-column label="来源" width="90"><template #default="{ row }">{{ row.type === 'MCP' ? 'MCP' : row.type }}</template></el-table-column>
          <el-table-column prop="businessAction" label="业务动作" min-width="150" />
          <el-table-column label="确认" width="90"><template #default="{ row }">{{ row.requiresConfirmation ? '必需' : '不需要' }}</template></el-table-column>
        </el-table>
      </el-card>

      <div v-if="validation" class="block">
        <el-alert v-if="validation.publishable" title="校验通过；配置未改变，可发布" type="success" :closable="false" />
        <el-alert v-else title="校验未通过；请按字段修订后重新保存和校验" type="error" :closable="false">
          <ul class="issues">
            <li v-for="issue in validation.issues" :key="issue.code + issue.fieldPath + issue.message">
              <button v-if="issue.fieldPath" class="issue-link" type="button" @click="focusIssue(issue)">
                <code>{{ issue.fieldPath }}</code>
              </button>
              <code>{{ issue.code }}</code> {{ issue.message }}
            </li>
          </ul>
        </el-alert>
      </div>

      <el-alert v-if="published" class="block" :title="`已发布版本 v${published.definition.version}`" type="success" :closable="false"
                :description="`版本 ID ${published.definition.id}，能力 ${published.capabilities.length} 项，发布时间 ${formatTime(published.definition.publishedAt)}。员工启停状态未随发布自动改变。`" />
    </template>
    <el-empty v-else-if="!loadError" description="从管理员员工列表中选择员工；未发布草稿也可在这里编辑" />

    <el-dialog v-model="createDialog" title="新建数字员工" width="520px" @closed="resetCreateForm">
      <el-form label-width="110px">
        <el-form-item label="员工编码" required>
          <el-input v-model="createForm.employeeCode" maxlength="64" />
        </el-form-item>
        <el-form-item label="显示名称" required>
          <el-input v-model="createForm.displayName" maxlength="128" />
        </el-form-item>
        <el-form-item label="创建原因" required>
          <el-input v-model="createForm.reason" type="textarea" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">创建为停用草稿</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="unsavedDialog" title="草稿有未保存修改" width="480px" :show-close="false">
      <p>切换员工前，保存当前修改、放弃修改，或继续留在当前员工。</p>
      <template #footer>
        <el-button @click="unsavedDialog = false; pendingEmployee = undefined">继续编辑</el-button>
        <el-button type="danger" @click="discardAndSwitch">放弃修改并切换</el-button>
        <el-button type="primary" :loading="saving" @click="saveAndSwitch">保存并切换</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="versionsDrawer" title="员工发布版本" size="78%">
      <div v-if="selectedEmployee">
        <p class="muted">版本正文仅管理员可见。历史版本不可变；恢复只复制到当前草稿，随后需重新校验并发布。</p>
        <el-table v-loading="versionsLoading" :data="versions" border row-key="versionId" @row-click="openVersion">
          <el-table-column prop="versionNo" label="版本" width="85"><template #default="{ row }">v{{ row.versionNo }}</template></el-table-column>
          <el-table-column prop="profile" label="Profile" width="190" />
          <el-table-column prop="contentHash" label="内容 Hash" min-width="210" />
          <el-table-column prop="publishedBy" label="发布人" width="100" />
          <el-table-column prop="publishedAt" label="发布时间" min-width="175"><template #default="{ row }">{{ formatTime(row.publishedAt) }}</template></el-table-column>
          <el-table-column label="当前" width="75"><template #default="{ row }">{{ row.current ? '是' : '否' }}</template></el-table-column>
        </el-table>
        <div class="pager">
          <el-button :disabled="versionPageIndex === 0 || versionsLoading" @click="previousVersions">上一页</el-button>
          <span>第 {{ versionPageIndex + 1 }} 页</span>
          <el-button :disabled="!versionPage?.hasMore || versionsLoading" @click="nextVersions">下一页</el-button>
        </div>
      </div>
      <el-empty v-if="selectedVersion" description="版本详情" />
      <el-card v-if="selectedVersion" class="block">
        <template #header>
          <div class="section-head">
            <span>v{{ selectedVersion.summary.versionNo }} · {{ selectedVersion.summary.profile }}</span>
            <div class="head-actions">
              <el-select v-model="comparisonSelect" clearable placeholder="选择比较版本" style="width: 230px">
                <el-option v-for="item in versions" :key="item.versionId" :value="item.versionId" :label="`v${item.versionNo} · ${item.profile}`" />
              </el-select>
              <el-button type="primary" @click="beginRestore">恢复为草稿</el-button>
            </div>
          </div>
        </template>
        <div class="meta">
          <span>Hash：<code>{{ selectedVersion.summary.contentHash }}</code></span>
          <span>发布人：{{ selectedVersion.summary.publishedBy }}</span>
          <span>时间：{{ formatTime(selectedVersion.summary.publishedAt) }}</span>
        </div>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="模型供应商">{{ selectedVersion.modelProvider }}</el-descriptions-item>
          <el-descriptions-item label="模型名称">{{ selectedVersion.modelName }}</el-descriptions-item>
          <el-descriptions-item label="能力修订" :span="2">
            {{ selectedVersion.capabilities.map(item => `${item.capabilityCode}@${item.revision}`).join('、') || '无' }}
          </el-descriptions-item>
          <el-descriptions-item label="固定成员" :span="2">
            <div v-for="member in selectedVersion.members" :key="member.roleId">
              {{ member.roleId }} → {{ member.displayName }}（{{ member.employeeCode }}）v{{ member.versionNo }} · {{ member.contentHash.slice(0, 12) }} · 声明 {{ member.steps }} 步
            </div>
            <span v-if="selectedVersion.members.length === 0">无</span>
          </el-descriptions-item>
          <el-descriptions-item label="允许运行包">{{ selectedVersion.runtimeBundleAvailable ? '存在' : '缺失' }}</el-descriptions-item>
          <el-descriptions-item label="运行预算">{{ JSON.stringify(selectedVersion.configuration.runtimePolicy) }}</el-descriptions-item>
        </el-descriptions>
        <h4>系统指令</h4>
        <pre class="instructions">{{ selectedVersion.instructions }}</pre>
        <section v-if="comparisonVersion" class="compare block">
          <h4>与 v{{ comparisonVersion.summary.versionNo }} 的业务字段差异</h4>
          <el-empty v-if="comparisonDiff.length === 0" description="业务内容一致" :image-size="60" />
          <el-table v-else :data="comparisonDiff" border size="small">
            <el-table-column prop="field" label="字段" width="180" />
            <el-table-column label="所选版本" min-width="220"><template #default="{ row }"><pre>{{ row.left }}</pre></template></el-table-column>
            <el-table-column label="比较版本" min-width="220"><template #default="{ row }"><pre>{{ row.right }}</pre></template></el-table-column>
          </el-table>
        </section>
      </el-card>
    </el-drawer>

    <el-dialog v-model="restoreDialog" title="恢复历史内容为草稿" width="640px">
      <template v-if="restoreSource">
        <el-alert type="warning" :closable="false" class="block"
                  title="这会替换当前草稿的指令、模型、能力修订、运行配置和固定成员；不会改动任何已发布版本。" />
        <el-descriptions :column="1" border>
          <el-descriptions-item label="来源版本">v{{ restoreSource.summary.versionNo }} · {{ restoreSource.summary.contentHash }}</el-descriptions-item>
          <el-descriptions-item label="替换指令">{{ form?.instructions.length ?? 0 }} → {{ restoreSource.instructions.length }} 字符</el-descriptions-item>
          <el-descriptions-item label="模型">{{ form?.modelProvider }}/{{ form?.modelName }} → {{ restoreSource.modelProvider }}/{{ restoreSource.modelName }}</el-descriptions-item>
          <el-descriptions-item label="能力修订">{{ form?.selected.length ?? 0 }} → {{ restoreSource.capabilities.length }} 项</el-descriptions-item>
          <el-descriptions-item label="固定成员版本">{{ form?.configuration.members.length ?? 0 }} → {{ restoreSource.members.length }} 项；来源 profile {{ restoreSource.summary.profile }}</el-descriptions-item>
        </el-descriptions>
      </template>
      <el-form label-width="90px" class="block">
        <el-form-item label="恢复理由" required>
          <el-input v-model="restoreReason" type="textarea" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="restoreDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!restoreReason.trim()" :loading="restoring" @click="restore">复制到草稿</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import * as adminApi from '../../api/admin'
import type {
  Capability, CreatedEmployee, EmployeeAdminPage, EmployeeAdminSummary, EmployeeRuntimeConfiguration,
  EmployeeRuntimeOptions, EmployeeVersionDetail, EmployeeVersionPage, EmployeeVersionSummary,
  PublishedEmployee, ValidationIssue, ValidationResult
} from '../../api/admin'
import { formatTime, notifyError, notifySuccess, toMessage } from '../../utils/notify'
import RuntimeConfigurationEditor from './RuntimeConfigurationEditor.vue'

interface DraftForm {
  instructions: string
  modelProvider: string
  modelName: string
  selected: string[]
  configuration: EmployeeRuntimeConfiguration
  reason: string
}

const employees = ref<EmployeeAdminSummary[]>([])
const employeePage = ref<EmployeeAdminPage>()
const catalog = ref<Capability[]>([])
const options = ref<EmployeeRuntimeOptions>()
const employeeId = ref<number>()
const selectedEmployee = ref<EmployeeAdminSummary>()
const form = ref<DraftForm>()
const formRef = ref<FormInstance>()
const draftRevision = ref(0)
const updatedAt = ref('')
const savedSnapshot = ref('')
const pageError = ref('')
const loadError = ref('')
const validation = ref<ValidationResult>()
const published = ref<PublishedEmployee>()
const filters = reactive({ query: '', enabled: '', published: '' })
const cursorStack = ref<Array<string | undefined>>([undefined])
const pageIndex = ref(0)
const currentCursor = ref<string>()
const loading = ref(false)
const employeesLoading = ref(false)
const saving = ref(false)
const validating = ref(false)
const publishing = ref(false)
const metadataSaving = ref(false)
const creating = ref(false)
const versionsLoading = ref(false)
const restoring = ref(false)
const versionsDrawer = ref(false)
const createDialog = ref(false)
const unsavedDialog = ref(false)
const restoreDialog = ref(false)
const metadata = reactive({ displayName: '', reason: '' })
const createForm = reactive({ employeeCode: '', displayName: '', reason: '' })
const restoreReason = ref('')
const pendingEmployee = ref<EmployeeAdminSummary>()
const versions = ref<EmployeeVersionSummary[]>([])
const versionPage = ref<EmployeeVersionPage>()
const versionPageIndex = ref(0)
const versionCursorStack = ref<Array<string | undefined>>([undefined])
const selectedVersion = ref<EmployeeVersionDetail>()
const restoreSource = ref<EmployeeVersionDetail>()
const comparisonVersionId = ref<number>()
const comparisonVersion = ref<EmployeeVersionDetail>()
let employeeRequestSequence = 0
let draftRequestSequence = 0
let versionRequestSequence = 0
const detailsCache = new Map<number, EmployeeVersionDetail>()
const pendingCommandIds = new Map<string, { key: string; requestId: string }>()

const dirty = computed(() => !!form.value && snapshot(form.value) !== savedSnapshot.value)
const selectedCapabilities = computed(() => catalog.value.filter(item => form.value?.selected.includes(valueOf(item))))
const canPublish = computed(() => !!form.value && !dirty.value && !!validation.value?.publishable
  && validation.value.draftRevision === draftRevision.value
  && options.value?.profiles.find(item => item.profile === form.value?.configuration.profile)?.publishEnabled === true)
const comparisonDiff = computed(() => {
  if (!selectedVersion.value || !comparisonVersion.value) return []
  const left = selectedVersion.value
  const right = comparisonVersion.value
  const fields: Array<[string, unknown, unknown]> = [
    ['系统指令', left.instructions, right.instructions],
    ['模型供应商', left.modelProvider, right.modelProvider],
    ['模型名称', left.modelName, right.modelName],
    ['运行配置', left.configuration, right.configuration],
    ['能力修订', left.capabilities.map(item => `${item.capabilityCode}@${item.revision}`), right.capabilities.map(item => `${item.capabilityCode}@${item.revision}`)],
    ['固定成员版本', left.members.map(member => `${member.roleId}:${member.employeeId}@${member.definitionVersionId}/${member.steps}`),
      right.members.map(member => `${member.roleId}:${member.employeeId}@${member.definitionVersionId}/${member.steps}`)]
  ]
  return fields.filter(([, first, second]) => JSON.stringify(first) !== JSON.stringify(second))
    .map(([field, first, second]) => ({ field, left: pretty(first), right: pretty(second) }))
})

const rules: FormRules<DraftForm> = {
  instructions: [{ required: true, min: 1, max: 12000, message: '系统指令长度为 1–12000 字符', trigger: 'blur' }],
  modelProvider: [{ required: true, message: '请选择模型供应商', trigger: 'change' }],
  modelName: [{ required: true, min: 1, max: 128, message: '模型名称长度为 1–128 字符', trigger: 'blur' }],
  selected: [{ validator: (_rule, value: string[], callback) => {
    if (form.value?.configuration.profile === 'LEGACY_STABLE' && (!value || value.length === 0)) callback(new Error('LEGACY_STABLE 至少选择一项工具或 MCP 能力'))
    else callback()
  }, trigger: 'change' }],
  reason: [{ required: true, message: '请填写变更原因', trigger: 'blur' }, { max: 500, message: '原因最多 500 字', trigger: 'blur' }]
}

function valueOf(capability: Capability) { return `${capability.capabilityCode}|${capability.revision}` }
function toSelections(selected: string[]): adminApi.CapabilitySelection[] {
  return selected.map(value => {
    const separator = value.lastIndexOf('|')
    return { capabilityCode: value.slice(0, separator), revision: value.slice(separator + 1) }
  })
}

function snapshot(value: DraftForm): string {
  return JSON.stringify({ instructions: value.instructions, modelProvider: value.modelProvider,
    modelName: value.modelName, selected: [...value.selected].sort(), configuration: value.configuration })
}

function toForm(draft: adminApi.AgentDefinitionDraft): DraftForm {
  return {
    instructions: draft.instructions ?? '', modelProvider: draft.modelProvider ?? '', modelName: draft.modelName ?? '',
    selected: draft.capabilities.map(item => `${item.capabilityCode}|${item.revision}`),
    configuration: draft.configuration,
    reason: ''
  }
}

async function loadEmployees(cursor = currentCursor.value) {
  const sequence = ++employeeRequestSequence
  employeesLoading.value = true
  pageError.value = ''
  try {
    const page = await adminApi.listManagedEmployees({
      query: filters.query.trim() || undefined,
      enabled: filters.enabled === '' ? undefined : filters.enabled === 'true',
      published: filters.published === '' ? undefined : filters.published === 'true',
      cursor,
      limit: 20
    })
    if (sequence !== employeeRequestSequence) return
    employees.value = page.items
    employeePage.value = page
    if (selectedEmployee.value) {
      const current = page.items.find(item => item.employeeId === selectedEmployee.value?.employeeId)
      if (current) selectedEmployee.value = current
    }
  } catch (error) {
    if (sequence === employeeRequestSequence) pageError.value = toMessage(error, '员工列表加载失败')
  } finally {
    if (sequence === employeeRequestSequence) employeesLoading.value = false
  }
}

async function reloadAll() {
  pageError.value = ''
  try {
    const [capabilityItems, runtimeOptions] = await Promise.all([adminApi.capabilities(), adminApi.employeeRuntimeOptions()])
    catalog.value = capabilityItems
    options.value = runtimeOptions
  } catch (error) {
    pageError.value = toMessage(error, '配置选项加载失败')
    return
  }
  await loadEmployees()
  if (employeeId.value) await loadDraft(employeeId.value)
}

async function resetEmployeePage() {
  currentCursor.value = undefined
  cursorStack.value = [undefined]
  pageIndex.value = 0
  await loadEmployees(undefined)
}

async function nextEmployees() {
  if (!employeePage.value?.hasMore || !employeePage.value.nextCursor) return
  const next = employeePage.value.nextCursor
  cursorStack.value = [...cursorStack.value.slice(0, pageIndex.value + 1), next]
  pageIndex.value++
  currentCursor.value = next
  await loadEmployees(next)
}

async function previousEmployees() {
  if (pageIndex.value <= 0) return
  pageIndex.value--
  currentCursor.value = cursorStack.value[pageIndex.value]
  await loadEmployees(currentCursor.value)
}

async function requestSelect(employee: EmployeeAdminSummary) {
  if (employee.employeeId === employeeId.value) return
  if (dirty.value) {
    pendingEmployee.value = employee
    unsavedDialog.value = true
    return
  }
  await selectEmployee(employee)
}

async function selectEmployee(employee: EmployeeAdminSummary) {
  employeeId.value = employee.employeeId
  selectedEmployee.value = employee
  metadata.displayName = employee.displayName
  metadata.reason = ''
  form.value = undefined
  savedSnapshot.value = ''
  validation.value = undefined
  published.value = undefined
  pendingCommandIds.delete(`publish:${employee.employeeId}`)
  await loadDraft(employee.employeeId)
}

async function discardAndSwitch() {
  const target = pendingEmployee.value
  unsavedDialog.value = false
  pendingEmployee.value = undefined
  if (target) await selectEmployee(target)
}

async function saveAndSwitch() {
  const target = pendingEmployee.value
  if (!target || !await save()) return
  unsavedDialog.value = false
  pendingEmployee.value = undefined
  await selectEmployee(target)
}

async function loadDraft(id: number | undefined = employeeId.value) {
  if (!id) return
  const sequence = ++draftRequestSequence
  loading.value = true
  loadError.value = ''
  try {
    const draft = await adminApi.getDraft(id)
    if (sequence !== draftRequestSequence || id !== employeeId.value) return
    form.value = toForm(draft)
    draftRevision.value = draft.draftRevision
    updatedAt.value = draft.updatedAt
    savedSnapshot.value = snapshot(form.value)
    validation.value = undefined
    published.value = undefined
  } catch (error) {
    if (sequence === draftRequestSequence && id === employeeId.value) {
      form.value = undefined
      loadError.value = toMessage(error, '草稿加载失败')
    }
  } finally {
    if (sequence === draftRequestSequence) loading.value = false
  }
}

async function save(): Promise<boolean> {
  if (!formRef.value || !form.value || !employeeId.value) return false
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return false
  saving.value = true
  try {
    const draft = await adminApi.saveDraft(employeeId.value, {
      expectedDraftRevision: draftRevision.value,
      instructions: form.value.instructions.trim(),
      modelProvider: form.value.modelProvider.trim(),
      modelName: form.value.modelName.trim(),
      capabilities: toSelections(form.value.selected),
      configuration: form.value.configuration,
      reason: form.value.reason.trim()
    })
    draftRevision.value = draft.draftRevision
    updatedAt.value = draft.updatedAt
    form.value = toForm(draft)
    metadata.reason = ''
    validation.value = undefined
    published.value = undefined
    savedSnapshot.value = snapshot(form.value)
    pendingCommandIds.delete(`publish:${employeeId.value}`)
    notifySuccess(`草稿已保存，修订号 ${draft.draftRevision}`)
    return true
  } catch (error) {
    notifyError(error, '草稿保存失败；本地编辑仍保留，可读取最新草稿后手工重做')
    return false
  } finally {
    saving.value = false
  }
}

async function validate() {
  if (!employeeId.value || dirty.value) return
  validating.value = true
  try {
    validation.value = await adminApi.validateDraft(employeeId.value)
    if (validation.value.publishable) notifySuccess('校验通过')
  } catch (error) {
    notifyError(error, '草稿校验失败')
  } finally {
    validating.value = false
  }
}

function commandId(key: string) {
  const existing = pendingCommandIds.get(key)
  if (existing) return existing.requestId
  const requestId = crypto.randomUUID()
  pendingCommandIds.set(key, { key, requestId })
  return requestId
}

async function publish() {
  if (!canPublish.value || !employeeId.value || !form.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  publishing.value = true
  const employee = employeeId.value
  const requestId = commandId(`publish:${employee}:${draftRevision.value}`)
  try {
    published.value = await adminApi.publishDraft(employee, draftRevision.value, requestId, form.value.reason.trim())
    pendingCommandIds.delete(`publish:${employee}:${draftRevision.value}`)
    notifySuccess(`已发布 v${published.value.definition.version}；员工启停状态保持原样`)
    await loadEmployees()
  } catch (error) {
    notifyError(error, '发布结果可能未返回；再次点击会复用同一个请求标识')
  } finally {
    publishing.value = false
  }
}

async function saveMetadata() {
  if (!selectedEmployee.value || !metadata.displayName.trim() || !metadata.reason.trim()) return
  metadataSaving.value = true
  const employee = selectedEmployee.value
  const key = `rename:${employee.employeeId}:${employee.rowVersion}:${metadata.displayName.trim()}:${metadata.reason.trim()}`
  try {
    const updated = await adminApi.updateEmployee(employee.employeeId, employee.rowVersion, metadata.displayName.trim(),
      commandId(key), metadata.reason.trim())
    selectedEmployee.value = updated
    metadata.displayName = updated.displayName
    metadata.reason = ''
    pendingCommandIds.delete(key)
    await loadEmployees()
    notifySuccess('员工名称已更新')
  } catch (error) {
    notifyError(error, '员工名称更新失败；若服务端已执行，请用相同内容重试')
  } finally {
    metadataSaving.value = false
  }
}

async function changeStatus(employee: EmployeeAdminSummary) {
  let reason = employee.employeeId === employeeId.value ? metadata.reason.trim() : ''
  if (!reason) {
    try {
      const prompt = await ElMessageBox.prompt('启用和停用分别生效；停用不会自动取消已有 Run。',
        employee.enabled ? '停用员工' : '启用员工', {
          confirmButtonText: '继续', cancelButtonText: '取消', inputPlaceholder: '填写变更原因',
          inputValidator: value => !!value.trim() && value.length <= 500,
          inputErrorMessage: '原因必填且最多 500 字'
        })
      reason = prompt.value.trim()
      if (employee.employeeId === employeeId.value) metadata.reason = reason
    } catch { return }
  }
  const nextEnabled = !employee.enabled
  const key = `status:${employee.employeeId}:${employee.rowVersion}:${nextEnabled}:${reason}`
  try {
    const updated = await adminApi.setEmployeeEnabled(employee.employeeId, employee.rowVersion, nextEnabled,
      commandId(key), reason)
    if (employee.employeeId === employeeId.value) {
      selectedEmployee.value = updated
      metadata.displayName = updated.displayName
      metadata.reason = ''
    }
    pendingCommandIds.delete(key)
    await loadEmployees()
    notifySuccess(nextEnabled ? '员工已启用' : '员工已停用；现有 Session 与 Run 未被自动取消')
  } catch (error) {
    notifyError(error, nextEnabled ? '员工启用失败；请检查发布包和依赖状态' : '员工停用失败；可用相同操作重试')
  }
}

async function create() {
  if (!createForm.employeeCode.trim() || !createForm.displayName.trim() || !createForm.reason.trim()) {
    notifyError(new Error('员工编码、名称和原因均为必填'), '请补全新建员工信息')
    return
  }
  creating.value = true
  const key = `create:${createForm.employeeCode.trim()}:${createForm.displayName.trim()}:${createForm.reason.trim()}`
  try {
    const result: CreatedEmployee = await adminApi.createEmployee(createForm.employeeCode.trim(), createForm.displayName.trim(),
      commandId(key), createForm.reason.trim())
    createDialog.value = false
    pendingCommandIds.delete(key)
    await resetEmployeePage()
    const created = result.employee
    notifySuccess('已创建停用员工和初始草稿；普通用户不可见，需配置并发布后再单独启用')
    await selectEmployee(created)
  } catch (error) {
    notifyError(error, '员工创建失败；同一内容重试会复用请求标识')
  } finally {
    creating.value = false
  }
}

function resetCreateForm() {
  createForm.employeeCode = ''
  createForm.displayName = ''
  createForm.reason = ''
}

async function openVersions() {
  if (!employeeId.value) return
  versionsDrawer.value = true
  selectedVersion.value = undefined
  comparisonVersion.value = undefined
  comparisonVersionId.value = undefined
  versionPageIndex.value = 0
  versionCursorStack.value = [undefined]
  await loadVersions()
}

async function loadVersions(cursor = versionCursorStack.value[versionPageIndex.value]) {
  if (!employeeId.value) return
  const sequence = ++versionRequestSequence
  versionsLoading.value = true
  try {
    const page = await adminApi.employeeVersions(employeeId.value, cursor, 20)
    if (sequence !== versionRequestSequence) return
    versions.value = page.items
    versionPage.value = page
  } catch (error) {
    notifyError(error, '发布版本列表加载失败')
  } finally {
    if (sequence === versionRequestSequence) versionsLoading.value = false
  }
}

async function nextVersions() {
  if (!versionPage.value?.hasMore || !versionPage.value.nextCursor) return
  versionCursorStack.value = [...versionCursorStack.value.slice(0, versionPageIndex.value + 1), versionPage.value.nextCursor]
  versionPageIndex.value++
  await loadVersions(versionPage.value.nextCursor)
}

async function previousVersions() {
  if (versionPageIndex.value <= 0) return
  versionPageIndex.value--
  await loadVersions(versionCursorStack.value[versionPageIndex.value])
}

async function openVersion(row: EmployeeVersionSummary) {
  if (!employeeId.value) return
  selectedVersion.value = await loadVersionDetail(row.versionId)
  comparisonVersion.value = undefined
  comparisonVersionId.value = undefined
}

async function loadVersionDetail(versionId: number): Promise<EmployeeVersionDetail | undefined> {
  if (!employeeId.value) return undefined
  const cached = detailsCache.get(versionId)
  if (cached) return cached
  try {
    const detail = await adminApi.employeeVersion(employeeId.value, versionId)
    detailsCache.set(versionId, detail)
    return detail
  } catch (error) {
    notifyError(error, '版本详情加载失败')
    return undefined
  }
}

async function selectComparisonVersion(versionId: number | undefined) {
  comparisonVersion.value = versionId ? await loadVersionDetail(versionId) : undefined
}

const comparisonSelect = computed({
  get: () => comparisonVersionId.value,
  set: (value: number | undefined) => {
    comparisonVersionId.value = value
    void selectComparisonVersion(value)
  }
})

async function beginRestore() {
  if (!selectedVersion.value || !employeeId.value) return
  if (dirty.value) {
    notifyError(new Error('先保存或放弃本地编辑'), '恢复前需要处理未保存修改')
    return
  }
  restoreSource.value = selectedVersion.value
  restoreReason.value = ''
  restoreDialog.value = true
}

async function restore() {
  if (!employeeId.value || !restoreSource.value || !restoreReason.value.trim()) return
  restoring.value = true
  const employee = employeeId.value
  const versionId = restoreSource.value.summary.versionId
  const revision = draftRevision.value
  const key = `restore:${employee}:${versionId}:${revision}:${restoreReason.value.trim()}`
  try {
    const draft = await adminApi.restoreEmployeeDraft(employee, versionId, revision, commandId(key), restoreReason.value.trim())
    pendingCommandIds.delete(key)
    form.value = toForm(draft)
    draftRevision.value = draft.draftRevision
    updatedAt.value = draft.updatedAt
    savedSnapshot.value = snapshot(form.value)
    validation.value = undefined
    restoreDialog.value = false
    notifySuccess('历史内容已复制到草稿；请重新校验并发布为新版本')
  } catch (error) {
    notifyError(error, '恢复草稿失败；若结果未知，同一内容重试会复用请求标识')
  } finally {
    restoring.value = false
  }
}

function focusIssue(issue: ValidationIssue) {
  if (issue.fieldPath) formRef.value?.scrollToField(issue.fieldPath)
}

function pretty(value: unknown) { return typeof value === 'string' ? value : JSON.stringify(value, null, 2) }

onMounted(reloadAll)
</script>

<style scoped>
.block { margin-bottom: 18px; }
.page-head, .section-head, .head-actions, .filter-bar, .pager { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.filter-bar { justify-content: flex-start; flex-wrap: wrap; margin-bottom: 14px; }
.pager { justify-content: center; margin-top: 14px; color: #667085; }
.meta { display: flex; flex-wrap: wrap; gap: 24px; color: #667085; font-size: 13px; margin-bottom: 16px; }
.muted, small { color: #667085; }
.tag-gap { margin-left: 6px; }
.metadata-form :deep(.el-form-item__content) { display: flex; gap: 12px; }
.issues { margin: 0; padding-left: 18px; }
.issue-link { border: 0; background: none; color: #409eff; cursor: pointer; padding: 0; margin-right: 8px; }
.instructions { white-space: pre-wrap; overflow-wrap: anywhere; background: #f7f8fa; border-radius: 6px; padding: 12px; max-height: 380px; overflow: auto; }
.compare pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; max-height: 220px; overflow: auto; }
</style>

<template>
  <div class="runtime-editor">
    <el-form label-width="170px" class="block">
      <el-form-item label="运行 Profile">
        <el-select :model-value="configuration.profile" style="width: 360px" @change="changeProfile">
          <el-option v-for="option in options.profiles" :key="option.profile" :value="option.profile"
                     :label="option.profile">
            <span>{{ option.profile }}</span>
            <el-tag v-if="!option.publishEnabled" size="small" type="info" class="option-tag">未开放发布</el-tag>
          </el-option>
        </el-select>
        <el-alert v-if="selectedProfile && !selectedProfile.publishEnabled" class="inline-alert" type="warning"
                  :closable="false" :title="`当前配置尚未开放发布（${selectedProfile.disabledReasonCode ?? 'PROFILE_DISABLED'}）`" />
      </el-form-item>
      <el-form-item label="最大迭代次数">
        <el-input-number :model-value="configuration.runtimePolicy.maxIterations"
                         :min="options.maxIterations.min" :max="options.maxIterations.max"
                         @change="updateMaxIterations" />
      </el-form-item>
      <el-form-item label="最大并行委派数">
        <el-input-number :model-value="configuration.runtimePolicy.maxParallelDelegations"
                         :min="options.maxParallelDelegations.min" :max="options.maxParallelDelegations.max"
                         @change="updateMaxParallelDelegations" />
      </el-form-item>
      <el-form-item label="每次 Run 专家调用上限">
        <el-input-number :model-value="configuration.runtimePolicy.maxExpertInvocationsPerRun"
                         :min="options.maxExpertInvocationsPerRun.min" :max="options.maxExpertInvocationsPerRun.max"
                         @change="updateMaxExpertInvocations" />
      </el-form-item>
      <el-form-item label="同步超时（秒）">
        <el-input-number :model-value="configuration.runtimePolicy.syncTimeoutSeconds"
                         :min="options.syncTimeoutSeconds.min" :max="options.syncTimeoutSeconds.max"
                         @change="updateSyncTimeout" />
      </el-form-item>
      <el-form-item label="长期记忆">
        <el-tag type="info">关闭 · 当前不可启用</el-tag>
      </el-form-item>
    </el-form>

    <template v-if="isTeam">
      <el-divider content-position="left">固定成员（精确发布版本）</el-divider>
      <el-alert type="info" :closable="false" class="block"
                title="每位成员固定到一个已发布版本。新增成员只能选择启用员工；已有冻结引用仍保留用于校验。" />
      <el-table :data="configuration.members" border size="small" class="block">
        <el-table-column label="角色标识" min-width="145">
          <template #default="{ $index, row }">
            <el-input :model-value="row.roleId" maxlength="32" @update:model-value="updateMemberRole($index, $event)" />
          </template>
        </el-table-column>
        <el-table-column label="员工" min-width="220">
          <template #default="{ $index, row }">
            <el-select :model-value="row.employeeId || undefined" filterable clearable placeholder="选择员工"
                       @change="changeMemberEmployee($index, $event)">
              <el-option v-for="employee in memberEmployeeChoices(row.employeeId)" :key="employee.employeeId"
                         :value="employee.employeeId"
                         :label="`${employee.displayName}（${employee.employeeCode}）${employee.enabled ? '' : ' · 已停用'}`" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="精确版本" min-width="240">
          <template #default="{ $index, row }">
            <el-select :model-value="row.definitionVersionId || undefined" filterable clearable
                       placeholder="选择已发布版本" :loading="loadingVersions.has(row.employeeId)"
                       @change="updateMemberVersion($index, $event)">
              <el-option v-for="version in versionsFor(row.employeeId)" :key="version.versionId"
                         :value="version.versionId" :disabled="!isMemberProfileSupported(version.profile)"
                         :label="`v${version.versionNo} · ${version.profile} · ${version.contentHash.slice(0, 10)}`" />
            </el-select>
            <small v-if="row.definitionVersionId && !versionsFor(row.employeeId).some(version => version.versionId === row.definitionVersionId)"
                   class="version-note">
              当前冻结引用 ID {{ row.definitionVersionId }} 保留在草稿中；列表未包含它时不会自动替换。
            </small>
            <small v-if="versionErrors[row.employeeId]" class="version-error">
              {{ versionErrors[row.employeeId] }}
              <el-button link type="primary" :disabled="loadingVersions.has(row.employeeId)"
                         @click="loadVersions(row.employeeId)">重试读取</el-button>
            </small>
            <small v-else-if="versionHasMore[row.employeeId]" class="version-note">
              版本较多，当前已加载 {{ versionsFor(row.employeeId).length }} 项。
              <el-button link type="primary" :disabled="loadingVersions.has(row.employeeId)"
                         @click="loadMoreVersions(row.employeeId)">继续加载</el-button>
            </small>
          </template>
        </el-table-column>
        <el-table-column label="声明步数" width="145">
          <template #default="{ $index, row }">
            <el-input-number :model-value="row.steps" :min="1" :max="8"
                             @change="updateMemberSteps($index, $event)" />
            <small class="muted">有效上限 {{ effectiveSteps(row.steps) }}</small>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="82">
          <template #default="{ $index }">
            <el-button link type="danger" @click="removeMember($index)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button class="block" :disabled="configuration.members.length >= memberLimit" @click="addMember">
        添加固定成员（{{ configuration.members.length }}/{{ memberLimit }}）
      </el-button>

      <el-divider content-position="left">Team 角色与委派声明</el-divider>
      <el-form label-width="170px">
        <el-form-item label="默认角色">
          <el-select :model-value="team.defaultRoleId" placeholder="选择固定成员角色"
                     @change="updateDefaultRole">
            <el-option v-for="member in configuration.members" :key="member.roleId" :value="member.roleId"
                       :label="member.roleId || '（未命名）'" />
          </el-select>
        </el-form-item>
        <el-form-item label="用户可选角色">
          <el-select :model-value="team.userSelectableRoles" multiple placeholder="选择固定成员角色"
                     @change="updateSelectableRoles">
            <el-option v-for="member in configuration.members" :key="member.roleId" :value="member.roleId"
                       :label="member.roleId || '（未命名）'" />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="muted">以下是管理员声明的允许委派关系；当前界面仅开放协调者作为委派来源，不代表平台会自动调度。</p>
      <el-table :data="team.allowedDelegations" border size="small" class="block">
        <el-table-column label="来源" width="180"><template #default>协调者（coordinator）</template></el-table-column>
        <el-table-column label="允许目标" min-width="260">
          <template #default="{ $index, row }">
            <el-select :model-value="row.toRoleId" placeholder="固定成员或只读工厂目标"
                       @change="updateDelegationTarget($index, $event)">
              <el-option v-for="role in targetRoles" :key="role.id" :value="role.id" :label="role.label" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="82">
          <template #default="{ $index }">
            <el-button link type="danger" @click="removeDelegation($index)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button class="block" @click="addDelegation">添加委派关系</el-button>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import * as adminApi from '../../api/admin'
import type { EmployeeAdminSummary, EmployeeRuntimeConfiguration, EmployeeRuntimeOptions, EmployeeVersionSummary, RuntimeProfile } from '../../api/admin'

const props = defineProps<{
  modelValue: EmployeeRuntimeConfiguration
  options: EmployeeRuntimeOptions
  employees: EmployeeAdminSummary[]
}>()
const emit = defineEmits<{ 'update:modelValue': [value: EmployeeRuntimeConfiguration] }>()
const versionLists = reactive<Record<number, EmployeeVersionSummary[]>>({})
const loadingVersions = reactive(new Set<number>())
const versionErrors = reactive<Record<number, string>>({})
const versionHasMore = reactive<Record<number, boolean>>({})
const versionNextCursors = reactive<Record<number, string | undefined>>({})

const configuration = computed(() => props.modelValue)
const selectedProfile = computed(() => props.options.profiles.find(item => item.profile === configuration.value.profile))
const isTeam = computed(() => configuration.value.profile === 'TEAM_READONLY' || configuration.value.profile === 'TEAM_AUTONOMOUS_READONLY')
const memberLimit = computed(() => selectedProfile.value?.memberLimit ?? 0)
const team = computed(() => configuration.value.team ?? { defaultRoleId: '', userSelectableRoles: [], allowedDelegations: [] })
const targetRoles = computed(() => [
  ...configuration.value.members.map(member => ({ id: member.roleId, label: `固定成员 · ${member.roleId || '未命名'}` })),
  ...props.options.reservedDelegationTargets
    .filter(target => target.supportedProfiles.includes(configuration.value.profile))
    .map(target => ({ id: target.targetRoleId, label: `${target.kind === 'BUILTIN_GENERAL_PURPOSE' ? '通用只读工厂' : '动态只读工厂'} · ${target.targetRoleId}` }))
])

function update(next: EmployeeRuntimeConfiguration) { emit('update:modelValue', next) }

async function changeProfile(profile: RuntimeProfile) {
  const wasTeam = isTeam.value
  const willBeTeam = profile === 'TEAM_READONLY' || profile === 'TEAM_AUTONOMOUS_READONLY'
  if (wasTeam && !willBeTeam && (configuration.value.members.length > 0 || team.value.allowedDelegations.length > 0)) {
    try {
      await ElMessageBox.confirm('切换到非 Team Profile 会清空固定成员与 Team 关系。请确认已查看这些字段。', '确认切换运行配置', {
        confirmButtonText: '清空并切换', cancelButtonText: '继续编辑', type: 'warning'
      })
    } catch { return }
  }
  const policy = { ...configuration.value.runtimePolicy }
  if (willBeTeam && policy.maxExpertInvocationsPerRun === 0) policy.maxExpertInvocationsPerRun = 1
  update({
    ...configuration.value,
    schemaVersion: profile === 'LEGACY_STABLE' ? 1 : 2,
    profile,
    runtimePolicy: policy,
    team: willBeTeam ? configuration.value.team ?? { defaultRoleId: '', userSelectableRoles: [], allowedDelegations: [] } : null,
    members: willBeTeam ? configuration.value.members : []
  })
}

function updatePolicy<K extends keyof EmployeeRuntimeConfiguration['runtimePolicy']>(key: K, value: number | undefined) {
  if (key === 'memoryEnabled') return
  update({ ...configuration.value, runtimePolicy: { ...configuration.value.runtimePolicy, [key]: value ?? 0 } })
}

function updateMaxIterations(value: number | undefined) { updatePolicy('maxIterations', value) }
function updateMaxParallelDelegations(value: number | undefined) { updatePolicy('maxParallelDelegations', value) }
function updateMaxExpertInvocations(value: number | undefined) { updatePolicy('maxExpertInvocationsPerRun', value) }
function updateSyncTimeout(value: number | undefined) { updatePolicy('syncTimeoutSeconds', value) }
function updateMemberRole(index: number, value: string) { updateMember(index, 'roleId', value) }
function updateMemberVersion(index: number, value: number | undefined) { updateMember(index, 'definitionVersionId', value) }
function updateMemberSteps(index: number, value: number | undefined) { updateMember(index, 'steps', value) }
function updateDefaultRole(value: string) { updateTeam('defaultRoleId', value) }
function updateSelectableRoles(value: string[]) { updateTeam('userSelectableRoles', value) }
function updateDelegationTarget(index: number, value: string) { updateDelegation(index, value) }

function updateTeam<K extends keyof NonNullable<EmployeeRuntimeConfiguration['team']>>(key: K, value: NonNullable<EmployeeRuntimeConfiguration['team']>[K]) {
  update({ ...configuration.value, team: { ...team.value, [key]: value } })
}

function updateMember(index: number, key: 'roleId' | 'employeeId' | 'definitionVersionId' | 'steps', value: string | number | undefined) {
  const members = configuration.value.members.map((member, position) => position === index
    ? { ...member, [key]: value ?? (key === 'roleId' ? '' : 0) }
    : member)
  update({ ...configuration.value, members })
}

function updateDelegation(index: number, target: string) {
  const allowedDelegations = team.value.allowedDelegations.map((rule, position) => position === index
    ? { fromRoleId: 'coordinator', toRoleId: target }
    : rule)
  updateTeam('allowedDelegations', allowedDelegations)
}

function removeDelegation(index: number) {
  updateTeam('allowedDelegations', team.value.allowedDelegations.filter((_, position) => position !== index))
}

function addDelegation() {
  const first = targetRoles.value[0]
  if (!first) return
  updateTeam('allowedDelegations', [...team.value.allowedDelegations, { fromRoleId: 'coordinator', toRoleId: first.id }])
}

function addMember() {
  if (configuration.value.members.length >= memberLimit.value) return
  update({ ...configuration.value, members: [...configuration.value.members,
    { roleId: '', employeeId: 0, definitionVersionId: 0, steps: 1 }] })
}

function removeMember(index: number) {
  const members = configuration.value.members.filter((_, position) => position !== index)
  const roles = new Set(members.map(member => member.roleId))
  update({ ...configuration.value, members, team: {
    ...team.value,
    defaultRoleId: roles.has(team.value.defaultRoleId) ? team.value.defaultRoleId : '',
    userSelectableRoles: team.value.userSelectableRoles.filter(role => roles.has(role)),
    allowedDelegations: team.value.allowedDelegations.filter(rule => roles.has(rule.toRoleId))
  } })
}

function memberEmployeeChoices(currentId: number) {
  return props.employees.filter(employee => employee.enabled && employee.published || employee.employeeId === currentId)
}

async function changeMemberEmployee(index: number, employeeId: number | undefined) {
  const members = configuration.value.members.map((member, position) => position === index
    ? { ...member, employeeId: employeeId ?? 0, definitionVersionId: 0 }
    : member)
  update({ ...configuration.value, members })
  if (employeeId) await loadVersions(employeeId)
}

async function loadVersions(employeeId: number, append = false) {
  if (!employeeId || loadingVersions.has(employeeId)) return
  loadingVersions.add(employeeId)
  try {
    const all: EmployeeVersionSummary[] = append ? [...versionsFor(employeeId)] : []
    let cursor: string | undefined = append ? versionNextCursors[employeeId] : undefined
    let hasMore = false
    for (let pageNo = 0; pageNo < 20; pageNo++) {
      const page = await adminApi.employeeVersions(employeeId, cursor, 100)
      all.push(...page.items)
      if (!page.hasMore || !page.nextCursor) {
        hasMore = false
        cursor = undefined
        break
      }
      cursor = page.nextCursor
      hasMore = true
    }
    versionLists[employeeId] = all
    versionHasMore[employeeId] = hasMore
    versionNextCursors[employeeId] = hasMore ? cursor : undefined
    delete versionErrors[employeeId]
  } catch {
    versionErrors[employeeId] = '读取已发布版本失败；当前冻结版本引用保持不变，请重试。'
  } finally {
    loadingVersions.delete(employeeId)
  }
}

function loadMoreVersions(employeeId: number) {
  if (versionHasMore[employeeId] && versionNextCursors[employeeId]) void loadVersions(employeeId, true)
}

function versionsFor(employeeId: number) { return versionLists[employeeId] ?? [] }
function isMemberProfileSupported(profile: RuntimeProfile) { return props.options.supportedMemberProfiles.includes(profile) }
function effectiveSteps(steps: number) { return Math.min(4, steps, configuration.value.runtimePolicy.maxIterations) }

watch(() => configuration.value.members.map(member => member.employeeId).join(','), () => {
  for (const employeeId of new Set(configuration.value.members.map(member => member.employeeId).filter(id => id > 0))) {
    if (!versionLists[employeeId]) void loadVersions(employeeId)
  }
})
onMounted(() => {
  for (const employeeId of new Set(configuration.value.members.map(member => member.employeeId).filter(id => id > 0)))
    void loadVersions(employeeId)
})
</script>

<style scoped>
.runtime-editor { width: 100%; }
.block { margin-bottom: 16px; }
.option-tag { margin-left: 12px; }
.inline-alert { margin-top: 8px; }
.version-note, .version-error { display: block; margin-top: 4px; }
.version-error { color: #b54708; }
.muted, small { color: #667085; }
</style>

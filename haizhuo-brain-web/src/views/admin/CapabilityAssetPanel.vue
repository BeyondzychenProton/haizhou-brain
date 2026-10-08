<template>
  <section class="asset-panel">
    <div class="asset-head">
      <div>
        <h3>技能与知识资产</h3>
        <p class="muted">资产以不可变修订发布；内容只作为只读文本加载，不会变成可执行工具。</p>
      </div>
      <div class="toolbar">
        <el-button @click="refresh">刷新</el-button>
        <el-button type="primary" @click="createAsset">新增资产</el-button>
      </div>
    </div>

    <el-skeleton v-if="loading" :rows="3" animated />
    <el-empty v-else-if="!assets.length" description="还没有技能或知识资产" />
    <el-table v-else :data="assets" border stripe>
      <el-table-column prop="displayName" label="资产" min-width="150" />
      <el-table-column prop="capabilityCode" label="编码" min-width="190" />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ row.type === 'SKILL' ? 'Skill' : '知识' }}</template>
      </el-table-column>
      <el-table-column label="草稿" width="100">
        <template #default="{ row }">第 {{ row.draftRevision }} 版</template>
      </el-table-column>
      <el-table-column label="已发布" width="150">
        <template #default="{ row }">
          <span v-if="row.publishedRevision">修订 {{ row.publishedRevision }}</span>
          <el-tag v-else size="small" type="warning">尚未发布</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="内容哈希" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">{{ row.assetHash || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="editAsset(row.capabilityCode)">编辑草稿</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="existing ? '编辑资产草稿' : '新增技能 / 知识资产'" width="760px"
               :close-on-click-modal="false" destroy-on-close>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="能力编码" required>
            <el-input v-model="form.capabilityCode" :disabled="existing" maxlength="128"
                      placeholder="例如 skill.customer-analysis" @input="markDirty" />
          </el-form-item>
          <el-form-item label="资产类型" required>
            <el-select v-model="form.type" :disabled="existing" @change="markDirty">
              <el-option label="Skill" value="SKILL" />
              <el-option label="知识" value="KNOWLEDGE" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="显示名称" required>
          <el-input v-model="form.displayName" maxlength="128" @input="markDirty" />
        </el-form-item>
        <el-form-item label="说明" required>
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="1000" @input="markDirty" />
        </el-form-item>
        <div class="files-head">
          <div>
            <strong>文本文件</strong>
            <span class="muted">最多 100 个文件，单文件 256 KiB，资产总量 2 MiB</span>
          </div>
          <el-button size="small" @click="addFile">添加文件</el-button>
        </div>
        <div v-for="(file, index) in form.files" :key="index" class="asset-file">
          <div class="file-path-row">
            <el-input v-model="file.relativePath" placeholder="相对路径，如 SKILL.md 或 references/fields.md"
                      @input="markDirty" />
            <el-button link type="danger" :disabled="form.files.length <= 1" @click="removeFile(index)">删除</el-button>
          </div>
          <el-input v-model="file.content" type="textarea" :rows="7" resize="vertical"
                    placeholder="UTF-8 文本内容" @input="markDirty" />
        </div>
        <el-form-item label="操作原因" required>
          <el-input v-model="form.reason" maxlength="500" placeholder="保存或发布的原因" @input="markDirty" />
        </el-form-item>
        <el-alert v-if="form.type === 'SKILL'" type="info" :closable="false"
                  title="SKILL.md 必须含有 name 与 description front matter；name 需与编码末段一致。" />
        <el-alert v-else type="info" :closable="false" title="知识资产必须包含根目录 KNOWLEDGE.md。" />
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="visible = false">关闭</el-button>
          <el-button :loading="saving" @click="saveDraft">保存草稿</el-button>
          <el-button type="primary" :disabled="!existing || dirty || !draftRevision" :loading="publishing"
                     @click="publish">发布当前草稿</el-button>
        </span>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import * as assetApi from '../../api/capabilityAssets'
import type { CapabilityAssetKind, CapabilityAssetSummary } from '../../api/capabilityAssets'
import { notifyError, notifySuccess } from '../../utils/notify'

interface EditableFile { relativePath: string; content: string }
interface AssetForm {
  capabilityCode: string
  type: CapabilityAssetKind
  displayName: string
  description: string
  files: EditableFile[]
  reason: string
}

const assets = ref<CapabilityAssetSummary[]>([])
const loading = ref(false)
const visible = ref(false)
const saving = ref(false)
const publishing = ref(false)
const existing = ref(false)
const dirty = ref(false)
const draftRevision = ref(0)
const form = reactive<AssetForm>({
  capabilityCode: '', type: 'SKILL', displayName: '', description: '',
  files: [{ relativePath: 'SKILL.md', content: '' }], reason: ''
})

async function refresh() {
  loading.value = true
  try { assets.value = await assetApi.listCapabilityAssets() }
  catch (error) { notifyError(error, '资产列表加载失败') }
  finally { loading.value = false }
}

function resetForm(type: CapabilityAssetKind = 'SKILL') {
  form.capabilityCode = ''
  form.type = type
  form.displayName = ''
  form.description = ''
  form.files = [{ relativePath: type === 'SKILL' ? 'SKILL.md' : 'KNOWLEDGE.md', content: '' }]
  form.reason = ''
  existing.value = false
  draftRevision.value = 0
  dirty.value = false
}

function createAsset() {
  resetForm()
  visible.value = true
}

async function editAsset(code: string) {
  try {
    const draft = await assetApi.getCapabilityAssetDraft(code)
    form.capabilityCode = draft.capabilityCode
    form.type = draft.type
    form.displayName = draft.displayName
    form.description = draft.description
    form.files = draft.files.map(file => ({ relativePath: file.relativePath, content: file.content }))
    form.reason = ''
    existing.value = true
    draftRevision.value = draft.draftRevision
    dirty.value = false
    visible.value = true
  } catch (error) { notifyError(error, '资产草稿加载失败') }
}

function addFile() {
  form.files.push({ relativePath: '', content: '' })
  dirty.value = true
}

function removeFile(index: number) {
  form.files.splice(index, 1)
  dirty.value = true
}

function markDirty() { dirty.value = true }

async function saveDraft() {
  const code = form.capabilityCode.trim()
  if (!code || !form.reason.trim()) return
  saving.value = true
  try {
    const draft = await assetApi.saveCapabilityAssetDraft(code, {
      expectedDraftRevision: draftRevision.value,
      type: form.type,
      displayName: form.displayName,
      description: form.description,
      files: form.files,
      reason: form.reason
    })
    existing.value = true
    draftRevision.value = draft.draftRevision
    dirty.value = false
    await refresh()
    notifySuccess(`草稿已保存，第 ${draft.draftRevision} 版`)
  } catch (error) { notifyError(error, '草稿保存失败') }
  finally { saving.value = false }
}

async function publish() {
  if (!form.capabilityCode || !form.reason.trim() || dirty.value || !draftRevision.value) return
  try {
    await ElMessageBox.confirm(`发布「${form.displayName}」的第 ${draftRevision.value} 版？`, '发布资产', { type: 'warning' })
  } catch { return }
  publishing.value = true
  try {
    const requestId = globalThis.crypto.randomUUID()
    const revision = await assetApi.publishCapabilityAsset(form.capabilityCode, draftRevision.value, requestId, form.reason)
    await refresh()
    notifySuccess(`已发布修订 ${revision.revision}`)
    visible.value = false
  } catch (error) { notifyError(error, '资产发布失败') }
  finally { publishing.value = false }
}

onMounted(refresh)
</script>

<style scoped>
.asset-panel { margin-top: 28px; padding-top: 24px; border-top: 1px solid var(--el-border-color-lighter); }
.asset-head, .toolbar, .files-head, .file-path-row, .dialog-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.asset-head { margin-bottom: 14px; }
.asset-head h3 { margin: 0 0 6px; }
.asset-head p { margin: 0; }
.form-grid { display: grid; grid-template-columns: 1fr 180px; gap: 16px; }
.files-head { margin: 12px 0; }
.files-head > div { display: flex; flex-direction: column; gap: 4px; }
.asset-file { margin-bottom: 16px; padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; }
.file-path-row { margin-bottom: 10px; }
</style>

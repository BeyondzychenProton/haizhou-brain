import { httpClient } from './httpClient'

/* ---------------- 能力目录 ---------------- */

/** 与后端 CapabilityBinding.CapabilityType 对齐。 */
export type CapabilityType = 'SKILL' | 'TOOL' | 'MCP' | 'KNOWLEDGE'

/** 与后端 CapabilityCatalogEntry 字段完全一致（含 implementationKey 等平台侧字段）。 */
export interface Capability {
  capabilityRevisionId: number
  capabilityCode: string
  type: CapabilityType
  revision: string
  displayName: string
  description: string
  toolName: string
  implementationKey: string
  businessAction: string
  inputSchema: Record<string, unknown>
  enabled: boolean
  requiresConfirmation: boolean
}

export function capabilities() {
  return httpClient.get<Capability[]>('/api/admin/v1/capabilities').then(r => r.data)
}

export function setCapabilityStatus(capabilityCode: string, enabled: boolean, reason: string) {
  return httpClient
    .put<void>(`/api/admin/v1/capabilities/${encodeURIComponent(capabilityCode)}/status`, { enabled, reason })
    .then(r => r.data)
}

/* ---------------- 可信 MCP 连接与逐工具审核 ---------------- */

export interface McpConnection {
  id: number
  code: string
  displayName: string
  endpoint: string
  revision: number
  enabled: boolean
}

export interface McpToolDescriptor {
  name: string
  description: string
  inputSchema: Record<string, unknown>
  outputSchema: Record<string, unknown>
  readOnlyHint: boolean
}

export interface McpDiscovery {
  snapshotId: number
  tools: McpToolDescriptor[]
}

export interface McpDiscoverySnapshotSummary {
  snapshotId: number
  connectionRevision: number
  discoveredAt: string
  toolCount: number
}

export interface McpToolChange {
  name: string
  type: 'ADDED' | 'REMOVED' | 'CHANGED'
  changedFields: Array<'description' | 'inputSchema' | 'outputSchema' | 'readOnlyHint'>
  before: McpToolDescriptor | null
  after: McpToolDescriptor | null
}

export interface McpDiscoveryDiff {
  previous: McpDiscoverySnapshotSummary | null
  current: McpDiscoverySnapshotSummary | null
  changes: McpToolChange[]
}

export interface McpApproval {
  snapshotId: number
  capabilityCode: string
  revision: string
  modelToolName: string
  displayName: string
  description: string
  readOnly: boolean
  requiresConfirmation: boolean
  reason: string
}

export function mcpConnections() {
  return httpClient.get<McpConnection[]>('/api/admin/v1/mcp/connections').then(r => r.data)
}

export function createMcpConnection(code: string, displayName: string, endpoint: string, reason: string) {
  return httpClient.post<McpConnection>('/api/admin/v1/mcp/connections', { code, displayName, endpoint, reason })
    .then(r => r.data)
}

export function setMcpConnectionStatus(id: number, enabled: boolean, reason: string) {
  return httpClient.put<void>(`/api/admin/v1/mcp/connections/${id}/status`, { enabled, reason })
    .then(r => r.data)
}

export function discoverMcpTools(id: number) {
  return httpClient.post<McpDiscovery>(`/api/admin/v1/mcp/connections/${id}/discover`).then(r => r.data)
}

export function mcpDiscoveryDiff(id: number) {
  return httpClient.get<McpDiscoveryDiff>(`/api/admin/v1/mcp/connections/${id}/discoveries/diff`)
    .then(r => r.data)
}

export function approveMcpTool(id: number, remoteName: string, approval: McpApproval) {
  return httpClient.post<{ capabilityRevisionId: number }>(
    `/api/admin/v1/mcp/connections/${id}/tools/${encodeURIComponent(remoteName)}/approve`, approval
  ).then(r => r.data)
}

/** 用户级能力授权：授予与撤销都走同一个 enabled 开关，响应体为空。 */
export function setUserGrant(userId: number, capabilityCode: string, enabled: boolean, reason: string) {
  return httpClient
    .put<void>(`/api/admin/v1/users/${userId}/capability-grants/${encodeURIComponent(capabilityCode)}`, { enabled, reason })
    .then(r => r.data)
}

/* ---------------- 员工定义草稿与发布 ---------------- */

/** 与后端 CapabilitySelection 对齐。 */
export interface CapabilitySelection {
  capabilityCode: string
  revision: string
}

/** 与后端 AgentDefinitionDraft 对齐。 */
export interface AgentDefinitionDraft {
  employeeId: number
  draftRevision: number
  instructions: string
  modelProvider: string
  modelName: string
  capabilities: CapabilitySelection[]
  updatedAt: string
}

export interface DraftUpdatePayload {
  expectedDraftRevision: number
  instructions: string
  modelProvider: string
  modelName: string
  capabilities: CapabilitySelection[]
  reason: string
}

/** 与后端 ValidationIssue 对齐。 */
export interface ValidationIssue {
  code: string
  message: string
}

/** 与后端 ValidationResult 对齐。 */
export interface ValidationResult {
  publishable: boolean
  issues: ValidationIssue[]
  draftRevision: number
}

/** 值对象 TenantId 序列化为 { value } 形式。 */
interface TenantRef {
  value: number
}

/** 与后端 CapabilityBinding 对齐。 */
export interface CapabilityBinding {
  definitionVersionId: number
  type: CapabilityType
  referenceId: string
  revision: string
}

/** 与后端 AgentDefinitionVersion 对齐。 */
export interface AgentDefinitionVersion {
  id: number
  employeeId: number
  version: number
  instructions: string
  modelProvider: string
  modelName: string
  publishedAt: string
  contentHash: string
}

/** 与后端 DigitalEmployee 对齐。 */
export interface DigitalEmployee {
  id: number
  tenantId: TenantRef
  code: string
  displayName: string
  enabled: boolean
}

/** 与后端 PublishedEmployee 对齐。 */
export interface PublishedEmployee {
  employee: DigitalEmployee
  definition: AgentDefinitionVersion
  capabilities: CapabilityBinding[]
}

export function getDraft(employeeId: number) {
  return httpClient.get<AgentDefinitionDraft>(`/api/admin/v1/agents/${employeeId}/draft`).then(r => r.data)
}

export function saveDraft(employeeId: number, payload: DraftUpdatePayload) {
  return httpClient.put<AgentDefinitionDraft>(`/api/admin/v1/agents/${employeeId}/draft`, payload).then(r => r.data)
}

export function validateDraft(employeeId: number) {
  return httpClient.post<ValidationResult>(`/api/admin/v1/agents/${employeeId}/validate`).then(r => r.data)
}

export function publishDraft(employeeId: number, expectedDraftRevision: number, requestId: string, reason: string) {
  return httpClient
    .post<PublishedEmployee>(`/api/admin/v1/agents/${employeeId}/publish`, { expectedDraftRevision, requestId, reason })
    .then(r => r.data)
}

/* ---------------- 用户管理 ---------------- */

/** 与后端 PlatformUserStatus 对齐。 */
export type PlatformUserStatus = 'PENDING_ACTIVATION' | 'ACTIVE' | 'DISABLED'

/** 与后端 UserDirectoryEntry 对齐；手机号已脱敏，不含任何凭据字段。 */
export interface UserDirectoryEntry {
  userId: number
  mobileMasked: string
  status: PlatformUserStatus
  roles: string[]
}

/** 与后端 UserDirectoryPage 对齐。 */
export interface UserDirectoryPage {
  content: UserDirectoryEntry[]
  total: number
}

export interface UserListQuery {
  keyword?: string
  status?: PlatformUserStatus | ''
  limit?: number
  offset?: number
}

/** 新增待激活用户；一次性激活凭据只在本次响应里返回。 */
export interface CreatedUser {
  userId: number
  mobileNormalized: string
  activationToken: string
  activationExpiresAt: string
}

/** 重发激活凭据的响应。 */
export interface ActivationCredential {
  activationToken: string
  expiresAt: string
}

export function listUsers(query: UserListQuery = {}) {
  const params: Record<string, string | number> = {
    limit: query.limit ?? 20,
    offset: query.offset ?? 0
  }
  if (query.keyword?.trim()) params.keyword = query.keyword.trim()
  if (query.status) params.status = query.status
  return httpClient.get<UserDirectoryPage>('/api/admin/v1/users', { params }).then(r => r.data)
}

export function createUser(mobile: string, reason: string) {
  return httpClient.post<CreatedUser>('/api/admin/v1/users', { mobile, reason }).then(r => r.data)
}

export function reissueActivation(userId: number, reason: string) {
  return httpClient.post<ActivationCredential>(`/api/admin/v1/users/${userId}/activation`, { reason }).then(r => r.data)
}

export function grantAdmin(userId: number, reason: string) {
  return httpClient.put<void>(`/api/admin/v1/users/${userId}/roles/PLATFORM_ADMIN`, { reason }).then(r => r.data)
}

/** 后端该接口是 DELETE 且要求请求体，axios 需要用 data 选项承载。 */
export function revokeAdmin(userId: number, reason: string) {
  return httpClient
    .delete<void>(`/api/admin/v1/users/${userId}/roles/PLATFORM_ADMIN`, { data: { reason } })
    .then(r => r.data)
}

export function changeUserStatus(userId: number, status: PlatformUserStatus, reason: string) {
  return httpClient.put<void>(`/api/admin/v1/users/${userId}/status`, { status, reason }).then(r => r.data)
}

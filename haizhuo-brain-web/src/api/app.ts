import { httpClient } from './httpClient'
import type { RunStreamEvent } from './runStream'
export interface Employee { id:number; code:string; name:string; description:string; available:boolean }
export interface Session { sessionId:string; employeeId:number; definitionVersionId?:number|null; employeeName?:string; status:string; createdAt:string; lastActiveAt:string }
export interface Run { runId:string; sessionId:string; state:string; definitionVersionId:number; createdAt:string; queuePosition:number;
  executorRoleId?:string; executorEmployeeId?:number; executorDefinitionVersionId?:number; mode?:string }
export interface SessionRole { roleId:string; displayName:string; employeeId:number; definitionVersionId:number|null; selectable:boolean }
export interface ReferenceableResult { resultId:string; runId:string; kind:string; mediaType:string; bodySha256:string;
  byteSize:number; createdAt:string; executorRoleId:string|null; executorEmployeeId:number|null;
  executorDefinitionVersionId:number|null }
export interface RunResult { resultId:string|null; runId:string; mediaType:string; body:string; bodySha256:string;
  byteSize:number; schemaVersion:number; createdAt:string; legacySummary:boolean; executorRoleId:string|null;
  executorEmployeeId:number|null; executorDefinitionVersionId:number|null }
export interface RunEvent { runId:string; sequenceNo:number; type:string; content:string; createdAt:string }
/** 会话级持久事件，形状与后端统一事件信封一致。 */
export type SessionEvent = RunStreamEvent
export interface TimelineItem extends RunEvent { }
type TimelineWireItem = Omit<TimelineItem, 'runId'> & { runId: string | { value: string } }
export async function listEmployees(){return(await httpClient.get<Employee[]>('/api/v1/employees')).data}
export async function listSessions(){return(await httpClient.get<Session[]>('/api/v1/sessions')).data}
export async function getSession(id:string){return(await httpClient.get<Session>(`/api/v1/sessions/${id}`)).data}
export async function listRuns(sessionId:string){return(await httpClient.get<Run[]>(`/api/v1/sessions/${sessionId}/runs`)).data}
export async function createSession(employeeId:number){return(await httpClient.post<Session>('/api/v1/sessions',{employeeId})).data}
export async function getSessionRoles(sessionId:string){return(await httpClient.get<SessionRole[]>(`/api/v1/sessions/${sessionId}/roles`)).data}
export async function listReferenceableResults(sessionId:string){return(await httpClient.get<ReferenceableResult[]>(`/api/v1/sessions/${sessionId}/results`)).data}
export async function createRun(sessionId:string,input:string,targetRoleId?:string,referencedResultIds:string[]=[]){
  return(await httpClient.post<Run>(`/api/v1/sessions/${sessionId}/runs`,{
    clientRequestId:crypto.randomUUID(),input,targetRoleId,mode:'DIRECT',referencedResultIds,
  })).data
}
export async function getRun(runId:string){return(await httpClient.get<Run>(`/api/v1/sessions/runs/${runId}`)).data}
export async function getRunResult(runId:string){return(await httpClient.get<RunResult>(`/api/v1/sessions/runs/${runId}/result`)).data}
export async function getEvents(runId:string,after:number,limit=200){return(await httpClient.get<RunEvent[]>(`/api/v1/sessions/runs/${runId}/events`,{params:{after,limit}})).data}
/** 会话级补读/历史分页：返回统一信封，sessionCursor 是跨 Run 的续传游标。 */
export interface SessionEventPage { events:SessionEvent[]; cursorFloor:number; cursorExpired:boolean; nextCursor:number }
export interface SessionSnapshot { sessionId:string; runs:Run[]; events:SessionEvent[]; snapshotCursor:number; cursorFloor:number }
export async function getSessionSnapshot(sessionId:string){
  return(await httpClient.get<SessionSnapshot>(`/api/v1/sessions/${sessionId}/snapshot`,{params:{format:'v2'}})).data
}
export async function getSessionEvents(sessionId:string,after:number,limit=200){
  return(await httpClient.get<SessionEventPage>(`/api/v1/sessions/${sessionId}/events`,{params:{after,limit,format:'v2'}})).data
}
export async function timeline(sessionId:string):Promise<TimelineItem[]>{
  const items=(await httpClient.get<TimelineWireItem[]>(`/api/v1/sessions/${sessionId}/timeline`)).data
  // 兼容当前运行中的旧后端；新接口直接返回字符串 ID。
  return items.map(item=>({...item,runId:typeof item.runId==='string'?item.runId:item.runId.value}))
}
export async function cancelRun(runId:string){return(await httpClient.post<Run>(`/api/v1/sessions/runs/${runId}/cancel`)).data}
export async function guideRun(runId:string,content:string){return(await httpClient.post(`/api/v1/sessions/runs/${runId}/guidance`,{content})).data}

/* ---------------- 工具执行与人工审批 ---------------- */

/** 与后端 ToolExecutionResponse 对齐；inputJson 是原始入参字符串，前端只做展示。 */
export interface ToolExecution {
  toolExecutionId: string
  toolName: string
  state: string
  inputJson: string
  approvalDecision: string | null
  createdAt: string
  updatedAt: string
  /** 持久化交互请求；旧后端缺失时按空值兼容。 */
  interactionId?: string | null
  interactionType?: 'TOOL_APPROVAL' | 'USER_SELECTION' | string | null
  options?: InteractionOption[]
}

export interface InteractionOption {
  id: string
  label: string
  description?: string
}

/** 与后端 ToolDecisionResponse 对齐；decided 为 false 表示这是一次重复的幂等提交。 */
export interface ToolDecisionResult {
  toolExecutionId: string
  decided: boolean
}

export async function listToolExecutions(runId:string){
  return(await httpClient.get<ToolExecution[]>(`/api/v1/sessions/runs/${runId}/tool-executions`)).data
}

export async function decideToolExecution(runId:string,toolExecutionId:string,approve:boolean,reason?:string){
  return(await httpClient.post<ToolDecisionResult>(
    `/api/v1/sessions/runs/${runId}/tool-executions/${toolExecutionId}/decision`,{approve,reason})).data
}

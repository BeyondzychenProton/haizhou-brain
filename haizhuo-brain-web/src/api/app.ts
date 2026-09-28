import { httpClient } from './httpClient'
export interface Employee { id:number; code:string; name:string; description:string; available:boolean }
export interface Session { sessionId:string; employeeId:number; employeeName?:string; status:string; createdAt:string; lastActiveAt:string }
export interface Run { runId:string; sessionId:string; state:string; definitionVersionId:number; createdAt:string; queuePosition:number }
export interface RunEvent { runId:string; sequenceNo:number; type:string; content:string; createdAt:string }
export interface TimelineItem extends RunEvent { }
export async function listEmployees(){return(await httpClient.get<Employee[]>('/api/v1/employees')).data}
export async function listSessions(){return(await httpClient.get<Session[]>('/api/v1/sessions')).data}
export async function getSession(id:string){return(await httpClient.get<Session>(`/api/v1/sessions/${id}`)).data}
export async function listRuns(sessionId:string){return(await httpClient.get<Run[]>(`/api/v1/sessions/${sessionId}/runs`)).data}
export async function createSession(employeeId:number){return(await httpClient.post<Session>('/api/v1/sessions',{employeeId})).data}
export async function createRun(sessionId:string,input:string){return(await httpClient.post<Run>(`/api/v1/sessions/${sessionId}/runs`,{clientRequestId:crypto.randomUUID(),input})).data}
export async function getRun(runId:string){return(await httpClient.get<Run>(`/api/v1/sessions/runs/${runId}`)).data}
export async function getEvents(runId:string,after:number){return(await httpClient.get<RunEvent[]>(`/api/v1/sessions/runs/${runId}/events`,{params:{after}})).data}
export async function timeline(sessionId:string){return(await httpClient.get<TimelineItem[]>(`/api/v1/sessions/${sessionId}/timeline`)).data}
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

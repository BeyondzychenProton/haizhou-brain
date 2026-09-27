import { httpClient } from './httpClient'
export interface Employee { id:number; code:string; name:string; description:string; available:boolean }
export interface Session { sessionId:string; employeeId:number; employeeName?:string; status:string; createdAt:string; lastActiveAt:string }
export interface Run { runId:string; sessionId:string; state:string; definitionVersionId:number; createdAt:string }
export interface RunEvent { runId:string; sequenceNo:number; type:string; content:string; createdAt:string }
export async function listEmployees(){return(await httpClient.get<Employee[]>('/api/v1/employees')).data}
export async function listSessions(){return(await httpClient.get<Session[]>('/api/v1/sessions')).data}
export async function getSession(id:string){return(await httpClient.get<Session>(`/api/v1/sessions/${id}`)).data}
export async function createSession(employeeId:number){return(await httpClient.post<Session>('/api/v1/sessions',{employeeId})).data}
export async function createRun(sessionId:string,input:string){return(await httpClient.post<Run>(`/api/v1/sessions/${sessionId}/runs`,{clientRequestId:crypto.randomUUID(),input})).data}
export async function getRun(runId:string){return(await httpClient.get<Run>(`/api/v1/sessions/runs/${runId}`)).data}
export async function getEvents(runId:string,after:number){return(await httpClient.get<RunEvent[]>(`/api/v1/sessions/runs/${runId}/events`,{params:{after}})).data}

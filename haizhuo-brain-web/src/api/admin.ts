import { httpClient } from './httpClient'
export interface Capability { capabilityCode:string; revision:string; displayName:string; description:string; enabled:boolean; implementationKey?:string; businessAction?:string; inputSchema?:Record<string,unknown> }
export async function capabilities(){return(await httpClient.get<Capability[]>('/api/admin/v1/capabilities')).data}

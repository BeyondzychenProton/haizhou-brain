import { httpClient } from './httpClient'
export interface CurrentUser { userId:number; mobileMasked:string; roles:string[]; mustChangePassword:boolean }
export async function login(mobile:string,password:string){return(await httpClient.post('/api/v1/auth/login',{mobile,password})).data}
export async function me(){return(await httpClient.get<CurrentUser>('/api/v1/auth/me')).data}
export async function changePassword(currentPassword:string,newPassword:string){return(await httpClient.post('/api/v1/auth/password/change',{currentPassword,newPassword})).data}
export async function logout(){await httpClient.post('/api/v1/auth/logout')}

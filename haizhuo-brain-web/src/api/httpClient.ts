import axios from 'axios'
import router from '../router'
// CSRF token is obtained from the platform endpoint and is intentionally masked by Spring Security.
// Axios's cookie-to-header automation would replace it with the raw cookie value and cause a 403.
export const httpClient = axios.create({ baseURL: '/', withCredentials: true, withXSRFToken: false, timeout: 15000, headers: { 'Content-Type': 'application/json' } })
let csrfHeader = ''; let csrfToken = ''
export async function ensureCsrf() { if (csrfToken) return; const { data } = await httpClient.get<{headerName:string;token:string}>('/api/v1/auth/csrf'); csrfHeader=data.headerName; csrfToken=data.token }
httpClient.interceptors.request.use(async config => { if (config.method && !['get','head','options'].includes(config.method.toLowerCase())) { await ensureCsrf(); if (csrfHeader) config.headers.set(csrfHeader, csrfToken) } return config })
httpClient.interceptors.response.use(response=>response, async error=>{ if(error.response?.status===401&&router.currentRoute.value.name!=='login') await router.push({name:'login'}); return Promise.reject(error) })

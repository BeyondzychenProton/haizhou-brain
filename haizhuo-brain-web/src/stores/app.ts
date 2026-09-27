import { ref } from 'vue'
import { defineStore } from 'pinia'
import * as api from '../api/app'
export const useAppStore=defineStore('app',()=>{const employees=ref<api.Employee[]>([]);const sessions=ref<api.Session[]>([]);async function loadEmployees(){employees.value=await api.listEmployees()}async function loadSessions(){sessions.value=await api.listSessions()}return{employees,sessions,loadEmployees,loadSessions}})

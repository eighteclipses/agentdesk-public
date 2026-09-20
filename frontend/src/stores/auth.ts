import { defineStore } from 'pinia'
import { api } from '../api'

export interface User {
  id: number; username: string; displayName: string; role: string
  departmentId?: number; departmentName?: string; accountStatus: string
  forcePasswordChange: boolean; queueIds: number[]
}

export interface ProviderInfo { key: string; model: string; label?: string }

export const useAuth = defineStore('auth', {
  state: () => ({
    user: null as User | null,
    booting: true,
    providers: [] as ProviderInfo[],
    activeProvider: '',
  }),
  getters: {
    isAdmin: (s) => s.user?.role === 'ADMIN',
    isAgent: (s) => s.user?.role === 'ADMIN' || s.user?.role === 'AGENT',
  },
  actions: {
    async loadMe() {
      try { this.user = (await api.get('/auth/me')).data } catch { this.user = null }
      this.booting = false
    },
    async login(username: string, password: string) {
      this.user = (await api.post('/auth/login', { username, password })).data
    },
    async logout() {
      await api.post('/auth/logout').catch(() => {})
      this.user = null
    },
    async changePassword(currentPassword: string, newPassword: string) {
      await api.post('/auth/password', { currentPassword, newPassword })
      if (this.user) this.user = { ...this.user, forcePasswordChange: false }
    },
    async loadProviders(force = false) {
      if (this.providers.length && !force) return
      try {
        const r = await api.get('/agent/providers')
        this.providers = (r.data.providers || []).filter((p: ProviderInfo) => p.key)
        if (!this.activeProvider) this.activeProvider = r.data.default || this.providers[0]?.key || ''
      } catch { /* Agent 不可用时静默 */ }
    },
  },
})

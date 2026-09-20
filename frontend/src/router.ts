import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from './stores/auth'

/**
 * 路由表：原有伪路由页面按原 URL 迁移（演示脚本不受影响），
 * 新增：问答工作区、导入中心、文章详情、个人知识库。
 */
const routes = [
  { path: '/', redirect: '/workspace' },
  { path: '/login', component: () => import('./pages/AuthView.vue'), meta: { public: true } },
  { path: '/register', component: () => import('./pages/AuthView.vue'), meta: { public: true } },
  { path: '/change-password', component: () => import('./pages/AuthView.vue'), meta: { public: true } },
  { path: '/workspace', component: () => import('./pages/DashboardView.vue') },
  { path: '/workspace/tickets', component: () => import('./pages/TicketsView.vue') },
  { path: '/workspace/agent', component: () => import('./pages/AgentWorkbenchView.vue'), meta: { agent: true } },
  { path: '/workspace/ask', component: () => import('./pages/AskView.vue') },
  { path: '/workspace/knowledge', component: () => import('./pages/KnowledgeBrowseView.vue') },
  { path: '/workspace/notifications', component: () => import('./pages/NotificationsView.vue') },
  { path: '/admin', component: () => import('./pages/DashboardView.vue'), meta: { admin: true } },
  { path: '/admin/organization', component: () => import('./pages/OrganizationView.vue'), meta: { admin: true } },
  { path: '/admin/queues', component: () => import('./pages/QueuesView.vue'), meta: { admin: true } },
  { path: '/admin/knowledge', component: () => import('./pages/AdminKnowledgeView.vue'), meta: { admin: true } },
  { path: '/admin/imports', component: () => import('./pages/ImportCenterView.vue'), meta: { admin: true } },
  { path: '/admin/audit', component: () => import('./pages/AuditView.vue'), meta: { admin: true } },
  { path: '/knowledge/articles/:id', component: () => import('./pages/ArticleDetailView.vue') },
  { path: '/vault', component: () => import('./pages/VaultView.vue') },
  { path: '/vault/notes/:id', component: () => import('./pages/VaultView.vue') },
  { path: '/vault/graph', component: () => import('./pages/VaultView.vue') },
  { path: '/:pathMatch(.*)*', component: () => import('./pages/NotFoundView.vue') },
]

export const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach(async (to) => {
  const auth = useAuth()
  if (auth.booting) await auth.loadMe()
  if (to.meta.public) {
    if (auth.user && !auth.user.forcePasswordChange && to.path === '/login') return auth.user.role === 'ADMIN' ? '/admin' : '/workspace'
    return true
  }
  if (!auth.user) return { path: '/login', query: { redirect: to.fullPath } }
  if (auth.user.forcePasswordChange && to.path !== '/change-password') return { path: '/change-password', query: { redirect: to.fullPath } }
  if (to.meta.admin && auth.user.role !== 'ADMIN') return auth.user.role === 'AGENT' ? '/workspace/agent' : '/workspace'
  if (to.meta.agent && auth.user.role === 'EMPLOYEE') return '/workspace'
  return true
})

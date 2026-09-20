<script setup lang="ts">
import { notificationTarget } from "./lib/notificationTarget"
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { LogOut, LayoutDashboard, ClipboardList, Activity, BookOpen, Users, Settings2, ShieldCheck, Bot, NotebookPen, Boxes, MessagesSquare, Bell, CheckCheck } from 'lucide-vue-next'
import { useAuth } from './stores/auth'
import { api, errorMessage } from './api'
import type { NotificationItem } from './lib/types'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const isPublic = computed(() => !!route.meta.public)
const isAdmin = computed(() => auth.isAdmin)
const isAgent = computed(() => auth.isAgent)

const nav = computed(() => {
  if (isAdmin.value) return [
    { label: '总览', to: '/admin', icon: LayoutDashboard },
    { label: '处理工作台', to: '/workspace/agent', icon: Activity },
    { label: '工单', to: '/workspace/tickets', icon: ClipboardList },
    { label: '问答工作区', to: '/workspace/ask', icon: MessagesSquare },
    { label: '企业知识库', to: '/workspace/knowledge', icon: BookOpen },
    { label: '组织与用户', to: '/admin/organization', icon: Users },
    { label: '处理队列', to: '/admin/queues', icon: Settings2 },
    { label: '资料与知识', to: '/admin/knowledge', icon: BookOpen },
    { label: '导入中心', to: '/admin/imports', icon: Boxes },
    { label: '审批与审计', to: '/admin/audit', icon: ShieldCheck },
  ]
  return [
    { label: '总览', to: '/workspace', icon: LayoutDashboard },
    { label: '我的工单', to: '/workspace/tickets', icon: ClipboardList },
    ...(isAgent.value ? [{ label: '处理工作台', to: '/workspace/agent', icon: Activity }] : []),
    { label: '企业知识库', to: '/workspace/knowledge', icon: BookOpen },
    { label: '问答工作区', to: '/workspace/ask', icon: MessagesSquare },
    { label: '个人知识库', to: '/vault', icon: NotebookPen },
  ]
})

// ---- 站内通知：未读徽标 30s 轮询 + 顶栏下拉面板 ----
const unreadCount = ref(0)
const panelOpen = ref(false)
const panelItems = ref<NotificationItem[]>([])
const panelLoading = ref(false)
let pollTimer: number | undefined

async function refreshUnread() {
  if (!auth.user) return
  try { unreadCount.value = (await api.get('/notifications/unread-count')).data.count } catch { /* 轮询失败不打扰用户 */ }
}

async function openPanel() {
  panelOpen.value = !panelOpen.value
  if (!panelOpen.value) return
  panelLoading.value = true
  try {
    const res = await api.get('/notifications', { params: { page: 1, size: 10 } })
    panelItems.value = res.data.items
    unreadCount.value = res.data.unread
  } catch (e: any) { ElMessage.error(errorMessage(e, '通知加载失败')) }
  finally { panelLoading.value = false }
}

async function openItem(n: NotificationItem) {
  if (!n.read) {
    try {
      await api.post(`/notifications/${n.id}/read`)
      n.read = true
      unreadCount.value = Math.max(0, unreadCount.value - 1)
    } catch { /* 已读失败不阻塞跳转，保留未读状态等待下次同步 */ }
  }
  panelOpen.value = false
  const target = notificationTarget(n)
  if (target) router.push(target)
}

async function readAll() {
  try {
    await api.post('/notifications/read-all')
    panelItems.value = panelItems.value.map(n => ({ ...n, read: true }))
    unreadCount.value = 0
  } catch (e: any) { ElMessage.error(errorMessage(e, '操作失败')) }
}

function startPolling() {
  stopPolling()
  refreshUnread()
  pollTimer = window.setInterval(refreshUnread, 30_000)
}
function stopPolling() { if (pollTimer) { window.clearInterval(pollTimer); pollTimer = undefined } }
function onNotificationsChanged() { if (auth.user) refreshUnread() }

watch(() => auth.user?.id, (id) => { if (id && !isPublic.value) startPolling(); else { stopPolling(); unreadCount.value = 0 } })
watch(isPublic, (pub) => { if (!pub && auth.user) startPolling() })
onMounted(() => {
  window.addEventListener('notifications:changed', onNotificationsChanged)
  if (auth.user && !isPublic.value) startPolling()
})
onUnmounted(() => { window.removeEventListener('notifications:changed', onNotificationsChanged); stopPolling() })
</script>

<template>
  <div v-if="auth.booting" class="loading">正在连接 AgentDesk...</div>
  <template v-else-if="isPublic">
    <router-view />
  </template>
  <div v-else class="app-shell">
    <header class="topbar">
      <div class="brand">
        <div class="brand-mark"><Bot :size="21" /></div>
        <div><strong>AgentDesk</strong><span>企业智能服务台</span></div>
      </div>
      <div class="userbar">
        <select v-if="auth.providers.length" v-model="auth.activeProvider" class="model-select" title="选择 Agent 模型">
          <option v-for="p in auth.providers" :key="p.key" :value="p.key">{{ p.label || p.key }}</option>
        </select>
        <div class="bell-wrap">
          <button class="icon-btn" title="通知" aria-label="通知" @click="openPanel">
            <Bell :size="17" />
            <span v-if="unreadCount > 0" class="bell-badge">{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
          </button>
          <div v-if="panelOpen" class="bell-panel">
            <div class="bell-panel-head">
              <strong>通知</strong>
              <button class="link-button" style="margin:0" @click="readAll"><CheckCheck :size="13" /> 全部已读</button>
            </div>
            <div v-if="panelLoading" class="bell-empty">加载中...</div>
            <div v-else-if="panelItems.length === 0" class="bell-empty">暂无通知</div>
            <button v-for="n in panelItems" :key="n.id" :class="['bell-item', { unread: !n.read }]" @click="openItem(n)">
              <span class="bell-dot" v-if="!n.read" />
              <span class="bell-title">{{ n.title }}</span>
              <small>{{ new Date(n.createdAt).toLocaleString('zh-CN', { hour12: false }) }}</small>
            </button>
            <button class="link-button" style="margin:10px auto 4px" @click="panelOpen = false; router.push('/workspace/notifications')">查看全部</button>
          </div>
          <div v-if="panelOpen" class="bell-overlay" @click="panelOpen = false" />
        </div>
        <span>{{ auth.user?.displayName }}</span>
        <small>{{ auth.user?.departmentName }} · {{ isAdmin ? '管理员' : isAgent ? '处理人' : '员工' }}</small>
        <button class="icon-btn" title="退出登录" @click="async () => { await auth.logout(); router.push('/login') }"><LogOut :size="17" /></button>
      </div>
    </header>
    <div class="layout">
      <aside class="sidebar">
        <button v-for="item in nav" :key="item.to" :class="['nav-item', { active: route.path === item.to || (item.to !== '/workspace' && item.to !== '/admin' && route.path.startsWith(item.to)) }]" @click="router.push(item.to)">
          <component :is="item.icon" :size="18" /><span>{{ item.label }}</span>
        </button>
        <div class="sidebar-foot"><Activity :size="16" />{{ isAdmin ? '管理员后台' : '部门工作台' }}</div>
      </aside>
      <main class="main">
        <router-view />
      </main>
    </div>
  </div>
</template>

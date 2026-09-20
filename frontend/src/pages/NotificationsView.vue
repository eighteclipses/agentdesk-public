<script setup lang="ts">
import { notificationTarget } from "../lib/notificationTarget"
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import { ElMessage } from 'element-plus'
import { Bell, CheckCheck, RefreshCw, ChevronLeft, ChevronRight } from 'lucide-vue-next'
import type { NotificationItem } from '../lib/types'

const router = useRouter()
const items = ref<NotificationItem[]>([])
const total = ref(0)
const unread = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const unreadOnly = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await api.get('/notifications', { params: { page: page.value, size, unread: unreadOnly.value || undefined } })
    items.value = res.data.items
    total.value = res.data.total
    unread.value = res.data.unread
  } catch (e: any) { ElMessage.error(errorMessage(e, '通知加载失败')) }
  finally { loading.value = false }
}

/** 已读状态变化后立即同步顶栏铃铛徽标（App.vue 监听同一事件） */
function emitChanged() { window.dispatchEvent(new CustomEvent('notifications:changed')) }

async function openItem(n: NotificationItem) {
  if (!n.read) {
    try { await api.post(`/notifications/${n.id}/read`); emitChanged() } catch { /* 忽略 */ }
  }
  const target = notificationTarget(n)
  if (target) router.push(target)
  else load()
}

async function readAll() {
  try { await api.post('/notifications/read-all'); emitChanged(); await load() }
  catch (e: any) { ElMessage.error(errorMessage(e, '操作失败')) }
}

function toggleFilter() {
  unreadOnly.value = !unreadOnly.value
  page.value = 1
  load()
}

const pages = computed(() => Math.max(1, Math.ceil(total.value / size)))

function fmt(s: string) { return new Date(s).toLocaleString('zh-CN', { hour12: false }) }

onMounted(load)
</script>

<template>
  <section class="page">
    <div class="page-head">
      <div>
        <p class="eyebrow">NOTIFICATIONS</p>
        <h1>通知中心</h1>
        <p class="muted">未读 {{ unread }} 条 · 共 {{ total }} 条</p>
      </div>
      <div class="actions">
        <button :class="['primary', { 'ghost-active': unreadOnly }]" style="background: #fff; color: #344158; border: 1px solid #dce2eb" @click="toggleFilter">{{ unreadOnly ? '只看未读：开' : '只看未读：关' }}</button>
        <button class="primary" style="background: #fff; color: #344158; border: 1px solid #dce2eb" :disabled="unread === 0" @click="readAll"><CheckCheck :size="15" />全部已读</button>
        <button class="icon-btn" title="刷新" @click="load"><RefreshCw :class="{ spin: loading }" :size="17" /></button>
      </div>
    </div>
    <div class="panel table-panel">
      <div v-if="items.length === 0 && !loading" class="bell-empty" style="padding: 30px 0"><Bell :size="22" /><p>暂无通知</p></div>
      <button v-for="n in items" :key="n.id" :class="['bell-item', 'wide', { unread: !n.read }]" @click="openItem(n)">
        <span class="bell-dot" v-if="!n.read" />
        <span class="grow">
          <span class="bell-title">{{ n.title }}</span>
          <small v-if="n.body">{{ n.body }}</small>
        </span>
        <span class="bell-meta">
          <code>{{ n.type }}</code>
          <small>{{ fmt(n.createdAt) }}</small>
        </span>
      </button>
      <div v-if="pages > 1" class="pager">
        <button class="icon-btn" :disabled="page <= 1" @click="page--; load()"><ChevronLeft :size="16" /></button>
        <span>第 {{ page }} / {{ pages }} 页</span>
        <button class="icon-btn" :disabled="page >= pages" @click="page++; load()"><ChevronRight :size="16" /></button>
      </div>
    </div>
  </section>
</template>

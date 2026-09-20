<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import { useAuth } from '../stores/auth'
import { ElMessage } from 'element-plus'
import { Send, Plus } from 'lucide-vue-next'
import TicketDetailPanel from '../components/TicketDetailPanel.vue'
import TicketFilters from '../components/TicketFilters.vue'
import { useTicketList } from '../lib/useTicketList'
import { statusLabel } from '../lib/types'

const auth = useAuth()
const router = useRouter()
const { tickets, total, page, pages, filters, loading, loadError, selected, detailError, load, openTicket, closeTicket, search, gotoPage, changed } = useTicketList()
const composing = ref(false)
const busy = ref(false)
const ticketForm = ref({ title: '', description: '', category: 'GENERAL', priority: 'P3' })
onMounted(load)
async function createTicket() {
  if (busy.value) return
  if (!ticketForm.value.title.trim() || !ticketForm.value.description.trim()) return ElMessage.warning('请填写标题和描述')
  busy.value = true
  try {
    const { data } = await api.post('/tickets', ticketForm.value)
    ticketForm.value = { title: '', description: '', category: 'GENERAL', priority: 'P3' }
    composing.value = false
    filters.value = { q: '', status: '', priority: '', view: '', sort: 'created' }
    await search()
    await openTicket(data)
    ElMessage.success(`工单 #${data.id} 已创建，可在详情中补充截图和附件`)
  } catch (e) { ElMessage.error(errorMessage(e, '工单创建失败')) }
  finally { busy.value = false }
}
</script>
<template>
  <section class="page">
    <div class="page-head">
      <div><p class="eyebrow">WORKSPACE / TICKETS</p><h1>{{ auth.isAgent ? '工单' : '我的工单' }}</h1><p class="muted">跟进问题、补充信息，并在解决后确认结果。</p></div>
      <button class="primary" @click="composing = !composing"><Plus :size="16" />{{ composing ? '收起表单' : '提交新问题' }}</button>
    </div>
    <form v-if="composing" class="panel ticket-compose" @submit.prevent="createTicket">
      <div class="panel-title"><span>描述你遇到的问题</span><button type="button" class="link-button" @click="router.push('/workspace/ask')">先查知识库</button></div>
      <div class="create-form">
        <label>问题标题<input v-model="ticketForm.title" required maxlength="240" placeholder="例如：VPN 连接后无法访问内部系统" /></label>
        <label>问题分类<select v-model="ticketForm.category"><option value="GENERAL">其他 / 不确定</option><option value="NETWORK">网络</option><option value="ACCESS">账号权限</option><option value="SOFTWARE">软件</option></select></label>
        <label>影响程度<select v-model="ticketForm.priority"><option value="P1">P1 紧急 · 大范围业务中断</option><option value="P2">P2 高 · 关键工作受阻</option><option value="P3">P3 普通 · 部分功能异常</option><option value="P4">P4 低 · 咨询或使用建议</option></select></label>
        <label class="wide">问题描述<textarea v-model="ticketForm.description" required maxlength="20000" placeholder="发生了什么？从何时开始？影响哪些人？你已尝试哪些操作？"></textarea></label>
      </div>
      <p class="muted">提交后按分类进入处理队列。创建成功后可补充附件。</p>
      <button class="primary" :disabled="busy"><Send :size="16" />{{ busy ? '正在提交…' : '提交工单' }}</button>
    </form>
    <div class="panel table-panel">
      <TicketFilters v-model="filters" :agent="auth.isAgent" :loading="loading" @search="search" />
      <div class="panel-title"><span>{{ auth.isAgent ? '可访问工单' : '我的问题' }}</span><span class="muted">共 {{ total }} 条</span></div>
      <div v-if="loadError" class="notice error" role="alert">{{ loadError }} <button @click="load">重试</button></div>
      <table :aria-busy="loading">
        <thead><tr><th>工单</th><th>处理队列</th><th>优先级</th><th>状态</th><th>最近更新</th></tr></thead>
        <tbody><tr v-for="t in tickets" :key="t.id" :class="{ selected: selected?.id === t.id }">
          <td><button class="ticket-title" @click="openTicket(t)">#{{ t.id }} {{ t.title }}</button><small class="ticket-excerpt">{{ t.description }}</small></td>
          <td>{{ t.queueName || t.category }}<small>{{ t.assigneeName || '待指派' }}</small></td>
          <td><span :class="['priority', t.priority.toLowerCase()]">{{ t.priority }}</span></td>
          <td><span class="status">{{ statusLabel[t.status] || t.status }}</span></td>
          <td>{{ t.updatedAt ? new Date(t.updatedAt).toLocaleString('zh-CN', { hour12: false }) : '—' }}</td>
        </tr></tbody>
      </table>
      <div v-if="!tickets.length && !loading && !loadError" class="empty-state"><strong>没有找到工单</strong><p>可以调整筛选条件，或提交一个新问题。</p><button class="secondary" @click="composing = true">提交新问题</button></div>
      <div v-if="pages > 1" class="pager"><button class="secondary" :disabled="loading || page <= 1" @click="gotoPage(page - 1)">上一页</button><span>{{ page }} / {{ pages }}</span><button class="secondary" :disabled="loading || page >= pages" @click="gotoPage(page + 1)">下一页</button></div>
    </div>
    <div v-if="detailError" class="notice error" role="alert">{{ detailError }}</div>
    <TicketDetailPanel :ticket="selected" @close="closeTicket" @changed="changed" />
  </section>
</template>
<style scoped>
.create-form { display:grid; grid-template-columns:1fr 1fr; gap:14px; }
.create-form label { display:grid; gap:7px; font-size:13px; color:#4e5b70; }
.create-form label:first-child,.create-form .wide { grid-column:1/-1; }
.create-form textarea { min-height:120px; resize:vertical; }
.selected { background:#edf3ff; }
@media(max-width:600px) { .create-form { grid-template-columns:1fr; } }
</style>

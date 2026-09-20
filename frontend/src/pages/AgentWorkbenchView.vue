<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, errorMessage } from '../api'
import { useAuth } from '../stores/auth'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Activity, FileSearch, Sparkles, RefreshCw, LoaderCircle, UserPlus } from 'lucide-vue-next'
import TicketDetailPanel from '../components/TicketDetailPanel.vue'
import { openCitationTarget } from '../lib/citations'
import type { Ticket } from '../lib/types'
import { statusLabel } from '../lib/types'
import { useTicketList } from '../lib/useTicketList'
import TicketFilters from '../components/TicketFilters.vue'

const auth = useAuth()
const { tickets, total, page, pages, filters, loading, loadError, selected, detailError, load, openTicket, closeTicket, search, gotoPage, changed } = useTicketList('open')
const drafts = ref<Record<number, { text: string; citations: any[] }>>({})
const draft = computed(() => selected.value ? drafts.value[selected.value.id]?.text || '' : '')
const evidence = computed(() => selected.value ? drafts.value[selected.value.id]?.citations || [] : [])
const recommendations = ref<Record<number, any>>({})
const submitted = ref<Record<number, boolean>>({})
/** 行内操作进行中：{ [ticketId]: 'recommend' | 'transition' | 'retrieve' }，按钮据此禁用防重复点击 */
const busyAction = ref<Record<number, string>>({})
/** 指派候选：{ [ticketId]: 处理人列表 }，展开指派条时按需加载 */
const assignOptions = ref<Record<number, { id: number; displayName: string }[]>>({})
const assignValue = ref<Record<number, string>>({})

onMounted(async () => { await load(); void auth.loadProviders() })

function setBusy(t: Ticket, action: string) { busyAction.value = { ...busyAction.value, [t.id]: action } }

async function recommend(t: Ticket) {
  if (busyAction.value[t.id]) return
  setBusy(t, 'recommend')
  try {
    const r = await api.post('/agent/recommend', { ticketId: t.id, title: t.title, description: t.description, provider: auth.activeProvider || undefined })
    if (!r.data.ok) { ElMessage.warning(r.data.failureReason || '建议生成失败'); return }
    recommendations.value = { ...recommendations.value, [t.id]: r.data }
    ElMessage.success('建议已生成，请查看后决定是否提交审批')
  } catch (e: any) { ElMessage.error(errorMessage(e, '分流建议生成失败')) }
  finally { setBusy(t, '') }
}

async function transition(t: Ticket, status: string, reason?: string, assigneeId?: number) {
  if (busyAction.value[t.id]) return
  setBusy(t, 'transition')
  try {
    await api.post(`/tickets/${t.id}/transition`, { status, reason, assigneeId })
    ElMessage.success(`工单已更新为${statusLabel[status] || status}`)
    await changed()
  } catch (e: any) { ElMessage.error(errorMessage(e, '状态更新失败')) }
  finally { setBusy(t, '') }
}

/** 标记已解决：必须填写原因（记录到工单与审计） */
async function resolve(t: Ticket) {
  const r = await ElMessageBox.prompt('请填写解决原因（记录到工单与审计）', '标记已解决', { inputPlaceholder: '例如：已更换故障设备并验证恢复' }).catch(() => null)
  if (!r || !String(r.value).trim()) return
  await transition(t, 'RESOLVED', String(r.value).trim())
}

/** 展开/收起指派候选（按需从队列成员加载） */
async function toggleAssign(t: Ticket) {
  if (t.id in assignOptions.value) {
    const next = { ...assignOptions.value }
    delete next[t.id]
    assignOptions.value = next
    return
  }
  try {
    const r = await api.get(`/tickets/${t.id}/assignees`)
    assignOptions.value = { ...assignOptions.value, [t.id]: r.data }
    if (!r.data.length) ElMessage.warning('当前队列没有可用的激活处理人')
  } catch (e: any) { ElMessage.error(errorMessage(e, '处理人列表加载失败')) }
}

async function assign(t: Ticket) {
  const id = Number(assignValue.value[t.id])
  if (!id) return ElMessage.warning('请先选择处理人')
  assignValue.value = { ...assignValue.value, [t.id]: '' }
  await transition(t, 'ASSIGNED', undefined, id)
}

async function retrieve(t: Ticket) {
  if (busyAction.value[t.id]) return
  await openTicket(t)
  setBusy(t, 'retrieve')
  try {
    const r = await api.post('/agent/retrieve-draft', { ticketId: t.id, question: t.title + ' ' + t.description, provider: auth.activeProvider || undefined })
    drafts.value[t.id] = { text: r.data.draft || "", citations: r.data.citations || [] }
    r.data.ok ? ElMessage.success('已生成带引用回复') : ElMessage.warning(r.data.failureReason)
  } catch (e: any) { ElMessage.error(errorMessage(e, '草稿生成失败')) }
  finally { setBusy(t, '') }
}
async function submitRecommendation(t: Ticket) {
  const suggestion = recommendations.value[t.id]?.suggestedAction
  if (!suggestion || busyAction.value[t.id] || submitted.value[t.id]) return
  setBusy(t, 'submit')
  try {
    await api.post('/agent/actions', suggestion)
    submitted.value[t.id] = true
    ElMessage.success('已提交审批，可到审批与审计查看')
  } catch (e) { ElMessage.error(errorMessage(e, '提交审批失败')) }
  finally { setBusy(t, '') }
}
</script>

<template>
  <section class="page">
    <div class="page-head"><div><p class="eyebrow">WORKSPACE / AGENT</p><h1>处理工作台</h1><p class="muted">找到需要处理的问题，查看上下文，回复并跟进解决结果。</p></div><button class="secondary" :disabled="loading" @click="changed"><RefreshCw :size="16" />刷新</button></div>
    <div class="panel table-panel">
      <TicketFilters v-model="filters" agent :loading="loading" @search="search" />
      <div class="panel-title"><span>授权队列工单</span><span class="muted">共 {{ total }} 条</span></div>
      <div v-if="loadError" class="notice error" role="alert">{{ loadError }} <button @click="load">重试</button></div>
      <table :aria-busy="loading">
        <thead><tr><th>工单</th><th>队列 / 优先级</th><th>状态 / 处理人</th><th>处理操作</th></tr></thead>
        <tbody><tr v-for="t in tickets" :key="t.id" :class="{ selected: selected?.id === t.id }">
          <td><button class="ticket-title" @click="openTicket(t)">#{{ t.id }} {{ t.title }}</button><small class="ticket-excerpt">{{ t.description }}</small><small v-if="t.dueAt">截止 {{ new Date(t.dueAt).toLocaleString('zh-CN', { hour12: false }) }}</small></td>
          <td>{{ t.queueName || t.category }}<small><span :class="['priority', t.priority.toLowerCase()]">{{ t.priority }}</span></small></td>
          <td><span class="status">{{ statusLabel[t.status] || t.status }}</span><small>{{ t.assigneeName || '未指派' }}</small></td>
          <td><div class="ticket-actions">
            <button class="secondary" @click="openTicket(t)">查看 / 回复</button>
            <button v-if="['NEW','TRIAGED','ASSIGNED','PENDING_USER'].includes(t.status)" class="secondary" :disabled="!!busyAction[t.id]" @click="transition(t, 'IN_PROGRESS')">{{ t.status === 'PENDING_USER' ? '继续处理' : '开始处理' }}</button>
            <button v-if="t.status === 'NEW'" class="secondary" :disabled="!!busyAction[t.id]" @click="transition(t, 'TRIAGED')">标记已分析</button>
            <button v-if="t.status === 'TRIAGED'" class="secondary" :disabled="!!busyAction[t.id]" @click="toggleAssign(t)">指派</button>
            <button v-if="t.status === 'IN_PROGRESS'" class="secondary" :disabled="!!busyAction[t.id]" @click="transition(t, 'PENDING_USER')">等待用户</button>
            <button v-if="['IN_PROGRESS','PENDING_USER'].includes(t.status)" class="approve" :disabled="!!busyAction[t.id]" @click="resolve(t)">标记已解决</button>
            <button class="secondary" :disabled="!!busyAction[t.id]" @click="retrieve(t)">{{ busyAction[t.id] === 'retrieve' ? '生成中…' : 'AI 回复草稿' }}</button>
            <button v-if="!['RESOLVED','CLOSED'].includes(t.status)" class="secondary" :disabled="!!busyAction[t.id]" @click="recommend(t)">{{ busyAction[t.id] === 'recommend' ? '分析中…' : '分流建议' }}</button>
          </div>
          <div v-if="recommendations[t.id]" class="recommendation"><p>{{ recommendations[t.id].team }} · {{ recommendations[t.id].reason }}</p><button v-if="recommendations[t.id].suggestedAction" class="secondary" :disabled="!!busyAction[t.id] || submitted[t.id]" @click="submitRecommendation(t)">{{ submitted[t.id] ? '已提交审批' : '提交分流审批' }}</button></div>
          <div v-if="t.id in assignOptions" class="assign-bar"><select v-model="assignValue[t.id]" aria-label="选择处理人"><option value="">选择队列内处理人</option><option v-for="o in assignOptions[t.id]" :key="o.id" :value="String(o.id)">{{ o.displayName }}</option></select><button class="secondary" :disabled="!!busyAction[t.id]" @click="assign(t)">确认指派</button></div></td>
        </tr></tbody>
      </table>
      <div v-if="!tickets.length && !loading && !loadError" class="empty-state"><strong>当前没有符合条件的工单</strong><p>可切换到全部工单，或清除搜索条件。</p></div>
      <div v-if="pages > 1" class="pager"><button class="secondary" :disabled="loading || page <= 1" @click="gotoPage(page - 1)">上一页</button><span>{{ page }} / {{ pages }}</span><button class="secondary" :disabled="loading || page >= pages" @click="gotoPage(page + 1)">下一页</button></div>
    </div>
    <div v-if="detailError" class="notice error" role="alert">{{ detailError }}</div>
    <div v-if="selected && draft" class="panel draft"><div class="panel-title"><span>AI 回复草稿 · #{{ selected.id }}</span><span class="muted">审核内容后，在回复框采用并编辑</span></div><p>{{ draft }}</p><div class="evidence"><button v-for="e in evidence" :key="e.citation" class="secondary" @click="openCitationTarget(e.citation)">{{ e.title || e.citation }}</button></div></div>
    <TicketDetailPanel :ticket="selected" :draft="draft" @close="closeTicket" @changed="changed" />
  </section>
</template>
<style scoped>
.ticket-actions { display:flex; flex-wrap:wrap; gap:6px; min-width:220px; max-width:420px; }
.selected { background:#f4f8ff; }
.assign-bar { display:flex; gap:6px; margin-top:12px; flex-wrap:wrap; }
.assign-bar select { max-width:220px; }
.recommendation { max-width:420px; font-size:12px; }
</style>

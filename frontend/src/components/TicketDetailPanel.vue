<script setup lang="ts">
import { nextTick, onUnmounted, ref, watch } from 'vue'
import { api, apiBase, errorMessage } from '../api'
import { useAuth } from '../stores/auth'
import { ElMessage, ElMessageBox } from 'element-plus'
import { X, MessageSquare, Paperclip, CheckCircle2, RotateCcw } from 'lucide-vue-next'
import type { Ticket, Comment, Attachment } from '../lib/types'
import { statusLabel } from '../lib/types'

const props = defineProps<{ ticket: Ticket | null; draft?: string }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'changed'): void }>()
const auth = useAuth()
const ticket = ref<Ticket | null>(null)
const comments = ref<Comment[]>([])
const attachments = ref<Attachment[]>([])
const commentText = ref('')
const busy = ref(false)
const uploading = ref(false)
const loading = ref(false)
const loadError = ref('')
const drafts = new Map<number, string>()
const panel = ref<HTMLElement | null>(null)
const now = ref(Date.now())
const timer = window.setInterval(() => { now.value = Date.now() }, 30000)
let request = 0
onUnmounted(() => { ++request; window.clearInterval(timer) })

function slaText(t: Ticket | null): { text: string; overdue: boolean } {
  if (t && ['RESOLVED', 'CLOSED'].includes(t.status)) return { text: '已结束计时', overdue: false }
  if (!t?.dueAt) return { text: '未设置', overdue: false }
  const diff = new Date(t.dueAt).getTime() - now.value
  const h = Math.floor(Math.abs(diff) / 3600000)
  const m = Math.floor(Math.abs(diff) % 3600000 / 60000)
  return { text: `${diff <= 0 ? '已超时' : '剩余'} ${h} 小时 ${m} 分`, overdue: diff <= 0 }
}
const isRequester = () => ticket.value?.requesterId === auth.user?.id
const fmt = (s: string) => new Date(s).toLocaleString('zh-CN', { hour12: false })

async function open() {
  const id = ticket.value?.id
  if (!id) return
  const current = ++request
  loading.value = true
  loadError.value = ''
  try {
    const [c, a] = await Promise.all([api.get(`/tickets/${id}/comments`), api.get(`/tickets/${id}/attachments`)])
    if (current !== request || ticket.value?.id !== id) return
    comments.value = c.data
    attachments.value = a.data
  } catch (e) { if (current === request) loadError.value = errorMessage(e, '工单详情加载失败') }
  finally { if (current === request) loading.value = false }
}
watch(() => props.ticket, async (value) => {
  const previousId = ticket.value?.id
  if (previousId && previousId !== value?.id) drafts.set(previousId, commentText.value)
  ticket.value = value
  if (previousId !== value?.id) {
    ++request
    comments.value = []; attachments.value = []; loadError.value = ''
    commentText.value = value ? drafts.get(value.id) || '' : ''
  }
  if (value) {
    void open()
    if (previousId !== value.id) { await nextTick(); panel.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }
  }
}, { immediate: true })

async function addComment() {
  const id = ticket.value?.id
  if (!id || !commentText.value.trim() || busy.value) return
  const content = commentText.value.trim()
  busy.value = true
  try {
    await api.post(`/tickets/${id}/comments`, { content })
    drafts.delete(id)
    if (ticket.value?.id === id) { commentText.value = ''; await open() }
    emit('changed')
    ElMessage.success('回复已发送')
  } catch (e) { ElMessage.error(errorMessage(e, '回复发送失败')) }
  finally { busy.value = false }
}
async function requesterTransition(status: 'CLOSED' | 'IN_PROGRESS') {
  const id = ticket.value?.id
  if (!id || busy.value) return
  busy.value = true
  try {
    const result = await ElMessageBox.prompt(status === 'CLOSED' ? '请填写验证结果，确认问题已解决。' : '请描述仍然存在的问题。', status === 'CLOSED' ? '确认关闭' : '重新打开', {
      inputValidator: value => !!value?.trim() || '请填写说明',
    }).catch(() => null)
    if (!result) return
    const { data } = await api.post(`/tickets/${id}/transition`, { status, reason: result.value.trim() })
    if (ticket.value?.id === id) { ticket.value = data; await open() }
    emit('changed')
    ElMessage.success(status === 'CLOSED' ? '工单已关闭' : '工单已重新打开')
  } catch (e) { ElMessage.error(errorMessage(e, '操作失败')) }
  finally { busy.value = false }
}
async function uploadAttachment(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  const id = ticket.value?.id
  if (!id || !file || uploading.value) return
  if (!file.size || file.size > 50 * 1024 * 1024) { ElMessage.warning('请选择非空且不超过 50MB 的附件'); input.value = ''; return }
  uploading.value = true
  const data = new FormData(); data.append('file', file)
  try {
    await api.post(`/tickets/${id}/attachments`, data)
    if (ticket.value?.id === id) await open()
    ElMessage.success('附件已上传')
  } catch (e) { ElMessage.error(errorMessage(e, '附件上传失败')) }
  finally { uploading.value = false; input.value = '' }
}
function insertReply(text: string) {
  commentText.value = [commentText.value, text].filter(Boolean).join('\n\n')
}
</script>

<template>
  <div v-if="ticket" class="panel ticket-detail" ref="panel">
    <div class="panel-title">
      <span>工单详情 · #{{ ticket.id }} {{ ticket.title }}</span>
      <button class="icon-btn" title="关闭详情" @click="emit('close')"><X :size="16" /></button>
    </div>
    <p class="muted">{{ ticket.description }}</p>
    <div class="meta-grid">
      <div><small>请求人</small>{{ ticket.requesterName || '—' }}</div>
      <div><small>处理人</small>{{ ticket.assigneeName || '待指派' }}</div>
      <div><small>队列</small>{{ ticket.queueName || ticket.category }}</div>
      <div><small>优先级</small><span :class="['priority', ticket.priority.toLowerCase()]">{{ ticket.priority }}</span></div>
      <div><small>状态</small><span class="status">{{ statusLabel[ticket.status] || ticket.status }}</span></div>
      <div><small>SLA 截止</small><span :class="{ 'sla-overdue': slaText(ticket).overdue }">{{ slaText(ticket).text }}</span></div>
    </div>
    <div v-if="isRequester() && ticket.status === 'RESOLVED'" class="requester-actions">
      <button class="approve" :disabled="busy" @click="requesterTransition('CLOSED')"><CheckCircle2 :size="14" />确认解决并关闭</button>
      <button class="reject" :disabled="busy" @click="requesterTransition('IN_PROGRESS')"><RotateCcw :size="14" />问题未解决，重新打开</button>
    </div>
    <div v-if="loading" class="muted" role="status">正在加载回复与附件…</div>
    <div v-if="loadError" class="notice error" role="alert">{{ loadError }} <button @click="open">重试</button></div>
    <div class="detail-grid">
      <div>
        <strong>评论时间线</strong>
        <div v-if="!comments.length && !loadError && !loading" class="muted">暂无评论</div>
        <div v-for="c in comments" :key="c.id" class="comment">
          <small>{{ c.authorName || `用户 #${c.authorId}` }} · {{ fmt(c.createdAt) }}</small>
          <p>{{ c.content }}</p>
        </div>
        <div v-if="auth.isAgent" class="reply-presets">
          <button v-if="draft" class="approve" :disabled="busy" @click="insertReply(draft)">采用 AI 草稿并编辑</button>
          <button class="secondary" :disabled="busy" @click="insertReply('您好，已收到您的问题。请补充故障发生时间、报错截图和影响范围，以便进一步排查。')">请求补充信息</button>
          <button class="secondary" :disabled="busy" @click="insertReply('您好，问题已处理，请按原操作步骤重试。如仍有异常，请在此工单中回复。')">邀请验证结果</button>
        </div>
        <form class="reply-form" @submit.prevent="addComment">
          <textarea v-model="commentText" aria-label="工单回复" maxlength="20000" placeholder="补充信息或回复处理结果。Ctrl + Enter 发送" :disabled="busy" @keydown.ctrl.enter.prevent="!$event.isComposing && addComment()"></textarea>
          <button class="primary" :disabled="busy || !commentText.trim()"><MessageSquare :size="15" />{{ busy ? '提交中…' : '发送回复' }}</button>
        </form>
      </div>
      <div>
        <strong>附件</strong>
        <div v-if="!attachments.length && !loadError && !loading" class="muted">暂无附件</div>
        <div v-for="a in attachments" :key="a.id" class="attachment">
          <Paperclip :size="14" />
          <a :href="`${apiBase}/tickets/${ticket.id}/attachments/${a.id}/download`" :download="a.fileName">{{ a.fileName }}</a>
          <small>{{ (a.size / 1024).toFixed(1) }} KB</small>
        </div>
        <label class="small upload-inline" title="上传附件"><Paperclip :size="15" />{{ uploading ? '上传中…' : '上传附件' }}<input type="file" :disabled="uploading" @change="uploadAttachment" /></label>
      </div>
    </div>
  </div>
</template>

<style scoped>
.meta-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px 16px; background: #f7f9fc; border-radius: 8px; padding: 12px 14px; margin: 12px 0; font-size: 12px; color: #344158; }
.meta-grid small { display: block; color: #8c98aa; font-size: 10px; margin-bottom: 3px; }
.sla-overdue { color: #b42318; font-weight: 700; }
.requester-actions { display: flex; gap: 8px; margin-bottom: 12px; }
.ticket-detail { margin-top:18px; scroll-margin-top:90px; }
.ticket-detail > p, .comment p { white-space:pre-wrap; overflow-wrap:anywhere; }
.detail-grid { display:grid; grid-template-columns:minmax(0,2fr) minmax(180px,1fr); gap:24px; }
.comment { border-bottom:1px solid #edf0f5; padding:12px 0; }
.comment small { color:#68758a; }
.reply-form { display:grid; gap:10px; margin-top:12px; }
.reply-form textarea { min-height:100px; resize:vertical; width:100%; }
.reply-form button { justify-self:start; }
.reply-presets { display:flex; flex-wrap:wrap; gap:8px; margin-top:14px; }
.attachment { display:flex; gap:8px; flex-wrap:wrap; margin:12px 0; overflow-wrap:anywhere; }
.upload-inline { width:auto; display:inline-flex; gap:6px; position:relative; }
.upload-inline input { position:absolute; inset:0; opacity:0; width:100%; cursor:pointer; }
@media(max-width:700px) { .detail-grid { grid-template-columns:1fr; } .meta-grid { grid-template-columns:repeat(2,1fr); } }
</style>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import { useAuth } from '../stores/auth'
import { askStream } from '../lib/sse'
import { renderMarkdown } from '../lib/markdown'
import { citationParts, openCitationTarget } from '../lib/citations'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Send, Square, MessageSquare, ThumbsUp, ThumbsDown, NotebookPen, Ticket, Plus, Bot, RotateCw, Trash2 } from 'lucide-vue-next'

const router = useRouter()
const openNote = (id: number) => router.push(`/vault/notes/${id}`)

interface Citation { id?: number; citation: string; title?: string; snippet?: string; headingPath?: string; page?: number; score?: number }
interface Msg { id?: number; role: 'user' | 'assistant'; content: string; ok?: boolean; errorReason?: string; source?: string; confidence?: number; citations?: Citation[] }

const auth = useAuth()
const conversations = ref<any[]>([])
const conversationId = ref<number | null>(null)
const messages = ref<Msg[]>([])
const question = ref('')
const scope = ref<'ENTERPRISE' | 'PERSONAL' | 'ALL'>('ENTERPRISE')
const streaming = ref(false)
const stages = ref<{ step: string; elapsedMs: number }[]>([])
const activeCitations = ref<Citation[]>([])
const abort = ref<AbortController | null>(null)
const bodyRef = ref<HTMLElement | null>(null)
const savedNotes = ref<any[]>([])
/** 流式回答期间的预输入：当前回答结束后自动发送 */
const queuedQuestion = ref('')
const toolBusy = ref(false)
let generation = 0
let navigation = 0

const scopeLabel: Record<string, string> = { ENTERPRISE: '企业知识', PERSONAL: '仅个人笔记', ALL: '个人+企业' }
const stageLabel: Record<string, string> = { retrieving: '检索授权资料', answering: '生成回答中', done: '完成' }

async function loadConversations() {
  conversations.value = (await api.get('/knowledge/conversations')).data
}
async function loadSaved() {
  savedNotes.value = (await api.get('/vault/notes')).data
}

async function openConversation(id: number) {
  stop()
  const visit = ++navigation
  activeCitations.value = []
  stages.value = []
  conversationId.value = id
  messages.value = []
  try {
    const detail = (await api.get(`/knowledge/conversations/${id}`)).data
    if (visit !== navigation) return
    for (const m of detail.messages || []) {
      messages.value.push({ id: m.id, role: String(m.role).toLowerCase() as Msg['role'], content: m.content || '', ok: m.ok, errorReason: m.ok === false ? m.source : undefined, source: m.source, confidence: m.confidence, citations: m.citations || [] })
    }
    const savedScope = detail.messages?.at(-1)?.scope
    if (['ENTERPRISE','PERSONAL','ALL'].includes(savedScope)) scope.value = savedScope
    activeCitations.value = [...messages.value].reverse().find(m => m.role === 'assistant')?.citations || []
    await scrollToBottom()
  } catch (e: any) { if (visit === navigation) ElMessage.error(errorMessage(e, '会话加载失败')) }
}

async function newConversation() {
  stop()
  ++navigation
  activeCitations.value = []
  stages.value = []
  conversationId.value = null
  messages.value = []
  question.value = ''
  await scrollToBottom()
}

/** 删除历史会话（含消息与引用），流式中不允许删除当前会话 */
async function deleteConversation(id: number, e: Event) {
  e.stopPropagation()
  if (streaming.value && id === conversationId.value) return ElMessage.warning('回答生成中，请先停止再删除')
  const confirmed = await ElMessageBox.confirm('删除该会话及其全部问答记录？此操作不可恢复。', '删除会话', { type: 'warning' }).catch(() => null)
  if (!confirmed) return
  try {
    await api.delete(`/knowledge/conversations/${id}`)
    if (conversationId.value === id) await newConversation()
    await loadConversations()
    ElMessage.success('会话已删除')
  } catch (err: any) { ElMessage.error(errorMessage(err, '删除失败')) }
}

function stop() {
  queuedQuestion.value = ''
  ++generation
  abort.value?.abort(); abort.value = null
  if (streaming.value) {
    const last = messages.value.at(-1)
    if (last?.role === 'assistant') { last.ok = false; last.errorReason = '已停止'; last.content ||= '已停止生成，可以继续提问。' }
  }
  streaming.value = false
}

/** 重新生成：以上一条用户问题重新提问（不重复展示问题气泡） */
async function regenerate() {
  if (streaming.value) return
  const lastUser = [...messages.value].reverse().find((m) => m.role === 'user')
  if (!lastUser) return
  await ask(lastUser.content)
}

async function scrollToBottom() {
  await nextTick()
  bodyRef.value?.scrollTo({ top: bodyRef.value.scrollHeight })
}

/** 当前回答结束后发送排队中的问题 */
function flushQueue() {
  const q = queuedQuestion.value
  if (!q) return
  queuedQuestion.value = ''
  void ask(q)
}

async function ask(overrideQuestion?: string) {
  const q = (overrideQuestion ?? question.value).trim()
  if (!q) return
  if (streaming.value) {
    // 流式期间允许预输入下一个问题：排队，当前回答结束自动发送
    queuedQuestion.value = q
    if (overrideQuestion === undefined) question.value = ''
    ElMessage.info('问题已排队，当前回答结束后自动发送')
    return
  }
  ++navigation
  const run = ++generation
  const controller = new AbortController()
  streaming.value = true
  abort.value = controller
  stages.value = []
  activeCitations.value = []
  messages.value.push({ role: 'user', content: q })
  const answerMsg = reactive<Msg>({ role: 'assistant', content: '', ok: true })
  messages.value.push(answerMsg)
  if (overrideQuestion === undefined) question.value = ''
  const prevConversation = conversationId.value
  await scrollToBottom()

  try { await askStream({
    question: q,
    scope: scope.value,
    provider: auth.activeProvider || undefined,
    conversationId: prevConversation ?? undefined,
    signal: controller.signal,
    onStage: (step, elapsedMs) => { if (run !== generation) return; stages.value.push({ step, elapsedMs }); scrollToBottom() },
    onCitations: (hits) => { if (run !== generation) return; answerMsg.citations = hits; activeCitations.value = hits; scrollToBottom() },
    onToken: (delta) => { if (run !== generation) return; answerMsg.content += delta; scrollToBottom() },
    onDone: (info) => {
      if (run !== generation) return
      if (info.conversationId) conversationId.value = info.conversationId
      answerMsg.id = info.messageId
      answerMsg.source = info.source
      answerMsg.confidence = info.confidence
      if (info.citations) { answerMsg.citations = info.citations; activeCitations.value = info.citations }
      loadConversations().catch(() => {})
    },
    onError: (reason, messageId, savedConversationId) => {
      if (run !== generation) return
      if (savedConversationId) conversationId.value = savedConversationId
      loadConversations().catch(() => {})
      queuedQuestion.value = ''
      answerMsg.ok = false
      answerMsg.errorReason = reason
      if (messageId) answerMsg.id = messageId
      if (reason === 'NO_EVIDENCE') answerMsg.content = answerMsg.content || '当前知识库没有足够证据，无法可靠回答。可以点击"转人工"带上问题上下文创建工单。'
      scrollToBottom()
      answerMsg.content ||= '回答未完成，请重试或转人工处理。'
    },
  }) } finally {
    if (run === generation) { streaming.value = false; abort.value = null; flushQueue() }
  }
}

// ---- 引用 ----
function openCitation(c: Citation) {
  if (!openCitationTarget(c.citation)) return
  if (c.id) api.post(`/knowledge/citations/${c.id}/click`).catch(() => {})
}

// ---- 反馈 / 转人工 / 存笔记 ----
async function feedback(rating: number) {
  if (toolBusy.value) return
  const last = [...messages.value].reverse().find((m) => m.role === 'assistant' && m.id)
  if (!last?.id) return ElMessage.warning('等待回答完成后评价')
  toolBusy.value = true
  try {
    await api.post('/knowledge/feedback', { messageId: last.id, rating, reasons: rating > 0 ? ['有帮助'] : ['不准确'] })
    ElMessage.success(rating > 0 ? '已记录好评' : '已记录差评，感谢反馈')
  } catch (e: any) { ElMessage.error(errorMessage(e, '反馈提交失败')) }
  finally { toolBusy.value = false }
}

async function escalate() {
  if (toolBusy.value) return
  const lastUser = [...messages.value].reverse().find((m) => m.role === 'user')
  const lastAnswer = [...messages.value].reverse().find((m) => m.role === 'assistant')
  if (!lastUser) return
  const citationsText = (activeCitations.value.length ? activeCitations.value : lastAnswer?.citations || [])
    .filter(c => c.citation.startsWith('article:')).slice(0, 5).map((c) => `${c.citation} ${c.title}：${c.snippet}`).join('\n')
  toolBusy.value = true
  try {
    const confirm = await ElMessageBox.confirm('将把当前问题及企业资料引用发送给处理人员。个人笔记引用不会附带，请确认问题本身适合共享。', '转人工处理', { confirmButtonText: '创建工单', cancelButtonText: '取消' }).catch(() => null)
    if (!confirm) return
    const result = await api.post('/tickets', {
      title: lastUser.content.slice(0, 100),
      description: `【知识问答转人工】\n问题：${lastUser.content}\n\nAI 未能解决（${lastAnswer?.errorReason || (lastAnswer?.ok === false ? '证据不足' : '需要人工介入')}）。\n引用上下文：\n${citationsText || '（无）'}`,
      category: 'GENERAL',
    })
    ElMessage.success('工单已创建，已带上问题与引用上下文')
    await router.push({ path: '/workspace/tickets', query: { ticket: result.data.id } })
  } catch (e: any) { ElMessage.error(errorMessage(e, '转人工建工单失败')) }
  finally { toolBusy.value = false }
}

async function saveAsNote() {
  if (toolBusy.value) return
  const lastUser = [...messages.value].reverse().find((m) => m.role === 'user')
  const lastAnswer = [...messages.value].reverse().find((m) => m.role === 'assistant')
  if (!lastUser || !lastAnswer?.content) return
  const cites = (activeCitations.value.length ? activeCitations.value : lastAnswer.citations || [])
  const citeBlock = cites.map((c) => `- ${c.citation} · ${c.title}`).join('\n') || '- 无'
  const content = `---
type: qa-saved
created: ${new Date().toISOString().slice(0, 10)}
source: 问答工作区
---

# ${lastUser.content}

${lastAnswer.content}

## 引用来源

${citeBlock}
`
  toolBusy.value = true
  try {
    await api.post('/vault/notes', { title: lastUser.content.slice(0, 60), content, tags: ['问答存档'], folderPath: '' })
    ElMessage.success('已保存到个人知识库')
    loadSaved().catch(() => {})
  } catch (e: any) { ElMessage.error(errorMessage(e, '保存笔记失败')) }
  finally { toolBusy.value = false }
}

// ---- 初始 ----
onMounted(async () => {
  try { await auth.loadProviders() } catch { /* 供应商列表失败不阻塞问答 */ }
  await Promise.all([loadConversations(), loadSaved()].map((p) => p.catch(() => {})))
})
onUnmounted(() => { ++navigation; stop() })

const showStages = computed(() => stages.value.length > 0)
const doneSummary = computed(() => {
  const last = [...messages.value].reverse().find((m) => m.role === 'assistant')
  return last
})
/** 工具条可见：有回答内容（含拒答兜底文案）且不在流式中；点赞仅在有服务端消息 id 时可用 */
const showTools = computed(() => !streaming.value && !!doneSummary.value && doneSummary.value.content.length > 0)
</script>

<template>
  <section class="page ask-page">
    <div class="page-head">
      <div>
        <p class="eyebrow">WORKSPACE / ASK</p>
        <h1>问答工作区</h1>
        <p class="muted">只基于你有权限访问的资料回答；每个事实性回答都保留文件+版本+分块引用，可点击回跳原文。</p>
      </div>
      <div class="ask-actions">
        <button class="primary" @click="newConversation"><Plus :size="15" />新对话</button>
      </div>
    </div>
    <div class="ask-layout">
      <aside class="ask-side">
        <div v-if="conversations.length" class="side-title">历史问答</div>
        <div v-for="c in conversations" :key="c.id" class="conv-item" :class="{ current: c.id === conversationId }" @click="openConversation(c.id)">
          <MessageSquare :size="13" /><span class="conv-title">{{ c.title || `会话 #${c.id}` }}</span>
          <button class="conv-del" title="删除会话" @click="deleteConversation(c.id, $event)"><Trash2 :size="12" /></button>
        </div>
        <div v-if="!conversations.length" class="muted side-hint">暂无历史会话</div>
      </aside>
      <main class="ask-main" ref="bodyRef">
        <div v-if="!messages.length" class="ask-empty">
          <Bot :size="34" />
          <p>问任何关于企业知识库的问题，例如：<br />"VPN 认证超时如何处理？"</p>
        </div>
        <div v-for="(m, i) in messages" :key="i" :class="['msg-row', m.role]">
          <div class="msg-avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
          <div class="msg-body">
            <div v-if="m.role === 'assistant' && m.content" class="msg-content" v-html="renderMarkdown(m.content)" />
            <div v-if="m.role === 'assistant' && !m.content" class="typing"><span></span><span></span><span></span></div>
            <div v-if="m.role === 'user'">{{ m.content }}</div>
            <div v-if="m.citations?.length && !(i === messages.length - 1 && streaming)" class="msg-citations">
              <button v-for="c in m.citations" :key="c.citation + (c.id ?? '')" class="citation-chip" @click="openCitation(c)">
                {{ (citationParts(c.citation)?.type === 'note' ? '笔记 ' : '文章 ') + (c.title || c.citation) }}<template v-if="citationParts(c.citation)?.chunk != null"> · C{{ citationParts(c.citation)!.chunk }}</template>
              </button>
            </div>
          </div>
        </div>
        <div v-if="streaming && messages.length && messages[messages.length-1].role==='assistant'" class="stream-panel">
          <div v-if="showStages" class="stage-timeline">
            <span v-for="(s, i) in stages" :key="i" class="stage-chip">{{ stageLabel[s.step] || s.step }} {{ s.elapsedMs }}ms</span>
          </div>
          <div v-if="activeCitations.length" class="live-citations">
            <strong>引用 {{ activeCitations.length }} 条</strong>
            <button v-for="c in activeCitations" :key="c.citation" class="citation-chip" @click="openCitation(c)">{{ c.title }}</button>
          </div>
        </div>
        <div v-if="queuedQuestion" class="queued-hint">排队中：{{ queuedQuestion }}（回答结束后自动发送）</div>
      </main>
      <aside class="ask-panel">
        <div class="panel-title"><span>本次回答</span></div>
        <div v-if="!doneSummary || (!doneSummary.content && streaming)" class="muted">等待回答…</div>
        <template v-else>
          <div v-if="doneSummary.confidence != null" class="meta-line">置信度 {{ Math.round(doneSummary.confidence * 100) }}% · {{ scopeLabel[scope] }} · {{ doneSummary.source === 'RULE_FALLBACK' ? '规则兜底' : doneSummary.source }}</div>
          <div v-if="activeCitations.length" class="ref-cards">
            <div v-for="c in activeCitations" :key="c.citation" class="ref-card" @click="openCitation(c)">
              <strong>{{ c.title }}</strong>
              <small v-if="c.headingPath">{{ c.headingPath }}</small>
              <small v-if="c.page">第 {{ c.page }} 页</small>
              <p>{{ (c.snippet || '').slice(0, 140) }}</p>
            </div>
          </div>
          <div v-if="doneSummary.errorReason" class="notice error">{{ doneSummary.errorReason === 'NO_EVIDENCE' ? '证据不足：知识库中没有可靠答案' : '生成中断：' + doneSummary.errorReason }}</div>
          <div v-if="showTools" class="answer-tools">
            <button class="small tool" title="有帮助" :disabled="toolBusy || !doneSummary.id" @click="feedback(1)"><ThumbsUp :size="14" /></button>
            <button class="small tool" title="不准确" :disabled="toolBusy || !doneSummary.id" @click="feedback(-1)"><ThumbsDown :size="14" /></button>
            <button class="small tool" title="重新生成" :disabled="toolBusy" @click="regenerate"><RotateCw :size="14" /></button>
            <button class="small tool" title="保存为个人笔记" :disabled="toolBusy" @click="saveAsNote"><NotebookPen :size="14" /></button>
            <button class="small tool" title="转人工建工单" :disabled="toolBusy" @click="escalate"><Ticket :size="14" /></button>
          </div>
        </template>
        <div class="panel-title vault-title"><span>我的笔记</span><button class="link-button" @click="router.push('/vault')">管理</button></div>
        <div v-for="n in savedNotes.slice(0, 6)" :key="n.id" class="note-item" @click="openNote(n.id)">{{ n.title }}</div>
      </aside>
    </div>
    <div class="ask-inputbar">
      <select v-model="scope" :disabled="streaming" class="model-select" title="检索范围">
        <option value="ENTERPRISE">企业知识</option>
        <option value="PERSONAL">仅个人笔记</option>
        <option value="ALL">个人+企业</option>
      </select>
      <input v-model="question" placeholder="输入问题，回车发送；回答过程中可预输入下一个问题" @keydown.enter="!$event.isComposing && ask()" />
      <button v-if="!streaming" class="primary" @click="ask()"><Send :size="15" />提问</button>
      <button v-else class="primary stop" @click="stop"><Square :size="14" />停止</button>
    </div>
  </section>
</template>

<style scoped>
.ask-page { display: flex; flex-direction: column; min-height: calc(100vh - 170px); }
.ask-layout { display: grid; grid-template-columns: 210px 1fr 300px; gap: 14px; flex: 1; min-height: 0; }
.ask-side, .ask-panel { background: #fff; border: 1px solid #e7ebf2; border-radius: 9px; padding: 13px; overflow: auto; }
.ask-main { background: #fff; border: 1px solid #e7ebf2; border-radius: 9px; padding: 18px; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; min-height: 380px; max-height: 62vh; }
.side-title { font-size: 11px; font-weight: 700; color: #64748b; padding: 6px 9px; }
.conv-item { display: flex; align-items: center; gap: 7px; padding: 8px 9px; border-radius: 7px; font-size: 12px; color: #4e5b70; cursor: pointer; margin-bottom: 3px; }
.conv-item:hover { background: #f2f6fc; }
.conv-item.current { background: #edf3ff; color: #235dcc; font-weight: 600; }
.conv-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conv-del { border: 0; background: none; color: #c0c8d4; cursor: pointer; padding: 2px; border-radius: 4px; display: none; flex: none; }
.conv-item:hover .conv-del { display: inline-flex; }
.conv-del:hover { color: #e11d48; background: #fff0ef; }
.side-hint { padding: 10px; font-size: 12px; }
.ask-empty { margin: auto; text-align: center; color: #a2acbb; }
.queued-hint { font-size: 11px; color: #a15d05; background: #fff7e8; border: 1px solid #ffe1b0; border-radius: 6px; padding: 6px 10px; align-self: flex-start; }
.msg-row { display: flex; gap: 10px; }
.msg-avatar { width: 28px; height: 28px; border-radius: 50%; display: grid; place-items: center; font-size: 11px; color: #fff; flex: none; margin-top: 2px; }
.msg-row.user .msg-avatar { background: #64748b; }
.msg-row.assistant .msg-avatar { background: #2563eb; }
.msg-body { flex: 1; max-width: 85%; }
.msg-row.user .msg-body { color: #334155; background: #f1f5f9; border-radius: 10px; padding: 9px 12px; font-size: 13px; line-height: 1.7; }
.msg-content { font-size: 13px; line-height: 1.8; color: #2b3850; }
.msg-content :deep(p) { margin: 0 0 10px; }
.msg-content :deep(h1), .msg-content :deep(h2), .msg-content :deep(h3) { font-size: 15px; margin: 12px 0 6px; }
.msg-content :deep(li) { margin: 3px 0; }
.typing span { display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: #94a3b8; margin-right: 4px; animation: blink 1.2s infinite; }
.typing span:nth-child(2) { animation-delay: .2s; } .typing span:nth-child(3) { animation-delay: .4s; }
@keyframes blink { 0%, 80%, 100% { opacity: .25; } 40% { opacity: 1; } }
.msg-citations, .live-citations { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.citation-chip { border: 1px solid #dbe4f5; background: #f4f8ff; color: #356bd3; font-size: 11px; border-radius: 20px; padding: 4px 10px; cursor: pointer; }
.citation-chip:hover { background: #e6efff; }
.stream-panel { border-left: 3px solid #4f7bdc; background: #f8faff; border-radius: 8px; padding: 9px 12px; }
.stage-timeline { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
.stage-chip { font-size: 10px; color: #2e68d7; background: #eaf2ff; padding: 3px 8px; border-radius: 20px; }
.stream-panel .live-citations strong { font-size: 11px; color: #64748b; display: block; margin-bottom: 5px; }
.ask-panel .meta-line { font-size: 11px; color: #8994a7; margin-bottom: 10px; }
.ref-cards { display: grid; gap: 8px; }
.ref-card { border: 1px solid #e7ebf2; border-radius: 8px; padding: 9px 10px; cursor: pointer; }
.ref-card:hover { border-color: #9cc0f5; background: #f7faff; }
.ref-card strong { font-size: 12px; color: #3d65a2; display: block; }
.ref-card small { font-size: 10px; color: #8c98aa; display: block; }
.ref-card p { font-size: 11px; color: #7e899a; margin: 6px 0 0; line-height: 1.6; }
.answer-tools { display: flex; gap: 6px; margin-top: 12px; }
.tool { width: auto; padding: 0 8px; height: 28px; display: inline-flex; align-items: center; gap: 5px; border: 1px solid #e2e8f0; border-radius: 6px; font-size: 11px; color: #64748b; }
.tool:hover { background: #f1f5f9; }
.ask-inputbar { display: flex; gap: 9px; margin-top: 14px; align-items: center; }
.ask-inputbar input { flex: 1; }
.ask-inputbar .stop { background: #e11d48; }
.vault-title { margin-top: 18px; }
.note-item { font-size: 12px; color: #356bd3; cursor: pointer; padding: 7px 8px; border-radius: 6px; }
.note-item:hover { background: #f2f6fc; }
@media (max-width: 1000px) { .ask-layout { grid-template-columns: 1fr; } .ask-side, .ask-panel { max-height: 220px; } }
@media(max-width:600px){.ask-layout{display:contents}.ask-side{order:1;max-height:96px;margin-bottom:12px}.ask-main{order:2;min-height:230px;max-height:42vh}.ask-panel{order:4;max-height:none;margin-top:14px}.ask-inputbar{order:3}.ask-main,.ask-side,.ask-panel{min-width:0}.ask-inputbar{flex-wrap:wrap}.ask-inputbar input{order:-1;flex:1 1 100%;min-width:0}.msg-body{min-width:0}.msg-content{overflow-wrap:anywhere}.msg-content :deep(pre){overflow:auto}}
</style>

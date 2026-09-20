<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, apiBase, errorMessage } from '../api'
import { ElMessage } from 'element-plus'
import { UploadCloud, RefreshCw, CheckCircle2, RotateCcw, Ban, Rocket, FolderOpen, Download } from 'lucide-vue-next'
import { itemStatusLabel } from '../lib/types'

const router = useRouter()
interface Item { id: number; batchId: number; fileName: string; sourcePath?: string | null; contentType?: string; size?: number; parser?: string; status: string; progress: number; error?: string; summary?: string; articleId?: number | null; duplicateOf?: number | null }
interface Batch { id: number; name: string; status: string; totalItems: number; createdAt: string; queued: number; active: number; review: number; published: number; failed: number; duplicate: number }

const MAX_FILES = 100
const MAX_FILE_MB = 50
const ALLOWED_EXT = ['.pdf', '.docx', '.txt', '.md', '.markdown']

const batches = ref<Batch[]>([])
const selected = ref<any | null>(null)
const items = ref<Item[]>([])
const pendingFiles = ref<{ file: File; path: string; rejected?: string }[]>([])
const uploading = ref(false)
const uploadProgress = ref(0)
const batchName = ref('')
const departments = ref<{ id: number; name: string }[]>([])
const departmentId = ref<number | null>(null)
const duplicateStrategy = ref<'skip' | 'version'>('skip')
const es = ref<EventSource | null>(null)
const pollTimer = ref<ReturnType<typeof setInterval> | null>(null)
const eventsLog = ref<{ itemId: number; stage: string; message: string; progress: number; at: string }[]>([])

function extOf(name: string) { const i = name.lastIndexOf('.'); return i >= 0 ? name.slice(i).toLowerCase() : '' }

/** 客户端预校验：与后端规则一致，避免整批传到一半才被拒绝 */
function addFiles(list: FileList | File[]) {
  for (const f of Array.from(list)) {
    const path = (f as any).webkitRelativePath || f.name
    let rejected: string | undefined
    if (!ALLOWED_EXT.includes(extOf(f.name))) rejected = '格式不支持（仅 PDF/DOCX/TXT/MD）'
    else if (f.size > MAX_FILE_MB * 1024 * 1024) rejected = `超过 ${MAX_FILE_MB}MB 上限`
    else if (pendingFiles.value.length >= MAX_FILES) rejected = `单批次最多 ${MAX_FILES} 个文件`
    pendingFiles.value.push({ file: f, path, rejected })
  }
}
function pickFiles(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files?.length) addFiles(input.files)
  input.value = ''
}
function onDrop(e: DragEvent) {
  if (e.dataTransfer?.files?.length) addFiles(e.dataTransfer.files)
}
function fmtSize(n?: number) {
  if (n == null) return ''
  return n > 1024 * 1024 ? (n / 1024 / 1024).toFixed(1) + ' MB' : (n / 1024).toFixed(0) + ' KB'
}

async function upload() {
  const rejectedCount = pendingFiles.value.filter((p) => p.rejected).length
  if (!pendingFiles.value.length) return ElMessage.warning('请先选择文件')
  if (rejectedCount) { ElMessage.error(`有 ${rejectedCount} 个文件不满足要求，请移除后重试`); return }
  uploading.value = true
  uploadProgress.value = 0
  const data = new FormData()
  if (batchName.value) data.append('name', batchName.value)
  if (departmentId.value) data.append('departmentId', String(departmentId.value))
  data.append('duplicateStrategy', duplicateStrategy.value)
  // 保留文件夹相对路径，服务端随批次写入 import_items.source_path
  for (const p of pendingFiles.value) { data.append('files', p.file); data.append('sourcePaths', p.path) }
  try {
    const r = await api.post('/admin/imports', data, {
      timeout: 0,
      onUploadProgress: (ev) => { if (ev.total) uploadProgress.value = Math.round((ev.loaded / ev.total) * 100) },
    })
    ElMessage.success(`批次 #${r.data.batchId} 已创建（${r.data.totalItems} 个文件），正在异步处理`)
    pendingFiles.value = []
    batchName.value = ''
    await loadBatches()
    await openBatch(r.data.batchId)
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '上传失败'))
  } finally { uploading.value = false; uploadProgress.value = 0 }
}

async function loadBatches() { try { batches.value = (await api.get('/admin/imports')).data } catch (e: any) { ElMessage.error(errorMessage(e, '批次列表加载失败')) } }

async function openBatch(id: number) {
  disconnectSse()
  stopPolling()
  selected.value = { id }
  items.value = []
  eventsLog.value = []
  await refreshSelected()
  connectSse(id)
  startPolling()
}

async function refreshSelected() {
  if (!selected.value) return
  selected.value = (await api.get(`/admin/imports/${selected.value.id}`)).data
  items.value = selected.value.items || []
}

function connectSse(id: number) {
  es.value = new EventSource(`${apiBase}/admin/imports/${id}/events`)
  es.value.addEventListener('init', (e: MessageEvent) => {
    const init = JSON.parse(e.data)
    if (init.items) { selected.value = { ...(selected.value || {}), items: init.items }; items.value = init.items }
    if (init.events) eventsLog.value = init.events
  })
  es.value.addEventListener('progress', (e: MessageEvent) => {
    const p = JSON.parse(e.data)
    eventsLog.value.push(p)
    const item = items.value.find((x) => x.id === p.itemId)
    if (item) {
      item.progress = p.progress
      // stage 与 import_items.status 同名（QUEUED/PARSING/NORMALIZING/CHUNKING/INDEXING/REVIEW/PUBLISHED/FAILED/DUPLICATE/CANCELED）
      if (p.stage && p.stage !== item.status) item.status = p.stage
      if (p.stage === 'FAILED') item.error = p.message
    }
  })
  es.value.onerror = () => { /* EventSource 自动重连 */ }
}

function disconnectSse() { es.value?.close(); es.value = null }
function startPolling() {
  stopPolling()
  pollTimer.value = setInterval(async () => {
    const b = batches.value.find((x) => x.id === selected.value?.id)
    if (b && (b.status === 'PROCESSING' || b.active > 0)) { await refreshSelected(); await loadBatches() }
  }, 4000)
}
function stopPolling() { if (pollTimer.value) { clearInterval(pollTimer.value); pollTimer.value = null } }

async function retry(id: number) {
  try { await api.post(`/admin/imports/items/${id}/retry`); ElMessage.success('已重新排队'); await refreshSelected(); await loadBatches() }
  catch (e: any) { ElMessage.error(errorMessage(e, '重试失败')) }
}
async function cancel(id: number) {
  try { await api.post(`/admin/imports/items/${id}/cancel`); await refreshSelected(); await loadBatches() }
  catch (e: any) { ElMessage.error(errorMessage(e, '取消失败')) }
}
async function publish() {
  if (!selected.value) return
  try {
    const r = await api.post(`/admin/imports/batches/${selected.value.id}/publish`)
    ElMessage.success(`已发布 ${r.data.published} 篇文章，员工问答即刻可检索`)
    await refreshSelected(); await loadBatches()
  } catch (e: any) { ElMessage.error(errorMessage(e, '批量发布失败')) }
}

const reviewCount = () => items.value.filter((x) => x.status === 'REVIEW').length
const activeCount = () => items.value.filter((x) => ['QUEUED', 'PARSING', 'NORMALIZING', 'CHUNKING', 'INDEXING'].includes(x.status)).length

onMounted(async () => {
  await loadBatches()
  try { departments.value = (await api.get('/auth/departments')).data } catch { /* 忽略 */ }
})
onUnmounted(() => { disconnectSse(); stopPolling() })
</script>

<template>
  <section class="page">
    <div class="page-head">
      <div>
        <p class="eyebrow">ADMIN / IMPORTS</p>
        <h1>导入中心</h1>
        <p class="muted">批量导入企业资料：原件入 MinIO，异步流水线解析→归一化→分块→索引，每步进度实时可见，失败可重试。</p>
      </div>
      <button class="primary" @click="loadBatches"><RefreshCw :size="15" />刷新批次</button>
    </div>

    <div class="panel upload-panel">
      <div class="panel-title"><span>新建导入批次</span><span class="muted">{{ pendingFiles.length }} 个文件待上传</span></div>
      <div class="dropzone" @dragover.prevent @drop.prevent="onDrop">
        <UploadCloud :size="26" />
        <p>拖拽文件到此处，或<label class="pick">选择文件<input type="file" multiple accept=".pdf,.docx,.txt,.md,.markdown" @change="pickFiles" /></label> / <label class="pick">选择文件夹<input type="file" webkitdirectory @change="pickFiles" /></label></p>
        <small class="muted">支持 PDF、DOCX、TXT、Markdown；单批次最多 100 个文件</small>
      </div>
      <div v-if="pendingFiles.length" class="pending-list">
        <div v-for="(p, i) in pendingFiles" :key="i" class="pending-file" :class="{ rejected: p.rejected }">
          <FolderOpen :size="13" /><span>{{ p.path }}</span><small>{{ fmtSize(p.file.size) }}</small>
          <small v-if="p.rejected" class="err-text">{{ p.rejected }}</small>
          <button class="small" @click="pendingFiles.splice(i, 1)">✕</button>
        </div>
      </div>
      <div class="upload-opts">
        <input v-model="batchName" placeholder="批次名称（可选）" style="max-width: 220px" />
        <select v-model="departmentId" style="max-width: 180px">
          <option :value="null">公开可见</option>
          <option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}（仅部门）</option>
        </select>
        <select v-model="duplicateStrategy" style="max-width: 200px" title="遇到已导入过的相同内容（sha256 一致）时如何处理">
          <option value="skip">重复内容跳过</option>
          <option value="version">作为新版本导入</option>
        </select>
        <button class="primary" :disabled="uploading || !pendingFiles.length" @click="upload"><Rocket :size="15" />{{ uploading ? `上传中 ${uploadProgress}%` : '上传并开始处理' }}</button>
      </div>
      <div v-if="uploading" class="upload-progress">
        <div class="progress-track"><div class="progress-fill" :style="{ width: uploadProgress + '%' }"></div></div>
        <small class="muted">正在上传到服务器（{{ uploadProgress }}%），完成后自动进入异步解析流水线</small>
      </div>
    </div>

    <div class="panel table-panel">
      <div class="panel-title"><span>导入批次</span><span class="muted">{{ batches.length }} 个</span></div>
      <table>
        <thead><tr><th>批次</th><th>状态</th><th>排队/处理中</th><th>待审核</th><th>已发布</th><th>失败/重复</th><th>创建时间</th></tr></thead>
        <tbody>
          <tr v-for="b in batches" :key="b.id" :class="{ selectedRow: selected?.id === b.id }" @click="openBatch(b.id)">
            <td><strong>#{{ b.id }} {{ b.name }}</strong></td>
            <td><span class="status">{{ { PROCESSING: '处理中', COMPLETED: '已完成', PARTIAL_FAILED: '部分失败' }[b.status] || b.status }}</span></td>
            <td>{{ b.queued }} / {{ b.active }}</td>
            <td>{{ b.review }}</td>
            <td>{{ b.published }}</td>
            <td>{{ b.failed }} / {{ b.duplicate }}</td>
            <td>{{ b.createdAt }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="selected" class="panel table-panel">
      <div class="panel-title">
        <span>批次 #{{ selected.id }} {{ selected.name }} · 文件明细</span>
        <span v-if="reviewCount()" class="citation-count">{{ reviewCount() }} 篇待审核</span>
      </div>
      <table>
        <thead><tr><th>文件</th><th>进度</th><th>状态</th><th>信息</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="it in items" :key="it.id">
            <td>
              <strong>{{ it.fileName }}</strong>
              <small v-if="it.sourcePath && it.sourcePath !== it.fileName">📁 {{ it.sourcePath }}</small>
              <small>{{ it.parser || it.contentType }} · {{ fmtSize(it.size) }}</small>
            </td>
            <td style="min-width: 160px">
              <div class="progress-track"><div class="progress-fill" :class="{ failed: it.status === 'FAILED' }" :style="{ width: it.progress + '%' }"></div></div>
              <small>{{ it.progress }}%</small>
            </td>
            <td><span class="status">{{ itemStatusLabel[it.status] || it.status }}</span></td>
            <td>
              <span v-if="it.error" class="err-text">{{ it.error }}</span>
              <span v-else-if="it.summary" class="muted">{{ it.summary.slice(0, 90) }}</span>
              <a v-if="it.articleId" class="link-button" style="margin: 0; display: inline" :href="`/knowledge/articles/${it.articleId}`">查看文章 →</a>
            </td>
            <td class="actions">
              <a class="small dl-btn" title="下载原件（含失败项，便于排障）" :href="`${apiBase}/admin/imports/items/${it.id}/file`"><Download :size="14" /></a>
              <button v-if="it.status === 'FAILED'" class="small" title="重试" @click="retry(it.id)"><RotateCcw :size="14" /></button>
              <button v-if="['QUEUED', 'FAILED'].includes(it.status)" class="small" title="取消" @click="cancel(it.id)"><Ban :size="14" /></button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="activeCount()" class="muted" style="margin-top: 10px">处理中 {{ activeCount() }} 项…进度通过 SSE 实时推送，断线自动重连。</div>
      <button v-if="reviewCount()" class="primary" style="margin-top: 14px" @click="publish"><CheckCircle2 :size="15" />批量发布 {{ reviewCount() }} 篇</button>
      <div v-if="eventsLog.length" class="events-wrap">
        <div class="panel-title" style="margin-top: 16px"><span>处理事件</span><span class="muted">{{ eventsLog.length }} 条</span></div>
        <div class="events-list">
          <div v-for="(ev, idx) in eventsLog.slice(-60).reverse()" :key="idx" class="event-row" :class="{ failed: ev.stage === 'FAILED' }">
            <code>{{ ev.stage }}</code>
            <span class="grow">{{ ev.message || ev.stage }}</span>
            <small>#{{ ev.itemId }} · {{ ev.progress ?? 0 }}%</small>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.dropzone { border: 2px dashed #cfd8e8; border-radius: 10px; padding: 30px; text-align: center; color: #6a768b; background: #fafcff; }
.dropzone .pick { color: #356bd3; cursor: pointer; position: relative; }
.dropzone .pick input { position: absolute; inset: 0; opacity: 0; cursor: pointer; }
.pending-list { margin-top: 12px; display: grid; gap: 5px; max-height: 150px; overflow: auto; }
.pending-file { display: flex; align-items: center; gap: 7px; font-size: 12px; color: #4e5b70; padding: 4px 8px; background: #f7f9fc; border-radius: 6px; }
.pending-file small { margin-left: auto; color: #8c98aa; }
.pending-file small.err-text { margin-left: 0; }
.pending-file.rejected { background: #fef2f2; }
.pending-file.rejected span { color: #b42318; }
.upload-progress { margin-top: 12px; display: grid; gap: 6px; }
.upload-progress .progress-track { height: 8px; }
.upload-opts { display: flex; gap: 10px; margin-top: 14px; flex-wrap: wrap; align-items: center; }
.selectedRow { background: #f2f7ff; }
.progress-track { height: 6px; background: #e9edf3; border-radius: 4px; overflow: hidden; }
.progress-fill { height: 100%; background: #2563eb; border-radius: 4px; transition: width .4s; }
.progress-fill.failed { background: #e11d48; }
.err-text { color: #b42318; font-size: 11px; }
.dl-btn { width: auto; padding: 5px 7px; display: inline-flex; align-items: center; color: #64748b; text-decoration: none; border: 1px solid transparent; border-radius: 5px; }
.dl-btn:hover { background: #edf3ff; color: #2e68d7; }
.events-wrap { margin-top: 4px; }
.events-list { max-height: 220px; overflow: auto; border: 1px solid #eef1f5; border-radius: 8px; }
.event-row { display: flex; align-items: center; gap: 9px; padding: 7px 10px; font-size: 11.5px; color: #4e5b70; border-bottom: 1px solid #f2f5f9; }
.event-row:last-child { border-bottom: 0; }
.event-row code { color: #356bd3; background: #eef4ff; border-radius: 4px; padding: 2px 6px; flex: none; }
.event-row.failed code { color: #b42318; background: #fff0ef; }
.event-row small { margin-left: auto; color: #a2acbb; flex: none; }
</style>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, apiBase, errorMessage } from '../api'
import { useAuth } from '../stores/auth'
import { renderMarkdown } from '../lib/markdown'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Download, Check, X, FileText, Hash, RefreshCw, Archive, Pencil } from 'lucide-vue-next'
import PdfViewer from '../components/PdfViewer.vue'

interface Chunk { id: number; chunkIndex: number; headingPath?: string; content: string; pageNo?: number | null; charCount?: number | null }
interface ArticleVersion { id: number; version: number; status: string; chunkReady: boolean; current: boolean; characters: number; author?: string; createdAt: string }

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const article = ref<any | null>(null)
const chunks = ref<Chunk[]>([])
const versions = ref<ArticleVersion[]>([])
const loading = ref(true)
const loadError = ref('')
const versionId = ref<number | null>(null)
const sourceMode = ref(false)
const targetChunk = ref<number | null>(null)
const targetPage = ref<number | null>(null)
const id = computed(() => Number(route.params.id))
const isPdf = computed(() => article.value?.file?.fileName?.toLowerCase().endsWith('.pdf'))
const fileUrl = computed(() => `${apiBase}/knowledge/articles/${id.value}/file?versionId=${article.value?.versionId}`)
const reviewing = ref(false)
const latestVersion = computed(() => versions.value[0])
const canReview = computed(() => auth.isAdmin && article.value && latestVersion.value?.id === article.value.versionId &&
  (['DRAFT', 'REJECTED'].includes(article.value.versionStatus) || article.value.status === 'ARCHIVED'))

const statusLabel: Record<string, string> = { DRAFT: '草稿', IN_REVIEW: '待审核', PUBLISHED: '已发布', ARCHIVED: '已归档', REJECTED: '已驳回' }
const sensitivityLabel: Record<string, string> = { INTERNAL: '内部', CONFIDENTIAL: '机密', PUBLIC: '公开' }

// ---- 管理员编辑：内容变更生成新版本（vMax+1），文章回到待审核；当前发布版本保持可读 ----
const editing = ref(false)
const saving = ref(false)
const editForm = ref({ title: '', content: '', category: '', visibility: 'PUBLIC', sensitivity: 'INTERNAL', tags: '' })
const editDepartmentIds = ref<number[]>([])
const departments = ref<any[]>([])

function startEdit() {
  editForm.value = {
    title: article.value?.title || '',
    content: article.value?.content || '',
    category: article.value?.category || '',
    visibility: article.value?.visibility || 'PUBLIC',
    sensitivity: article.value?.sensitivity || 'INTERNAL',
    tags: (article.value?.tags || []).map((t: any) => t.name || t).join('，'),
  }
  editDepartmentIds.value = (article.value?.departments || []).map((d: any) => d.id)
  editing.value = true
  if (!departments.value.length) api.get('/admin/departments').then(r => departments.value = r.data).catch(() => {})
}

async function saveEdit() {
  if (!editForm.value.title.trim() || !editForm.value.content.trim()) { ElMessage.warning('标题和正文不能为空'); return }
  const restricted = editForm.value.visibility === 'DEPARTMENT' || editForm.value.sensitivity === 'CONFIDENTIAL'
  if (restricted && editDepartmentIds.value.length === 0) {
    ElMessage.warning('受限可见性/机密文章必须至少授权一个部门，否则员工将全部不可见')
    return
  }
  saving.value = true
  try {
    const r = await api.put(`/knowledge/articles/${id.value}`, {
      title: editForm.value.title.trim(),
      content: editForm.value.content,
      category: editForm.value.category.trim() || undefined,
      visibility: editForm.value.visibility,
      sensitivity: editForm.value.sensitivity,
      tags: editForm.value.tags.split(/[，,]/).map(t => t.trim()).filter(Boolean),
      departmentIds: editDepartmentIds.value,
    })
    editing.value = false
    sourceMode.value = false
    const v = r.data?.newVersion
    ElMessage.success(v ? `已生成新版本 v${v}，待审核通过后生效` : '元数据已更新')
    if (r.data?.newVersionId) await selectVersion({ id: r.data.newVersionId })
    else await load()
  } catch (e: any) { ElMessage.error(errorMessage(e, '保存失败')) }
  finally { saving.value = false }
}

let loadSequence = 0
async function load() {
  const sequence = ++loadSequence
  loading.value = true
  loadError.value = ''
  editing.value = false
  targetChunk.value = route.query.chunk != null ? Number(route.query.chunk) : null
  targetPage.value = null
  try {
    const versionParam = route.query.version ? Number(route.query.version) : undefined
    const [detail, history] = await Promise.all([
      api.get(`/knowledge/articles/${id.value}`, { params: versionParam ? { versionId: versionParam } : {} }),
      api.get(`/knowledge/articles/${id.value}/versions`),
    ])
    if (sequence !== loadSequence) return
    article.value = detail.data
    versionId.value = article.value.versionId
    chunks.value = article.value.chunks || []
    versions.value = history.data
    const target = chunks.value.find(c => c.chunkIndex === targetChunk.value)
    if (target?.pageNo) targetPage.value = target.pageNo
  } catch (e: any) {
    if (sequence !== loadSequence) return
    article.value = null
    chunks.value = []
    versions.value = []
    loadError.value = errorMessage(e, '文章加载失败，可能是无权访问或文章不存在')
  } finally {
    if (sequence === loadSequence) {
      loading.value = false
      await nextTick()
      if (targetChunk.value != null) document.getElementById(`chunk-${targetChunk.value}`)?.scrollIntoView({ block: 'start' })
    }
  }
}

async function selectVersion(v: { id: number }) {
  if (v.id === article.value?.versionId) return
  await router.replace({ query: { ...route.query, version: String(v.id), chunk: undefined } })
}

async function showCurrent() {
  if (route.query.version) await router.replace({ query: { ...route.query, version: undefined, chunk: undefined } })
  else await load()
}

async function review(approve: boolean) {
  if (reviewing.value) return
  let comment: string | null = ''
  if (!approve) {
    const r = await ElMessageBox.prompt('请填写驳回原因（会记录到文章与审计）', '驳回文章', { inputPlaceholder: '原因' }).catch(() => null)
    if (!r) return
    comment = r.value
  }
  reviewing.value = true
  try {
    await api.post(`/knowledge/articles/${id.value}/review`, { approve, comment, versionId: article.value.versionId, expectedArticleStatus: article.value.status })
    ElMessage.success(approve ? '已发布' : '已驳回')
    await showCurrent()
  } catch (e: any) { ElMessage.error(errorMessage(e, '审核操作失败')) }
  finally { reviewing.value = false }
}

/** 撤回/下架：文章立即退出检索与列表，保留版本与审计记录 */
async function retract() {
  const r = await ElMessageBox.confirm('撤回后文章立即退出员工检索与列表（保留版本历史与审计记录）。确认撤回？', '撤回文章', { type: 'warning' }).catch(() => null)
  if (!r) return
  try {
    await api.post(`/knowledge/articles/${id.value}/retract`)
    ElMessage.success('已撤回')
    await showCurrent()
  } catch (e: any) { ElMessage.error(errorMessage(e, '撤回失败')) }
}

function openChunk(c: Chunk) {
  targetChunk.value = c.chunkIndex
  if (isPdf.value && c.pageNo) {
    targetPage.value = c.pageNo
    document.querySelector('.article-content')?.scrollIntoView({ behavior: 'smooth' })
  }
  nextTick(() => {
    document.getElementById(`chunk-${c.chunkIndex}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })
}

watch(() => [route.params.id, route.query.version, route.query.chunk], load, { immediate: true })
</script>

<template>
  <section class="page" v-if="!loading && article">
    <div class="page-head">
      <div>
        <p class="eyebrow">KNOWLEDGE / ARTICLE #{{ article.id }}</p>
        <h1>{{ article.title }}</h1>
        <p class="muted">
          <span class="status">{{ statusLabel[article.status] || article.status }}</span>
          <span> · 查看 v{{ article.version }} {{ statusLabel[article.versionStatus] || article.versionStatus }}</span>
          <span v-if="article.sensitivity"> · {{ sensitivityLabel[article.sensitivity] || article.sensitivity }}</span>
          <span v-if="article.reviewedAt"> · {{ article.reviewedBy ? '已审核' : '' }} {{ article.reviewedAt }}</span>
          <span v-if="article.file"> · 原件 {{ article.file.fileName }}（{{ Math.round(article.file.sizeBytes / 1024) }} KB）</span>
        </p>
      </div>
      <div class="detail-actions">
        <button class="icon-btn" title="返回" @click="$router.back()"><ArrowLeft :size="17" /></button>
        <a v-if="article.file" class="primary" :href="fileUrl"><Download :size="15" />下载此版本原件</a>
        <button v-if="auth.isAdmin && !editing" class="primary" style="background: #fff; color: #344158; border: 1px solid #dce2eb" @click="startEdit"><Pencil :size="14" />编辑</button>
        <template v-if="canReview && !editing">
          <button class="approve" :disabled="reviewing" @click="review(true)"><Check :size="14" />通过并发布此版本</button>
          <button v-if="article.versionStatus === 'DRAFT'" class="reject" :disabled="reviewing" @click="review(false)"><X :size="14" />驳回此版本</button>
        </template>
        <button v-if="auth.isAdmin && article.status === 'PUBLISHED' && !editing" class="reject" @click="retract"><Archive :size="14" />撤回下架</button>
      </div>
    </div>

    <div v-if="auth.isAdmin && latestVersion?.status === 'DRAFT' && latestVersion.id !== article.versionId && !editing" class="notice">
      有待审核的新稿 v{{ latestVersion.version }}。当前发布内容继续可用。
      <button class="small" @click="selectVersion(latestVersion)">查看并审核新稿</button>
    </div>

    <div v-if="editing" class="panel edit-panel">
      <div class="panel-title"><span>编辑文章（保存后生成新版本并回到待审核，当前发布版本不受影响）</span><button class="small" @click="editing = false">取消</button></div>
      <div class="form-grid edit-grid">
        <input v-model="editForm.title" placeholder="标题" />
        <input v-model="editForm.category" placeholder="分类" />
        <select v-model="editForm.visibility" title="可见性">
          <option value="PUBLIC">公开可见</option>
          <option value="DEPARTMENT">仅授权部门</option>
        </select>
        <select v-model="editForm.sensitivity" title="密级">
          <option value="INTERNAL">内部</option>
          <option value="PUBLIC">公开</option>
          <option value="CONFIDENTIAL">机密（须部门授权）</option>
        </select>
        <input v-model="editForm.tags" placeholder="标签，逗号分隔" style="grid-column: 1 / -1" />
        <textarea v-model="editForm.content" placeholder="Markdown 正文" style="min-height: 240px; grid-column: 1 / -1" />
      </div>
      <div v-if="departments.length && (editForm.visibility === 'DEPARTMENT' || editForm.sensitivity === 'CONFIDENTIAL')" class="dept-picker">
        <label v-for="d in departments" :key="d.id" class="dept-option">
          <input type="checkbox" :value="d.id" v-model="editDepartmentIds" />{{ d.name }}
        </label>
      </div>
      <button class="primary" :disabled="saving" @click="saveEdit"><Check :size="15" />保存并生成新版本</button>
    </div>

    <div class="article-grid" v-show="!editing">
      <aside class="outline-panel">
        <div class="panel-title"><span>大纲</span><span class="muted">{{ chunks.length }} 块</span></div>
        <div v-for="c in chunks" :key="c.id" class="outline-item" :class="{ active: targetChunk === c.chunkIndex }" @click="openChunk(c)">
          <Hash :size="12" />{{ c.headingPath || `#${c.chunkIndex + 1}` }}<small v-if="c.pageNo">p{{ c.pageNo }}</small>
        </div>
      </aside>
      <main class="article-content">
        <div class="content-toolbar">
          <select v-if="versions.length > 1" :value="versionId" class="model-select" @change="selectVersion({ id: Number(($event.target as HTMLSelectElement).value) })">
            <option v-for="v in versions" :key="v.id" :value="v.id">v{{ v.version }} {{ statusLabel[v.status] || v.status }} {{ v.current ? '· 当前生效' : '' }}</option>
          </select>
          <span v-else class="muted">v{{ article.version }} · {{ statusLabel[article.versionStatus] || article.versionStatus }}</span>
          <button class="small tool-btn" @click="sourceMode = !sourceMode"><FileText :size="13" />{{ sourceMode ? '渲染视图' : 'Markdown 源码' }}</button>
        </div>

        <div v-if="isPdf && !sourceMode" class="pdf-wrap">
          <PdfViewer :key="fileUrl" :url="fileUrl" :page="targetPage" />
        </div>
        <template v-else>
          <div v-if="sourceMode" class="source-block"><pre>{{ article.content }}</pre></div>
          <div v-else class="chunk-list">
            <div v-for="c in chunks" :key="c.id" :id="`chunk-${c.chunkIndex}`" class="chunk-card" :class="{ highlight: targetChunk === c.chunkIndex }">
              <div v-if="c.headingPath" class="chunk-head"><Hash :size="12" />{{ c.headingPath }}<small v-if="c.pageNo">第 {{ c.pageNo }} 页</small></div>
              <div class="chunk-body" v-html="renderMarkdown(c.content)" />
            </div>
          </div>
        </template>
      </main>
      <aside class="meta-panel">
        <div class="panel-title"><span>元数据</span></div>
        <dl>
          <dt>分类</dt><dd>{{ article.category }}</dd>
          <dt>可见性</dt><dd>{{ article.visibility === 'PUBLIC' ? '公开' : '部门授权' }}</dd>
          <dt>查看版本</dt><dd>v{{ article.version }}</dd>
          <dt>分块就绪</dt><dd>{{ article.chunkReady ? '是' : '否' }}</dd>
          <dt v-if="article.tags?.length">标签</dt><dd v-if="article.tags?.length"><span v-for="t in article.tags" :key="t.name" class="tag">{{ t.name }}</span></dd>
          <dt v-if="article.departments?.length">授权部门</dt><dd v-if="article.departments?.length"><span v-for="d in article.departments" :key="d.id" class="tag">{{ d.name }}</span></dd>
          <dt v-if="article.reviewComment">审核意见</dt><dd v-if="article.reviewComment">{{ article.reviewComment }}</dd>
          <dt v-if="article.sourceNoteId">来源笔记</dt><dd v-if="article.sourceNoteId"><a class="link-button" style="margin:0;display:inline" :href="`/vault/notes/${article.sourceNoteId}`">个人笔记 #{{ article.sourceNoteId }} →</a></dd>
        </dl>
        <div class="panel-title" style="margin-top:18px"><span>{{ auth.isAdmin ? '版本历史' : '当前发布版本' }}</span></div>
        <div v-for="v in versions" :key="v.id" class="version-item" :class="{ current: v.current }">
          <strong>v{{ v.version }}</strong><span>{{ statusLabel[v.status] || v.status }}</span>
          <small>{{ v.characters }} 字 · {{ v.createdAt }}</small>
          <button class="link-button" style="margin:0" @click="selectVersion(v)">查看</button>
        </div>
      </aside>
    </div>
  </section>
  <section v-else-if="loading" class="page"><div class="loading">加载中…</div></section>
  <section v-else class="page">
    <div class="notice error" style="margin:40px auto;max-width:520px;text-align:center">
      {{ loadError || '文章不存在或无权访问' }}
      <div style="margin-top:12px"><button class="small" @click="load"><RefreshCw :size="13" /> 重试</button></div>
      <button v-if="route.query.version" class="small" @click="showCurrent">查看当前发布版本</button>
    </div>
  </section>
</template>

<style scoped>
.detail-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.edit-panel { margin-bottom: 16px; }
.edit-grid { grid-template-columns: 1.4fr 1fr 1fr 1fr; }
.dept-picker { display: flex; gap: 14px; flex-wrap: wrap; margin-bottom: 12px; font-size: 12px; color: #344158; }
.dept-option { display: inline-flex; gap: 5px; align-items: center; }
.article-grid { display: grid; grid-template-columns: 200px 1fr 240px; gap: 14px; align-items: start; }
.outline-panel, .meta-panel { background: #fff; border: 1px solid #e7ebf2; border-radius: 9px; padding: 14px; position: sticky; top: 84px; max-height: calc(100vh - 110px); overflow: auto; }
.outline-item { display: flex; gap: 6px; align-items: center; font-size: 12px; color: #4e5b70; padding: 7px 8px; border-radius: 6px; cursor: pointer; }
.outline-item small { margin-left: auto; color: #a2acbb; font-size: 10px; }
.outline-item.active, .outline-item:hover { background: #edf3ff; color: #235dcc; }
.article-content { min-width: 0; }
.content-toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; }
.tool-btn { display: inline-flex; align-items: center; gap: 5px; border: 1px solid #e2e8f0; border-radius: 6px; padding: 6px 10px; font-size: 11px; color: #64748b; }
.chunk-card { background: #fff; border: 1px solid #e7ebf2; border-radius: 9px; padding: 16px 18px; margin-bottom: 12px; scroll-margin-top: 90px; transition: box-shadow .3s; }
.chunk-card.highlight { box-shadow: 0 0 0 3px #f59e0b55; border-color: #f59e0b; }
.chunk-head { display: flex; gap: 6px; align-items: center; font-size: 12px; font-weight: 700; color: #356bd3; margin-bottom: 9px; }
.chunk-head small { color: #8c98aa; font-weight: 400; }
.chunk-body { font-size: 13px; line-height: 1.8; color: #2b3850; }
.chunk-body :deep(p) { margin: 0 0 10px; }
.chunk-body :deep(h1), .chunk-body :deep(h2), .chunk-body :deep(h3) { font-size: 15px; }
.chunk-body :deep(code) { background: #f1f5f9; padding: 1px 5px; border-radius: 4px; }
.source-block pre { white-space: pre-wrap; font-size: 12px; line-height: 1.7; background: #0f172a; color: #e2e8f0; border-radius: 9px; padding: 16px; }
.pdf-wrap { background: #fff; border-radius: 9px; padding: 10px; }
.meta-panel dl { margin: 0; }
.meta-panel dt { font-size: 11px; color: #8c98aa; margin-top: 9px; }
.meta-panel dd { margin: 3px 0 0; font-size: 12px; color: #344158; }
.version-item { border-top: 1px solid #eef1f5; padding: 9px 0; font-size: 12px; }
.version-item strong { color: #356bd3; margin-right: 7px; }
.version-item span { color: #64748b; margin-right: 7px; }
.version-item small { display: block; color: #8c98aa; margin-top: 3px; }
.version-item.current { background: #f2f7ff; border-radius: 6px; padding: 8px; }
@media (max-width: 1100px) { .article-grid { grid-template-columns: 1fr; } .outline-panel { position: static; max-height: 200px; } .meta-panel { position: static; max-height: none; } }
</style>

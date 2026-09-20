<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Save, Trash2, Send, Search, NotebookPen, Link2, Share2 } from 'lucide-vue-next'
import { renderWikilinks } from '../lib/markdown'

const route = useRoute()
const router = useRouter()
const notes = ref<any[]>([])
const q = ref('')
const current = ref<any | null>(null)
const form = ref({ title: '', content: '', tags: '', folderPath: '' })
const editingNew = ref(false)
const saving = ref(false)
const loadError = ref('')
const preview = ref(false)
const backlinks = ref<any[]>([])
const graph = ref<{ nodes: any[]; edges: any[] } | null>(null)
const graphError = ref('')
const isGraph = computed(() => route.path.endsWith('/graph'))
const noteId = computed(() => (route.params.id ? Number(route.params.id) : null))

async function loadNotes() {
  loadError.value = ''
  try { notes.value = (await api.get('/vault/notes', { params: q.value ? { q: q.value } : {} })).data }
  catch (e: any) { loadError.value = errorMessage(e, '笔记列表加载失败') }
}
async function loadGraph() {
  graphError.value = ''
  try { graph.value = (await api.get('/vault/graph', { params: { scope: 'all' } })).data } catch (e: any) { graphError.value = e?.response?.data?.message || '图谱加载失败' }
}
async function loadNote(id: number) {
  try {
    const d = (await api.get(`/vault/notes/${id}`)).data
    current.value = d
    form.value = { title: d.title || '', content: d.content || '', tags: (d.tags || []).join(', '), folderPath: d.folder_path || '' }
    backlinks.value = d.backlinks || []
    preview.value = false
  } catch (e: any) { ElMessage.error(errorMessage(e, '笔记加载失败')) }
}

async function save() {
  if (!form.value.title.trim()) return ElMessage.warning('请填写标题')
  if (saving.value) return
  saving.value = true
  const payload = { title: form.value.title, content: form.value.content, tags: form.value.tags.split(',').map((s) => s.trim()).filter(Boolean), folderPath: form.value.folderPath }
  try {
    if (noteId.value) {
      await api.put(`/vault/notes/${noteId.value}`, payload)
      ElMessage.success('笔记已保存')
      await loadNote(noteId.value); await loadNotes()
    } else {
      const r = await api.post('/vault/notes', payload)
      ElMessage.success('笔记已创建')
      editingNew.value = false
      router.push(`/vault/notes/${r.data.id}`)
      await loadNotes()
    }
  } catch (e: any) { ElMessage.error(errorMessage(e, '笔记保存失败')) }
  finally { saving.value = false }
}

async function remove() {
  if (!noteId.value) return
  const r = await ElMessageBox.confirm('删除后无法恢复，确定删除这篇笔记？', '删除笔记', { type: 'warning' }).catch(() => null)
  if (!r) return
  try {
    await api.delete(`/vault/notes/${noteId.value}`)
    ElMessage.success('已删除')
    router.push('/vault')
    await loadNotes()
  } catch (e: any) { ElMessage.error(errorMessage(e, '删除失败')) }
}

async function toEnterprise() {
  if (!noteId.value) return
  const r = await ElMessageBox.confirm('将这篇笔记复制为企业知识草稿（待管理员审核），个人笔记保留不动。', '提交审核', { confirmButtonText: '提交', cancelButtonText: '取消' }).catch(() => null)
  if (!r) return
  try {
    const res = (await api.post(`/vault/notes/${noteId.value}/to-enterprise`)).data
    ElMessage.success('已提交审核，管理员可在"资料与知识"中审核发布')
    if (res.articleId) window.open(`/knowledge/articles/${res.articleId}`, '_blank')
  } catch (e: any) { ElMessage.error(errorMessage(e, '提交审核失败')) }
}

function newNote() {
  router.push('/vault')
  current.value = null
  editingNew.value = true
  form.value = { title: '', content: '', tags: '', folderPath: '' }
  backlinks.value = []
  preview.value = false
}

function openGraph() { router.push('/vault/graph'); if (!graph.value) loadGraph() }

function graphClick(node: any) {
  if (node.type === 'NOTE') router.push(`/vault/notes/${node.key.split(':')[1]}`)
  else if (node.type === 'ARTICLE') window.open(`/knowledge/articles/${node.key.split(':')[1]}`, '_blank')
  else if (node.type === 'TAG') { router.push('/vault'); q.value = node.label }
}

// ---- 图谱布局：径向圆 ----
const svg = computed(() => {
  const g = graph.value
  if (!g) return null
  const nodes = g.nodes || []
  const edges = g.edges || []
  const w = 760, h = 560, cx = w / 2, cy = h / 2, r = Math.min(cx, cy) - 70
  const pos = new Map<string, { x: number; y: number }>()
  nodes.forEach((n, i) => {
    const angle = (2 * Math.PI * i) / Math.max(nodes.length, 1) - Math.PI / 2
    pos.set(n.key, { x: cx + r * Math.cos(angle), y: cy + r * Math.sin(angle) })
  })
  const colors: Record<string, string> = { NOTE: '#2563eb', TAG: '#10b981', ARTICLE: '#f59e0b' }
  const edgeLines = edges.map((e) => ({ ...e, from: pos.get(e.source), to: pos.get(e.target) })).filter((e) => e.from && e.to)
  return { w, h, edgeLines, nodes: nodes.map((n) => ({ ...n, ...(pos.get(n.key) || { x: cx, y: cy }), color: colors[n.type] || '#94a3b8' })) }
})

watch(noteId, (id) => {
  if (id) { editingNew.value = false; loadNote(id) }
}, { immediate: true })

let searchTimer: ReturnType<typeof setTimeout> | null = null
watch(q, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => loadNotes(), 300)
})
onMounted(async () => {
  await loadNotes()
  if (isGraph.value) await loadGraph()
  // [[双向链接]] 落地：按标题定位笔记并打开
  const wikilink = route.query.wikilink as string | undefined
  if (wikilink) {
    const hit = notes.value.find((n: any) => String(n.title).toLowerCase() === wikilink.toLowerCase())
    if (hit) { router.replace('/vault'); await loadNote(hit.id) }
    else ElMessage.warning(`未找到笔记"${wikilink}"，可在左侧新建`)
  }
})
watch(isGraph, (g) => { if (g) loadGraph() })
</script>

<template>
  <section class="page">
    <div class="page-head">
      <div>
        <p class="eyebrow">VAULT</p>
        <h1>{{ isGraph ? '知识地图' : '个人知识库' }}</h1>
        <p class="muted">{{ isGraph ? '节点 = 笔记/标签/企业文章，边 = 双向链接、同标签；点击节点直达详情。' : 'Markdown 笔记默认私有，仅提交审核后才进入企业知识审核流。支持 [[双向链接]] 与标签。' }}</p>
      </div>
      <div class="vault-actions">
        <button class="primary" :class="{ ghost: isGraph }" @click="!isGraph && newNote()"><Plus :size="15" />新建笔记</button>
        <button class="primary ghost" @click="openGraph"><Share2 :size="15" />知识地图</button>
      </div>
    </div>

    <template v-if="!isGraph">
      <div class="vault-layout">
        <aside class="notes-panel">
          <div class="searchbar"><Search :size="15" /><input v-model="q" placeholder="搜索我的笔记" /></div>
          <div v-if="loadError" class="notice error">{{ loadError }} <button class="small" @click="loadNotes">重试</button></div>
          <div v-for="n in notes" :key="n.id" class="note-row" :class="{ current: noteId === n.id }" @click="router.push(`/vault/notes/${n.id}`)">
            <NotebookPen :size="14" />
            <div class="grow"><strong>{{ n.title }}</strong><small>{{ (n.tags || []).join(' · ') || '无标签' }} · v{{ n.version }}</small></div>
          </div>
          <div v-if="!notes.length && !loadError" class="muted side-hint">还没有笔记，点击"新建笔记"开始记录</div>
        </aside>
        <main class="editor-panel">
          <div v-if="!noteId && !editingNew && !current" class="editor-empty"><NotebookPen :size="30" /><p>选择左侧笔记，或新建一篇。</p></div>
          <template v-else>
            <div class="editor-bar">
              <input v-model="form.title" placeholder="笔记标题" class="title-input" />
              <div>
                <button class="small tool-btn" @click="preview = !preview">{{ preview ? '编辑' : '预览' }}</button>
                <button class="primary" :disabled="saving" @click="save"><Save :size="14" />{{ saving ? '保存中…' : '保存' }}</button>
                <button v-if="noteId" class="small tool-btn" title="提交审核为企业知识" @click="toEnterprise"><Send :size="13" />提交审核</button>
                <button v-if="noteId" class="small tool-btn danger" title="删除" @click="remove"><Trash2 :size="13" /></button>
              </div>
            </div>
            <input v-model="form.tags" placeholder="标签（逗号分隔），例如：VPN, 运维" class="tags-input" />
            <textarea v-if="!preview" v-model="form.content" placeholder="支持 Markdown 与 [[双向链接]]…" class="content-area"></textarea>
            <div v-else class="preview-area" v-html="renderWikilinks(form.content)" />
            <div v-if="backlinks.length" class="backlinks">
              <strong><Link2 :size="13" />反向链接 {{ backlinks.length }}</strong>
              <div v-for="b in backlinks" :key="b.srcNoteId" class="backlink-item" @click="router.push(`/vault/notes/${b.srcNoteId}`)">← {{ b.srcTitle }}</div>
            </div>
          </template>
        </main>
      </div>
    </template>

    <template v-else>
      <div class="panel graph-panel">
        <div v-if="graphError" class="notice error">{{ graphError }}</div>
        <svg v-else-if="svg" :width="svg.w" :height="svg.h" viewBox="0 0 760 560" class="graph-svg">
          <line v-for="(e, i) in svg.edgeLines" :key="'e' + i" :x1="e.from!.x" :y1="e.from!.y" :x2="e.to!.x" :y2="e.to!.y" stroke="#cbd5e1" stroke-width="1.2" />
          <g v-for="n in svg.nodes" :key="n.key" class="graph-node" @click="graphClick(n)">
            <circle :cx="n.x" :cy="n.y" :r="n.type === 'TAG' ? 13 : 18" :fill="n.color" opacity="0.92" />
            <text :x="n.x" :y="n.y + 32" text-anchor="middle" font-size="11" fill="#475569">{{ n.label.length > 14 ? n.label.slice(0, 13) + '…' : n.label }}</text>
            <title>{{ n.label }}</title>
          </g>
        </svg>
        <div v-else class="muted">加载图谱…</div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.vault-actions { display: flex; gap: 8px; }
.primary.ghost { background: #fff; color: #2563eb; border: 1px solid #c6d8f7; }
.vault-layout { display: grid; grid-template-columns: 260px 1fr; gap: 14px; align-items: start; }
.notes-panel, .editor-panel { background: #fff; border: 1px solid #e7ebf2; border-radius: 9px; padding: 14px; }
.notes-panel { max-height: calc(100vh - 200px); overflow: auto; }
.note-row { display: flex; gap: 8px; align-items: center; padding: 10px 9px; border-radius: 8px; cursor: pointer; }
.note-row:hover { background: #f2f6fc; }
.note-row.current { background: #edf3ff; }
.note-row strong { display: block; font-size: 13px; color: #2b3850; }
.note-row small { display: block; color: #8c98aa; font-size: 11px; margin-top: 3px; }
.side-hint { padding: 14px; font-size: 12px; }
.editor-empty { text-align: center; color: #a2acbb; padding: 70px 0; }
.editor-bar { display: flex; gap: 10px; align-items: center; margin-bottom: 10px; }
.title-input { flex: 1; font-size: 16px; font-weight: 700; }
.tags-input { width: 100%; margin-bottom: 10px; font-size: 12px; }
.content-area { width: 100%; min-height: 340px; resize: vertical; font-family: Consolas, "SF Mono", monospace; font-size: 13px; line-height: 1.7; }
.preview-area { min-height: 340px; border: 1px solid #dce2eb; border-radius: 6px; padding: 14px 16px; font-size: 13px; line-height: 1.9; }
.preview-area :deep(a.wikilink) { color: #2563eb; text-decoration: underline; cursor: pointer; }
.tool-btn { display: inline-flex; align-items: center; gap: 5px; border: 1px solid #e2e8f0; border-radius: 6px; padding: 8px 11px; font-size: 12px; color: #64748b; background: #fff; margin-right: 6px; }
.tool-btn.danger:hover { color: #e11d48; border-color: #fecdd3; }
.backlinks { margin-top: 14px; border-top: 1px solid #eef1f5; padding-top: 12px; }
.backlinks strong { display: flex; gap: 5px; align-items: center; font-size: 12px; color: #64748b; margin-bottom: 8px; }
.backlink-item { font-size: 12px; color: #356bd3; padding: 5px 8px; cursor: pointer; border-radius: 6px; }
.backlink-item:hover { background: #f2f6fc; }
.graph-panel { display: grid; place-items: center; }
.graph-svg { max-width: 100%; height: auto; }
.graph-node { cursor: pointer; }
.graph-node circle { transition: r .15s; }
.graph-node:hover circle { stroke: #0f172a; stroke-width: 2; }
@media (max-width: 900px) { .vault-layout { grid-template-columns: 1fr; } }
</style>

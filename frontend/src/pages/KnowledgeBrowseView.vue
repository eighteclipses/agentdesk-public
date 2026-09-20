<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import { Search, BookOpen, ExternalLink, FileSearch, ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { openCitationTarget } from '../lib/citations'

interface Article { id: number; title: string; category: string; status: string; version: number; visibility: string; updatedAt: string }
/** 全文检索命中（后端 /knowledge/search，与问答同一检索链路；score 为相关度，混合检索时为 RRF 融合分） */
interface Hit { articleId: number; versionId: number; title: string; snippet: string; citation: string; headingPath?: string; page?: number; score?: number }

const router = useRouter()
const items = ref<Article[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const q = ref('')
const category = ref('')
const tag = ref('')
const categories = ref<string[]>([])
const tags = ref<{ name: string; count: number }[]>([])
const loading = ref(false)
const loadError = ref('')
const ftsHits = ref<Hit[] | null>(null)
const ftsTotal = ref(0)
const ftsPage = ref(1)
const ftsLoading = ref(false)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const r = await api.get('/knowledge', { params: { page: page.value, size: size.value, q: q.value || undefined, category: category.value || undefined, tag: tag.value || undefined } })
    items.value = r.data.items || []
    total.value = r.data.total || 0
  } catch (e: any) { loadError.value = errorMessage(e, '知识列表加载失败') }
  finally { loading.value = false }
}

async function loadFacets() {
  try {
    const [c, t] = await Promise.all([api.get('/knowledge/categories'), api.get('/knowledge/tags')])
    categories.value = c.data || []
    tags.value = t.data || []
  } catch { /* 字典加载失败不阻塞列表 */ }
}

function search() { ftsHits.value = null; page.value = 1; load() }
function goto(p: number) { page.value = p; load() }
function onFilterChange() { page.value = 1; ftsHits.value = null; load() }
const pageCount = () => Math.max(1, Math.ceil(total.value / size.value))

/** 全文检索：走分块 FTS/混合检索，返回带锚点引用的命中（与问答检索同一链路），支持分页 */
async function fullTextSearch(p = 1) {
  if (!q.value.trim()) return
  ftsLoading.value = true
  try {
    const r = await api.get('/knowledge/search', { params: { q: q.value.trim(), category: category.value || undefined, tag: tag.value || undefined, page: p, size: 20 } })
    ftsHits.value = r.data.items
    ftsTotal.value = r.data.total || 0
    ftsPage.value = p
  } catch (e: any) { loadError.value = errorMessage(e, '全文检索失败'); ftsHits.value = null }
  finally { ftsLoading.value = false }
}
const ftsPageCount = () => Math.max(1, Math.ceil(ftsTotal.value / 20))

const fmtScore = (s?: number) => s == null ? '' : s >= 1 ? s.toFixed(2) : s.toFixed(4)

onMounted(() => { load(); loadFacets() })
</script>

<template>
  <section class="page">
    <div class="page-head">
      <div>
        <p class="eyebrow">WORKSPACE / KNOWLEDGE</p>
        <h1>企业知识库</h1>
        <p class="muted">浏览你有权限访问的已发布资料；打开文章可阅读全文、大纲与版本历史。问答时引用会自动定位到这里。</p>
      </div>
    </div>
    <div class="panel table-panel">
      <div class="panel-title">
        <span>资料目录</span><span class="muted">{{ total }} 篇</span>
        <div class="search-box">
          <Search :size="14" />
          <input v-model="q" placeholder="搜索，例如：VPN 认证超时" @keyup.enter="search" />
          <select v-model="category" class="filter-select" title="按分类筛选" @change="onFilterChange">
            <option value="">全部分类</option>
            <option v-for="c in categories" :key="c" :value="c">{{ c }}</option>
          </select>
          <select v-model="tag" class="filter-select" title="按标签筛选" @change="onFilterChange">
            <option value="">全部标签</option>
            <option v-for="t in tags" :key="t.name" :value="t.name">{{ t.name }}（{{ t.count }}）</option>
          </select>
          <button class="small" title="按标题过滤列表" @click="search">标题搜索</button>
          <button class="small" title="全文检索分块内容（同问答检索链路）" :disabled="ftsLoading" @click="fullTextSearch(1)"><FileSearch :size="14" />全文检索</button>
        </div>
      </div>
      <div v-if="loadError" class="notice error">{{ loadError }} <button class="small" @click="load">重试</button></div>
      <div v-if="ftsHits" class="fts-results">
        <div class="panel-title"><span>全文检索结果</span><span class="muted">{{ ftsTotal }} 条命中</span><button class="small" @click="ftsHits = null">返回目录</button></div>
        <div v-if="!ftsHits.length" class="empty-state"><BookOpen :size="26" /><p>没有命中任何分块；换个关键词，或直接到问答工作区提问</p></div>
        <div v-for="h in ftsHits" :key="h.citation" class="fts-hit" @click="openCitationTarget(h.citation)">
          <strong>{{ h.title }}</strong>
          <span v-if="h.score != null" class="hit-score" title="相关度得分（混合检索时为 FTS+向量 RRF 融合分）">{{ fmtScore(h.score) }}</span>
          <small>{{ h.citation }}<template v-if="h.headingPath"> · {{ h.headingPath }}</template><template v-if="h.page"> · 第 {{ h.page }} 页</template></small>
          <p>{{ h.snippet }}</p>
        </div>
        <div v-if="ftsPageCount() > 1" class="pager">
          <button class="small" :disabled="ftsPage <= 1" @click="fullTextSearch(ftsPage - 1)"><ChevronLeft :size="14" />上一页</button>
          <span class="muted">{{ ftsPage }} / {{ ftsPageCount() }}</span>
          <button class="small" :disabled="ftsPage >= ftsPageCount()" @click="fullTextSearch(ftsPage + 1)">下一页<ChevronRight :size="14" /></button>
        </div>
      </div>
      <div v-if="loading" class="loading">加载中…</div>
      <div v-else-if="!items.length && !ftsHits" class="empty-state">
        <BookOpen :size="30" />
        <p>{{ q || category || tag ? '没有符合筛选条件的已发布资料' : '还没有你有权限访问的已发布资料' }}</p>
      </div>
      <table v-else-if="!ftsHits">
        <thead><tr><th>标题</th><th>分类</th><th>版本</th><th>可见性</th><th>更新时间</th><th></th></tr></thead>
        <tbody>
          <tr v-for="a in items" :key="a.id" class="kb-row" @click="router.push(`/knowledge/articles/${a.id}`)">
            <td><strong>{{ a.title }}</strong><small>#{{ a.id }}</small></td>
            <td>{{ a.category }}</td>
            <td>v{{ a.version }}</td>
            <td>{{ a.visibility === 'PUBLIC' ? '公开' : '部门授权' }}</td>
            <td><small>{{ a.updatedAt?.slice(0, 10) }}</small></td>
            <td><ExternalLink :size="14" /></td>
          </tr>
        </tbody>
      </table>
      <div v-if="pageCount() > 1 && !ftsHits" class="pager">
        <button class="small" :disabled="page <= 1" @click="goto(page - 1)">上一页</button>
        <span class="muted">{{ page }} / {{ pageCount() }}</span>
        <button class="small" :disabled="page >= pageCount()" @click="goto(page + 1)">下一页</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.panel-title { justify-content: flex-start; gap: 10px; flex-wrap: wrap; }
.search-box { margin-left: auto; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.search-box input { min-width: 200px; }
.search-box .small { width: auto; padding: 6px 10px; display: inline-flex; align-items: center; gap: 5px; }
.filter-select { padding: 7px 9px; font-size: 12px; }
.kb-row { cursor: pointer; }
.kb-row:hover { background: #f2f6fc; }
.empty-state { text-align: center; color: #a2acbb; padding: 40px 0; }
.pager { display: flex; align-items: center; gap: 10px; justify-content: center; margin-top: 14px; }
.pager .small { width: auto; padding: 6px 10px; display: inline-flex; align-items: center; gap: 4px; }
.fts-results { margin-bottom: 14px; }
.fts-hit { border: 1px solid #e7ebf2; border-radius: 8px; padding: 10px 12px; margin-bottom: 8px; cursor: pointer; position: relative; }
.fts-hit:hover { border-color: #9cc0f5; background: #f7faff; }
.fts-hit strong { font-size: 13px; color: #3d65a2; }
.hit-score { position: absolute; right: 12px; top: 10px; font-size: 11px; font-weight: 700; color: #2f66d4; background: #eaf2ff; border-radius: 5px; padding: 2px 7px; }
.fts-hit small { display: block; color: #8c98aa; font-size: 11px; margin: 3px 0; }
.fts-hit p { margin: 0; font-size: 12px; color: #64748b; line-height: 1.6; }
</style>

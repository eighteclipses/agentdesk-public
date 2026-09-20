<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, X, Boxes, ExternalLink, Archive, Search, FilePlus2 } from 'lucide-vue-next'

const router = useRouter()
const knowledge = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const q = ref('')
const status = ref('')
const loadError = ref('')
const statusLabel: Record<string, string> = { DRAFT: '草稿', IN_REVIEW: '待审核', PUBLISHED: '已发布', ARCHIVED: '已归档', REJECTED: '已驳回' }

async function load() {
  loadError.value = ''
  try {
    const r = await api.get('/knowledge', { params: { page: page.value, size: size.value, q: q.value || undefined, status: status.value || undefined } })
    // 传 page/size 时后端返回分页结构；保留全量数组兼容
    knowledge.value = r.data.items ?? r.data
    total.value = r.data.total ?? knowledge.value.length
  } catch (e: any) { loadError.value = errorMessage(e, '知识列表加载失败') }
}
onMounted(load)

function search() { page.value = 1; load() }
function goto(p: number) { page.value = p; load() }
const pageCount = () => Math.max(1, Math.ceil(total.value / size.value))

async function review(a: any, approve: boolean) {
  let comment: string | null = ''
  if (!approve) {
    const r = await ElMessageBox.prompt('请填写驳回原因', '驳回文章', { inputPlaceholder: '例如：内容与现有文档重复' }).catch(() => null)
    if (!r) return
    comment = r.value
  }
  try {
    await api.post(`/knowledge/articles/${a.id}/review`, { approve, comment })
    ElMessage.success(approve ? '已发布，员工问答即刻可检索' : '已驳回')
    await load()
  } catch (e: any) { ElMessage.error(errorMessage(e, '审核操作失败')) }
}

async function retract(a: any) {
  const r = await ElMessageBox.confirm(`撤回《${a.title}》后将立即退出员工检索（保留版本与审计记录）。确认？`, '撤回文章', { type: 'warning' }).catch(() => null)
  if (!r) return
  try {
    await api.post(`/knowledge/articles/${a.id}/retract`)
    ElMessage.success('已撤回')
    await load()
  } catch (e: any) { ElMessage.error(errorMessage(e, '撤回失败')) }
}

// ---- 新建文章（激活 POST /api/knowledge）----
const showCreate = ref(false)
const creating = ref(false)
const departments = ref<any[]>([])
const createDepartmentIds = ref<number[]>([])
const form = ref({ title: '', content: '', category: '', visibility: 'PUBLIC', sensitivity: 'INTERNAL', tags: '' })

async function openCreate() {
  showCreate.value = !showCreate.value
  if (showCreate.value && !departments.value.length) {
    try { departments.value = (await api.get('/admin/departments')).data } catch { /* 下拉留空 */ }
  }
}

async function createArticle() {
  if (!form.value.title.trim() || !form.value.content.trim()) { ElMessage.warning('标题和正文不能为空'); return }
  creating.value = true
  try {
    const payload: any = {
      title: form.value.title.trim(),
      content: form.value.content,
      category: form.value.category.trim() || 'GENERAL',
      visibility: form.value.visibility,
      sensitivity: form.value.sensitivity,
      tags: form.value.tags.split(/[，,]/).map((t: string) => t.trim()).filter(Boolean),
      departmentIds: form.value.visibility === 'DEPARTMENT' ? createDepartmentIds.value : [],
    }
    await api.post('/knowledge', payload)
    ElMessage.success('文章已创建（草稿），审核通过后发布')
    showCreate.value = false
    createDepartmentIds.value = []
    form.value = { title: '', content: '', category: '', visibility: 'PUBLIC', sensitivity: 'INTERNAL', tags: '' }
    await load()
  } catch (e: any) { ElMessage.error(errorMessage(e, '创建失败')) }
  finally { creating.value = false }
}
</script>

<template>
  <section class="page">
    <div class="page-head">
      <div>
        <p class="eyebrow">ADMIN / KNOWLEDGE</p>
        <h1>资料与知识</h1>
        <p class="muted">导入的资料经解析、分块、索引后进入待审核；审核通过才参与员工问答。批量导入请到导入中心。</p>
      </div>
      <div class="actions">
        <button class="primary" style="background: #fff; color: #344158; border: 1px solid #dce2eb" @click="openCreate"><FilePlus2 :size="15" />新建文章</button>
        <button class="primary" @click="router.push('/admin/imports')"><Boxes :size="15" />导入中心</button>
      </div>
    </div>
    <div v-if="showCreate" class="panel create-panel">
      <div class="panel-title"><span>新建知识文章</span><button class="small" @click="showCreate = false">收起</button></div>
      <div class="form-grid create-grid">
        <input v-model="form.title" placeholder="标题（必填）" />
        <input v-model="form.category" placeholder="分类，如：网络 / 软件 / 通用" />
        <select v-model="form.visibility" title="可见性">
          <option value="PUBLIC">公开可见</option>
          <option value="DEPARTMENT">仅授权部门</option>
        </select>
        <select v-model="form.sensitivity" title="密级：CONFIDENTIAL 必须部门授权才可读">
          <option value="INTERNAL">内部</option>
          <option value="PUBLIC">公开</option>
          <option value="CONFIDENTIAL">机密（须部门授权）</option>
        </select>
        <input v-model="form.tags" placeholder="标签，逗号分隔，如：VPN,网络" style="grid-column: 1 / -1" />
        <textarea v-model="form.content" placeholder="Markdown 正文（必填）。保存后为草稿，审核通过才发布参与问答。" style="min-height: 160px" />
      </div>
      <div v-if="form.visibility === 'DEPARTMENT'" class="dept-picker">
        <label v-for="d in departments" :key="d.id" class="dept-option">
          <input type="checkbox" :value="d.id" v-model="createDepartmentIds" />{{ d.name }}
        </label>
      </div>
      <button class="primary" :disabled="creating" @click="createArticle"><Check :size="15" />保存草稿</button>
    </div>
    <div class="panel table-panel">
      <div class="panel-title">
        <span>知识文章审核</span><span class="muted">{{ total }} 篇</span>
        <div class="search-box">
          <Search :size="14" />
          <input v-model="q" placeholder="按标题搜索" @keyup.enter="search" />
          <select v-model="status" class="filter-select" title="按状态筛选" @change="search()">
            <option value="">全部状态</option>
            <option v-for="(label, key) in statusLabel" :key="key" :value="key">{{ label }}</option>
          </select>
          <button class="small" @click="search">搜索</button>
        </div>
      </div>
      <div v-if="loadError" class="notice error">{{ loadError }} <button class="small" @click="load">重试</button></div>
      <table>
        <thead><tr><th>文章</th><th>分类</th><th>版本</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="k in knowledge" :key="k.id">
            <td>
              <strong>{{ k.title }}</strong>
              <small>#{{ k.id }} · {{ k.visibility === 'PUBLIC' ? '公开' : '部门授权' }}</small>
              <a class="link-button" style="margin: 0; display: inline" :href="`/knowledge/articles/${k.id}`">详情 <ExternalLink :size="10" /></a>
            </td>
            <td>{{ k.category }}</td>
            <td>v{{ k.version }}</td>
            <td><span class="status">{{ statusLabel[k.status] || k.status }}</span></td>
            <td>
              <template v-if="k.status === 'IN_REVIEW' || k.status === 'DRAFT' || k.status === 'REJECTED'">
                <button class="approve" @click="review(k, true)"><Check :size="14" />通过</button>
                <button v-if="k.status === 'IN_REVIEW'" class="reject" @click="review(k, false)"><X :size="14" />驳回</button>
              </template>
              <template v-else-if="k.status === 'PUBLISHED'">
                <span class="muted">已生效</span>
                <button class="small" title="撤回下架" @click="retract(k)"><Archive :size="13" /></button>
              </template>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="!knowledge.length && !loadError" class="muted" style="padding: 20px">{{ q || status ? `没有符合筛选条件的文章` : '还没有文章，先到导入中心上传资料或点击"新建文章"' }}</div>
      <div v-if="pageCount() > 1" class="pager">
        <button class="small" :disabled="page <= 1" @click="goto(page - 1)">上一页</button>
        <span class="muted">{{ page }} / {{ pageCount() }}</span>
        <button class="small" :disabled="page >= pageCount()" @click="goto(page + 1)">下一页</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.panel-title { justify-content: flex-start; gap: 10px; }
.search-box { margin-left: auto; display: flex; align-items: center; gap: 6px; }
.search-box input { min-width: 200px; padding: 7px 10px; font-size: 12px; }
.filter-select { padding: 7px 9px; font-size: 12px; }
.pager { display: flex; align-items: center; gap: 10px; justify-content: center; margin-top: 14px; }
.create-panel { margin-bottom: 18px; }
.create-grid { grid-template-columns: 1fr 1fr 1fr 1fr; }
.dept-picker { display: flex; gap: 14px; flex-wrap: wrap; margin-bottom: 12px; font-size: 12px; color: #344158; }
.dept-option { display: inline-flex; gap: 5px; align-items: center; }
</style>

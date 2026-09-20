<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, apiBase, errorMessage } from '../api'
import { ElMessage } from 'element-plus'
import { Check, X, Download } from 'lucide-vue-next'

const actions = ref<any[]>([])
const audits = ref<any[]>([])
const auditTotal = ref(0)
const auditPage = ref(1)
const auditSize = ref(50)
const actionFilter = ref('')
const busy = ref(false)
const loadError = ref('')

async function loadActions() {
  try { actions.value = (await api.get('/agent/actions')).data }
  catch (e: any) { loadError.value = errorMessage(e, '审批列表加载失败') }
}

async function loadAudits() {
  loadError.value = ''
  try {
    const r = await api.get('/audit', { params: { page: auditPage.value, size: auditSize.value, action: actionFilter.value || undefined } })
    audits.value = r.data.items || []
    auditTotal.value = r.data.total || 0
  } catch (e: any) { loadError.value = errorMessage(e, '审计日志加载失败') }
}

async function load() { await Promise.all([loadActions(), loadAudits()]) }
onMounted(load)

function filterAudits() { auditPage.value = 1; loadAudits() }
function gotoPage(p: number) { auditPage.value = p; loadAudits() }
const pageCount = () => Math.max(1, Math.ceil(auditTotal.value / auditSize.value))

async function decide(id: number, ok: boolean) {
  busy.value = true
  try { await api.post(`/agent/actions/${id}/${ok ? 'approve' : 'reject'}`); await load(); ElMessage.success(ok ? '已批准' : '已驳回') }
  catch (e: any) { ElMessage.error(errorMessage(e, '操作失败')) }
  finally { busy.value = false }
}
</script>

<template>
  <section class="page">
    <div class="page-head">
      <div><p class="eyebrow">ADMIN / AUDIT</p><h1>审批与审计</h1><p class="muted">Agent 建议必须人工确认，所有分析和写入动作都保留记录。</p></div>
    </div>
    <div v-if="loadError" class="notice error">{{ loadError }} <button class="small" @click="load">重试</button></div>
    <div class="panel table-panel">
      <div class="panel-title"><span>待审批 Agent 动作</span><span class="muted">{{ actions.length }} 条</span></div>
      <div v-for="a in actions" :key="a.id" class="approval-row">
        <div><strong>#{{ a.ticketId }} · {{ a.actionType }}</strong><small>{{ JSON.stringify(a.payload) }}</small></div>
        <div><button class="approve" @click="decide(a.id, true)" :disabled="busy"><Check :size="15" />批准</button><button class="reject" @click="decide(a.id, false)" :disabled="busy"><X :size="15" />驳回</button></div>
      </div>
      <div v-if="!actions.length" class="muted">暂无待审批动作</div>
    </div>
    <div class="panel table-panel">
      <div class="panel-title">
        <span>审计日志</span><span class="muted">共 {{ auditTotal }} 条</span>
        <input v-model="actionFilter" placeholder="按动作名过滤，如 KNOWLEDGE" style="margin-left:auto;max-width:220px" @keyup.enter="filterAudits" />
        <button class="small" @click="filterAudits">过滤</button>
        <a class="small export-btn" title="导出 CSV（最多 10000 行，沿用当前过滤）" :href="`${apiBase}/audit/export${actionFilter ? '?action=' + encodeURIComponent(actionFilter) : ''}`"><Download :size="14" />导出</a>
      </div>
      <table>
        <thead><tr><th>时间</th><th>动作</th><th>资源</th><th>详情</th></tr></thead>
        <tbody>
          <tr v-for="a in audits" :key="a.id">
            <td>{{ a.created_at }}</td><td>{{ a.action }}</td><td>{{ a.resource_type }} #{{ a.resource_id }}</td><td><code>{{ JSON.stringify(a.detail) }}</code></td>
          </tr>
        </tbody>
      </table>
      <div class="pager">
        <button class="small" :disabled="auditPage <= 1" @click="gotoPage(auditPage - 1)">上一页</button>
        <span class="muted">{{ auditPage }} / {{ pageCount() }}</span>
        <button class="small" :disabled="auditPage >= pageCount()" @click="gotoPage(auditPage + 1)">下一页</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.pager { display: flex; align-items: center; gap: 10px; justify-content: center; margin-top: 14px; }
.export-btn { width: auto; padding: 5px 10px; display: inline-flex; align-items: center; gap: 5px; border: 1px solid #e2e8f0; border-radius: 6px; font-size: 11px; color: #64748b; text-decoration: none; }
.export-btn:hover { background: #f1f5f9; color: #2e68d7; }
</style>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import { useAuth } from '../stores/auth'
import { ElMessage } from 'element-plus'
import { ClipboardList, Activity, BookOpen, ShieldCheck, Send, Sparkles, Users, Check, RefreshCw, BarChart3, MessagesSquare } from 'lucide-vue-next'
import type { Ticket, DashboardStats } from '../lib/types'
import { statusLabel } from '../lib/types'
import SvgBarChart from '../components/SvgBarChart.vue'
import SvgLineChart from '../components/SvgLineChart.vue'

const router = useRouter()
const auth = useAuth()
const busy = ref(false)
const dashboard = ref<Record<string, any>>({})
const tickets = ref<Ticket[]>([])
const knowledge = ref<any[]>([])
const actions = ref<any[]>([])
const users = ref<any[]>([])
const stats = ref<DashboardStats | null>(null)

async function load() {
  if (!auth.user) return
  busy.value = true
  try {
    const [d, t, k] = await Promise.all([api.get('/dashboard'), api.get('/tickets', { params: { page: 1, size: 5 } }), api.get('/knowledge')])
    dashboard.value = d.data
    tickets.value = t.data.items
    knowledge.value = k.data
    if (auth.isAgent) actions.value = (await api.get('/agent/actions')).data
    if (auth.isAdmin) users.value = (await api.get('/admin/users')).data
    if (auth.isAdmin) stats.value = (await api.get('/dashboard/stats', { params: { days: 7 } })).data
  } catch (e: any) { ElMessage.error(e?.response?.data?.message || '数据加载失败') }
  finally { busy.value = false }
}

const statusBuckets = () => (stats.value?.status || []).map(b => ({ label: statusLabel[b.label] || b.label, count: b.count }))
const priorityBuckets = () => (stats.value?.priority || []).map(b => ({ label: b.label, count: b.count }))
const priorityColors: Record<string, string> = { P1: '#c5453a', P2: '#d48436', P3: '#3d73d8', P4: '#94a3b8' }

const approveBusy = ref(false)
async function approve(id: number) {
  if (approveBusy.value) return
  approveBusy.value = true
  try {
    await api.post(`/admin/users/${id}/approve`)
    ElMessage.success('账号已激活')
    await load()
  } catch (e: any) { ElMessage.error(errorMessage(e, '激活失败')) }
  finally { approveBusy.value = false }
}

onMounted(load)
</script>

<template>
  <section class="page">
    <div class="page-head">
      <div>
        <p class="eyebrow">{{ auth.isAdmin ? 'ADMINISTRATION' : 'WORKSPACE' }}</p>
        <h1>{{ auth.isAdmin ? '管理员控制台' : '服务台运行总览' }}</h1>
        <p class="muted">{{ auth.isAdmin ? '管理组织、处理队列、企业资料和全量审计。' : `当前部门：${auth.user?.departmentName || '未分配'}` }}</p>
      </div>
      <button v-if="!auth.isAdmin" class="primary" @click="router.push('/workspace/tickets')"><Send :size="16" />提交工单</button>
    </div>
    <div v-if="!auth.isAdmin" class="quick-start">
      <button class="panel quick-card" @click="router.push('/workspace/ask')"><MessagesSquare :size="23" /><strong>先找答案</strong><span>用一句话描述问题，从企业资料中找解决办法</span></button>
      <button class="panel quick-card" @click="router.push('/workspace/tickets')"><ClipboardList :size="23" /><strong>联系服务台</strong><span>提交新问题，查看处理进度，补充截图和信息</span></button>
      <button v-if="auth.isAgent" class="panel quick-card" @click="router.push('/workspace/agent')"><Activity :size="23" /><strong>开始处理</strong><span>查看分配给我的问题与即将到期的工单</span></button>
    </div>
    <div class="metrics">
      <div class="metric"><span>工单总量</span><strong>{{ dashboard.tickets ?? '-' }}</strong><ClipboardList :size="22" /></div>
      <div class="metric"><span>处理中</span><strong>{{ dashboard.openTickets ?? '-' }}</strong><Activity :size="22" /></div>
      <div class="metric"><span>已发布知识</span><strong>{{ dashboard.knowledgeArticles ?? '-' }}</strong><BookOpen :size="22" /></div>
      <div class="metric warn"><span>{{ auth.isAdmin ? '待审批动作' : 'SLA 超时' }}</span><strong>{{ auth.isAdmin ? actions.length : (dashboard.slaBreaches ?? '-') }}</strong><ShieldCheck :size="22" /></div>
    </div>
    <div class="grid-two">
      <div class="panel">
        <div class="panel-title">
          <span>{{ auth.isAdmin ? '待审核账号' : '最近工单' }}</span>
          <button class="link-button" @click="router.push(auth.isAdmin ? '/admin/organization' : '/workspace/tickets')">查看全部</button>
        </div>
        <template v-if="auth.isAdmin">
          <div v-for="u in users.filter((x: any) => x.account_status === 'PENDING_APPROVAL').slice(0, 5)" :key="u.id" class="ticket-row">
            <div class="ticket-icon"><Users :size="17" /></div>
            <div class="grow"><strong>{{ u.display_name }} · {{ u.username }}</strong><small>{{ u.department || '未分配部门' }} · 待审核</small></div>
            <button class="approve" :disabled="approveBusy" @click="approve(u.id)"><Check :size="14" />激活</button>
          </div>
        </template>
        <template v-else>
          <p v-if="!tickets.length && !busy" class="muted">暂无工单。可以先问知识库，或联系服务台。</p>
          <div v-for="t in tickets.slice(0, 5)" :key="t.id" class="ticket-row">
            <div class="ticket-icon"><ClipboardList :size="17" /></div>
            <div class="grow"><button class="ticket-title" @click="router.push({ path: auth.isAgent ? '/workspace/agent' : '/workspace/tickets', query: { ticket: t.id } })">#{{ t.id }} {{ t.title }}</button><small>{{ t.queueName || t.category }} · {{ statusLabel[t.status] || t.status }}</small></div>
            <span :class="['priority', t.priority.toLowerCase()]">{{ t.priority }}</span>
          </div>
        </template>
      </div>
      <div class="panel accent">
        <div class="panel-title"><span>{{ auth.isAdmin ? '系统状态' : '智能处理' }}</span><Sparkles :size="18" /></div>
        <p>{{ auth.isAdmin ? 'LLM 模型通过环境变量接入，所有 Agent 运行和管理动作都写入审计。' : 'AI 模型会结合你有权限访问的知识片段分析工单；重要写入动作仍需处理人确认。' }}</p>
      </div>
    </div>
    <template v-if="auth.isAdmin && stats">
      <div class="panel-title stats-head"><span><BarChart3 :size="16" /> 数据统计（近 {{ stats.days }} 天趋势 / 全量分布）</span></div>
      <div class="metrics stats-metrics">
        <div class="metric"><span>SLA 达成率</span><strong>{{ stats.sla.rate === null ? '—' : stats.sla.rate + '%' }}</strong><small class="metric-sub">按期 {{ stats.sla.onTime }} · 超时 {{ stats.sla.overdue }}</small></div>
        <div class="metric"><span>问答会话 / 消息</span><strong>{{ stats.qa.conversations }} <em class="metric-divider">/</em> {{ stats.qa.messages }}</strong><small class="metric-sub">累计问答规模</small></div>
        <div class="metric"><span>问答反馈</span><strong :class="{ 'metric-warn-text': stats.qa.feedbackDown > stats.qa.feedbackUp }">{{ stats.qa.feedbackUp }} <em class="metric-divider">/</em> {{ stats.qa.feedbackDown }}</strong><small class="metric-sub">赞 / 踩</small></div>
        <div class="metric"><span>引用点击率</span><strong>{{ stats.qa.citationsTotal === 0 ? '—' : Math.round((stats.qa.citationsClicked / stats.qa.citationsTotal) * 100) + '%' }}</strong><small class="metric-sub">点击 {{ stats.qa.citationsClicked }} / 引用 {{ stats.qa.citationsTotal }}</small></div>
      </div>
      <div class="grid-two stats-grid">
        <div class="panel">
          <div class="panel-title"><span>近 {{ stats.days }} 天新建工单趋势</span></div>
          <SvgLineChart :points="stats.dailyCreated" />
        </div>
        <div class="panel">
          <div class="panel-title"><span>工单状态 / 优先级分布</span></div>
          <SvgBarChart :items="statusBuckets()" />
          <div style="height: 14px" />
          <SvgBarChart :items="priorityBuckets()" :colors="priorityColors" />
        </div>
      </div>
    </template>
    <button class="icon-btn" style="margin-top: 14px" title="刷新" @click="load"><RefreshCw :class="{ spin: busy }" :size="17" /></button>
  </section>
</template>

<style scoped>
.quick-start { display:flex; flex-wrap:wrap; gap:14px; margin-bottom:22px; }
.quick-card { flex:1 1 220px; display:grid; gap:10px; text-align:left; cursor:pointer; color:#235dcc; }
.quick-card:hover { border-color:#6191e9; background:#f8faff; }
.quick-card strong { font-size:17px; color:#27344a; }
.quick-card span { color:#68758a; font-size:13px; line-height:1.6; }
</style>

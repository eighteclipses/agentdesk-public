<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, LoaderCircle, RefreshCw, X } from 'lucide-vue-next'
import { api, errorMessage } from '../api'
import type { AgentAction } from '../lib/types'

const props = withDefaults(defineProps<{
  title?: string
  description?: string
}>(), {
  title: '待审批 Agent 动作',
  description: 'Agent 建议只会在人工确认后执行。请核对工单和动作内容，再批准或驳回。',
})

const actions = ref<AgentAction[]>([])
const loading = ref(false)
const loadError = ref('')
const busyId = ref<number | null>(null)
const emit = defineEmits<{ decided: [] }>()

function actionLabel(action: AgentAction) {
  if (action.actionType === 'TRANSFER') return '分流到指定队列'
  if (action.actionType === 'CLOSE') return '关闭工单'
  return action.actionType
}

function actionDetail(action: AgentAction) {
  const payload = action.payload || {}
  if (action.actionType === 'TRANSFER') {
    const queue = payload.queueCode || payload.queueName
    return queue ? `目标队列：${String(queue)}` : '请核对分流目标队列'
  }
  if (action.actionType === 'CLOSE') return `关闭原因：${String(payload.reason || 'Agent 建议关闭')}`
  return Object.keys(payload).length ? JSON.stringify(payload) : '无附加说明'
}

function createdAt(action: AgentAction) {
  return action.createdAt ? new Date(action.createdAt).toLocaleString('zh-CN', { hour12: false }) : '时间未知'
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const response = await api.get('/agent/actions')
    actions.value = Array.isArray(response.data) ? response.data : []
  } catch (e: any) {
    loadError.value = errorMessage(e, '审批列表加载失败')
  } finally {
    loading.value = false
  }
}

async function decide(action: AgentAction, approve: boolean) {
  if (busyId.value !== null) return
  const verb = approve ? '批准' : '驳回'
  const detail = approve ? '批准后该 Agent 动作会立即执行。' : '驳回后该 Agent 动作不会执行。'
  try {
    await ElMessageBox.confirm(`${verb}“${actionLabel(action)}”（工单 #${action.ticketId}）？${detail}`, '人工确认', {
      confirmButtonText: verb,
      cancelButtonText: '取消',
      type: approve ? 'warning' : 'info',
    })
  } catch {
    return
  }

  busyId.value = action.id
  try {
    await api.post(`/agent/actions/${action.id}/${approve ? 'approve' : 'reject'}`)
    emit('decided')
    await load()
    ElMessage.success(approve ? '已批准，动作已执行' : '已驳回，动作未执行')
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '审批操作失败，请稍后重试'))
  } finally {
    busyId.value = null
  }
}

defineExpose({ load })
onMounted(load)
</script>

<template>
  <div class="approval-queue">
    <div class="approval-queue-head">
      <div>
        <h2>{{ props.title }}</h2>
        <p class="muted">{{ props.description }}</p>
      </div>
      <button class="secondary" :disabled="loading" @click="load">
        <RefreshCw :class="{ spin: loading }" :size="15" />刷新
      </button>
    </div>

    <div v-if="loadError" class="notice error" role="alert">
      {{ loadError }} <button class="small" @click="load">重试</button>
    </div>

    <div v-if="loading && !actions.length" class="approval-empty" aria-live="polite">
      <LoaderCircle class="spin" :size="18" />正在加载待审批动作…
    </div>
    <div v-else-if="!actions.length && !loadError" class="approval-empty">
      <strong>暂无待审批动作</strong>
      <p>当前没有需要你人工确认的 Agent 建议。</p>
    </div>
    <div v-else class="approval-list">
      <article v-for="action in actions" :key="action.id" class="approval-item">
        <div class="approval-item-content">
          <div class="approval-item-meta">
            <strong>{{ actionLabel(action) }}</strong>
            <span>工单 #{{ action.ticketId }}</span>
            <RouterLink :to="`/workspace/agent?ticket=${action.ticketId}`">查看工单</RouterLink>
            <span class="approval-status">待人工确认</span>
          </div>
          <p>{{ actionDetail(action) }}</p>
          <small>提交于 {{ createdAt(action) }}</small>
        </div>
        <div class="approval-item-actions">
          <button class="approve" :disabled="busyId !== null" @click="decide(action, true)">
            <LoaderCircle v-if="busyId === action.id" class="spin" :size="14" />
            <Check v-else :size="14" />批准
          </button>
          <button class="reject" :disabled="busyId !== null" @click="decide(action, false)">
            <X :size="14" />驳回
          </button>
        </div>
      </article>
    </div>
  </div>
</template>

<style scoped>
.approval-queue-head { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; margin-bottom:14px; }
.approval-queue-head h2 { margin:0 0 6px; font-size:16px; }
.approval-queue-head p { margin:0; line-height:1.6; }
.approval-list { display:grid; gap:0; }
.approval-item { display:flex; align-items:center; justify-content:space-between; gap:20px; padding:14px 0; border-top:1px solid #eef1f5; }
.approval-item-content { min-width:0; }
.approval-item-meta { display:flex; align-items:center; flex-wrap:wrap; gap:8px; color:#536176; font-size:12px; }
.approval-item-meta strong { color:#25334a; font-size:13px; }
.approval-status { color:#9b630d; background:#fff5d9; border-radius:4px; padding:3px 6px; font-size:10px; }
.approval-item p { margin:7px 0 4px; color:#4e5b70; font-size:12px; line-height:1.6; overflow-wrap:anywhere; }
.approval-item small { color:#8c98aa; font-size:11px; }
.approval-item-actions { display:flex; flex:none; gap:5px; }
.approval-item-actions .approve, .approval-item-actions .reject { margin-left:0; }
.approval-empty { display:grid; place-items:center; gap:6px; padding:32px 16px 20px; color:#8994a7; font-size:13px; text-align:center; }
.approval-empty strong { color:#536176; font-size:14px; }
.approval-empty p { margin:0; }
@media (max-width: 640px) {
  .approval-item { align-items:flex-start; flex-direction:column; gap:10px; }
  .approval-item-actions { width:100%; }
  .approval-item-actions button { flex:1; justify-content:center; }
}
</style>

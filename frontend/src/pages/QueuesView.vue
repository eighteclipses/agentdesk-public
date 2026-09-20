<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, errorMessage } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Settings2, Users, Plus, ChevronDown, ChevronRight, UserMinus, Power } from 'lucide-vue-next'

const queues = ref<any[]>([])
const users = ref<any[]>([])
const memberUserId = ref<Record<number, string>>({})
const loadError = ref('')
const busy = ref(false)
const creating = ref(false)
const queueForm = ref({ code: '', name: '', description: '' })
/** 展开查看成员的队列：{ [queueId]: members[] }，未展开则不存在该 key */
const expanded = ref<Record<number, any[]>>({})

async function load() {
  loadError.value = ''
  try {
    const [q, u] = await Promise.all([api.get('/admin/queues'), api.get('/admin/users')])
    queues.value = q.data
    users.value = u.data
    // 已展开的队列同步刷新成员
    for (const id of Object.keys(expanded.value)) await refreshMembers(Number(id))
  } catch (e: any) { loadError.value = errorMessage(e, '队列数据加载失败') }
}
onMounted(load)

async function addQueueMember(queueId: number) {
  const id = Number(memberUserId.value[queueId])
  if (!id) return ElMessage.warning('请先选择处理人')
  if (busy.value) return
  busy.value = true
  try {
    await api.post(`/admin/queues/${queueId}/members`, { userId: id })
    memberUserId.value = { ...memberUserId.value, [queueId]: '' }
    ElMessage.success('队列成员已添加')
    await load()
  } catch (e: any) { ElMessage.error(errorMessage(e, '添加成员失败')) }
  finally { busy.value = false }
}

async function createQueue() {
  const form = queueForm.value
  if (!form.code.trim() || !form.name.trim()) return ElMessage.warning('请填写队列代码与名称')
  if (creating.value) return
  creating.value = true
  try {
    await api.post('/admin/queues', { code: form.code.trim().toUpperCase(), name: form.name.trim(), description: form.description.trim(), active: true })
    queueForm.value = { code: '', name: '', description: '' }
    ElMessage.success('队列已创建')
    await load()
  } catch (e: any) { ElMessage.error(errorMessage(e, '队列创建失败')) }
  finally { creating.value = false }
}

async function refreshMembers(queueId: number) {
  try {
    const members = (await api.get(`/admin/queues/${queueId}/members`)).data
    expanded.value = { ...expanded.value, [queueId]: members }
  } catch (e: any) { ElMessage.error(errorMessage(e, '成员列表加载失败')) }
}

async function toggleMembers(queueId: number) {
  if (queueId in expanded.value) {
    const next = { ...expanded.value }
    delete next[queueId]
    expanded.value = next
    return
  }
  await refreshMembers(queueId)
}

async function removeMember(queueId: number, member: any) {
  const confirmed = await ElMessageBox.confirm(`把「${member.display_name || member.username}」移出队列？移出后该账号将无法看到此队列的工单。`, '移除成员', { type: 'warning' }).catch(() => null)
  if (!confirmed) return
  if (busy.value) return
  busy.value = true
  try {
    await api.delete(`/admin/queues/${queueId}/members/${member.id}`)
    ElMessage.success('成员已移除')
    await refreshMembers(queueId)
    await load()
  } catch (e: any) { ElMessage.error(errorMessage(e, '移除成员失败')) }
  finally { busy.value = false }
}

/** 启用/停用队列：停用后新工单不再允许分流到该队列（TRANSFER 校验依赖 active） */
async function toggleActive(q: any) {
  if (busy.value) return
  busy.value = true
  try {
    await api.patch(`/admin/queues/${q.id}`, { active: q.active === false })
    ElMessage.success(q.active === false ? '队列已启用' : '队列已停用')
    await load()
  } catch (e: any) { ElMessage.error(errorMessage(e, '队列状态更新失败')) }
  finally { busy.value = false }
}
</script>

<template>
  <section class="page">
    <div class="page-head">
      <div><p class="eyebrow">ADMIN / QUEUES</p><h1>处理队列</h1><p class="muted">按网络、权限、软件和通用服务分流，队列成员决定处理范围。</p></div>
    </div>
    <div v-if="loadError" class="notice error">{{ loadError }} <button class="small" @click="load">重试</button></div>

    <div class="panel" style="margin-bottom: 18px">
      <div class="panel-title"><span>新建队列</span><Plus :size="16" /></div>
      <div class="form-grid compact">
        <input v-model="queueForm.code" placeholder="队列代码（大写），如 NETWORK" />
        <input v-model="queueForm.name" placeholder="队列名称，如 网络服务队列" />
        <input v-model="queueForm.description" placeholder="队列职责描述（可选）" />
      </div>
      <button class="primary" :disabled="creating" @click="createQueue">{{ creating ? '创建中…' : '创建队列' }}</button>
    </div>

    <div class="knowledge-grid">
      <article v-for="q in queues" :key="q.id" class="knowledge-card">
        <div class="card-icon"><Settings2 :size="18" /></div>
        <div style="flex: 1">
          <span class="tag">{{ q.code }}</span>
          <span v-if="q.active === false" class="tag disabled-tag">已停用</span>
          <h3>{{ q.name }}</h3>
          <p>{{ q.description }} · {{ q.member_count }} 名处理人</p>
          <div class="inline-form">
            <select v-model="memberUserId[q.id]">
              <option value="">添加处理人</option>
              <option v-for="u in users.filter((x: any) => x.role === 'AGENT' && x.active)" :key="u.id" :value="String(u.id)">{{ u.display_name }}</option>
            </select>
            <button class="small" title="添加成员" :disabled="busy" @click="addQueueMember(q.id)"><Users :size="15" /></button>
            <button class="small" :title="q.id in expanded ? '收起成员' : '查看成员'" :disabled="busy" @click="toggleMembers(q.id)">
              <ChevronDown v-if="q.id in expanded" :size="15" />
              <ChevronRight v-else :size="15" />
            </button>
            <button class="small" :title="q.active === false ? '启用队列' : '停用队列'" :disabled="busy" @click="toggleActive(q)"><Power :size="14" /></button>
          </div>
          <div v-if="q.id in expanded" class="member-list">
            <div v-if="!expanded[q.id].length" class="muted">暂无成员</div>
            <div v-for="m in expanded[q.id]" :key="m.id" class="member-row">
              <strong>{{ m.display_name }}</strong><small>{{ m.username }}</small>
              <button class="small danger" title="移出队列" :disabled="busy" @click="removeMember(q.id, m)"><UserMinus :size="14" /></button>
            </div>
          </div>
        </div>
      </article>
    </div>
    <div v-if="!queues.length && !loadError" class="muted" style="padding: 30px; text-align: center">还没有队列，先在上方创建一个</div>
  </section>
</template>

<style scoped>
.member-list { margin-top: 10px; border-top: 1px solid #eef1f5; padding-top: 8px; display: grid; gap: 4px; }
.member-row { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #4e5b70; }
.member-row strong { font-size: 12px; }
.member-row small { flex: 1; color: #8c98aa; }
.small.danger:hover { color: #e11d48; background: #fff0ef; }
.disabled-tag { color: #8c98aa; background: #f0f2f5; margin-left: 5px; }
</style>

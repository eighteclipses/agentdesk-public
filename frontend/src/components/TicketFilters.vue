<script setup lang="ts">
import { statusLabel } from '../lib/types'
const model = defineModel<{ q: string; status: string; priority: string; view: string; sort: string }>({ required: true })
defineProps<{ agent?: boolean; loading?: boolean }>()
const emit = defineEmits<{ search: [] }>()
function changeStatus() {
  if (['RESOLVED', 'CLOSED'].includes(model.value.status)) model.value.view = ''
  emit('search')
}
function reset() {
  model.value = { q: '', status: '', priority: '', view: '', sort: 'updated' }
  emit('search')
}
</script>
<template>
  <form class="ticket-filters" @submit.prevent="$emit('search')">
    <input v-model="model.q" maxlength="200" aria-label="搜索工单" placeholder="搜索标题、描述或 #工单号" />
    <select v-model="model.view" aria-label="工单范围" @change="$emit('search')">
      <option value="">全部工单</option><option value="open">未解决</option>
      <option v-if="agent" value="mine">分配给我</option><option v-if="agent" value="unassigned">待指派</option><option value="overdue">已超时</option>
    </select>
    <select v-model="model.status" aria-label="工单状态" @change="changeStatus"><option value="">全部状态</option><option v-for="(label,key) in statusLabel" :key="key" :value="key">{{ label }}</option></select>
    <select v-model="model.priority" aria-label="优先级筛选" @change="$emit('search')"><option value="">全部优先级</option><option value="P1">P1 紧急</option><option value="P2">P2 高</option><option value="P3">P3 普通</option><option value="P4">P4 低</option></select>
    <select v-model="model.sort" aria-label="工单排序" @change="$emit('search')"><option value="updated">最近更新</option><option value="created">最新创建</option><option value="due">最早到期</option></select>
    <button class="primary" :disabled="loading">{{ loading ? '加载中…' : '搜索' }}</button>
    <button type="button" class="secondary" :disabled="loading" @click="reset">清除筛选</button>
  </form>
</template>
<style scoped>
.ticket-filters { display:flex; gap:8px; flex-wrap:wrap; margin-bottom:16px; }
input { flex:1 1 230px; min-width:0; } select { max-width:150px; font-size:13px; }
</style>

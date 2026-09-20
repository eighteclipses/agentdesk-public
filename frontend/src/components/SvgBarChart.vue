<script setup lang="ts">
import { computed } from 'vue'
import type { StatBucket } from '../lib/types'

const props = defineProps<{ items: StatBucket[]; color?: string; colors?: Record<string, string> }>()

const max = computed(() => Math.max(1, ...props.items.map(i => i.count)))

function fill(label: string) { return props.colors?.[label] || props.color || '#3d73d8' }
</script>

<template>
  <div class="bar-chart">
    <div v-if="items.length === 0" class="chart-empty">暂无数据</div>
    <div v-for="item in items" :key="item.label" class="bar-row">
      <span class="bar-label">{{ item.label }}</span>
      <div class="bar-track">
        <div class="bar-fill" :style="{ width: (item.count / max) * 100 + '%', background: fill(item.label) }" />
      </div>
      <span class="bar-value">{{ item.count }}</span>
    </div>
  </div>
</template>

<style scoped>
.bar-chart { display: grid; gap: 9px; }
.bar-row { display: grid; grid-template-columns: 76px 1fr 40px; align-items: center; gap: 9px; }
.bar-label { font-size: 12px; color: #5c6a80; text-align: right; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.bar-track { background: #eef2f8; border-radius: 4px; height: 16px; overflow: hidden; }
.bar-fill { height: 100%; border-radius: 4px; min-width: 2px; transition: width .4s ease; }
.bar-value { font-size: 12px; font-weight: 650; color: #33415c; }
.chart-empty { color: #a2acbb; font-size: 12px; text-align: center; padding: 18px 0; }
</style>

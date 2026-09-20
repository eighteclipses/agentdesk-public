<script setup lang="ts">
import { computed } from 'vue'
import type { DayPoint } from '../lib/types'

const props = defineProps<{ points: DayPoint[] }>()

const W = 560
const H = 190
const PAD = { left: 34, right: 12, top: 14, bottom: 26 }

const maxCount = computed(() => Math.max(1, ...props.points.map(p => p.count)))

const coords = computed(() => {
  const n = props.points.length
  const innerW = W - PAD.left - PAD.right
  const innerH = H - PAD.top - PAD.bottom
  return props.points.map((p, i) => ({
    x: n === 1 ? PAD.left + innerW / 2 : PAD.left + (i * innerW) / (n - 1),
    y: PAD.top + innerH - (p.count / maxCount.value) * innerH,
    ...p,
  }))
})

const linePath = computed(() => coords.value.map((c, i) => `${i === 0 ? 'M' : 'L'}${c.x.toFixed(1)},${c.y.toFixed(1)}`).join(' '))
const areaPath = computed(() => {
  if (coords.value.length === 0) return ''
  const first = coords.value[0]
  const last = coords.value[coords.value.length - 1]
  const base = H - PAD.bottom
  return `${linePath.value} L${last.x.toFixed(1)},${base} L${first.x.toFixed(1)},${base} Z`
})

const yTicks = computed(() => {
  const step = maxCount.value <= 4 ? 1 : Math.ceil(maxCount.value / 4)
  const ticks: { y: number; value: number }[] = []
  for (let v = 0; v <= maxCount.value; v += step) {
    ticks.push({ value: v, y: PAD.top + (H - PAD.top - PAD.bottom) - (v / maxCount.value) * (H - PAD.top - PAD.bottom) })
  }
  return ticks
})

const labelEvery = computed(() => Math.ceil(props.points.length / 8))
</script>

<template>
  <svg :viewBox="`0 0 ${W} ${H}`" class="line-chart" role="img" aria-label="每日新建工单趋势">
    <g v-for="t in yTicks" :key="t.value">
      <line :x1="PAD.left" :x2="W - PAD.right" :y1="t.y" :y2="t.y" stroke="#e7ebf2" stroke-width="1" />
      <text :x="PAD.left - 6" :y="t.y + 3.5" text-anchor="end" class="tick">{{ t.value }}</text>
    </g>
    <path :d="areaPath" fill="rgba(61,115,216,.12)" />
    <path :d="linePath" fill="none" stroke="#3d73d8" stroke-width="2" stroke-linejoin="round" stroke-linecap="round" />
    <g v-for="(c, i) in coords" :key="i">
      <circle :cx="c.x" :cy="c.y" r="3" fill="#fff" stroke="#3d73d8" stroke-width="2">
        <title>{{ c.date }}：新建 {{ c.count }}</title>
      </circle>
      <text v-if="i % labelEvery === 0" :x="c.x" :y="H - 8" text-anchor="middle" class="tick">{{ c.date }}</text>
    </g>
  </svg>
</template>

<style scoped>
.line-chart { width: 100%; height: auto; display: block; }
.tick { font-size: 10px; fill: #8a94a6; }
</style>

<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import * as pdfjsLib from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import { ZoomIn, ZoomOut } from 'lucide-vue-next'

const props = defineProps<{ url: string; page?: number | null }>()
const emit = defineEmits<{ (e: 'ready', pageCount: number): void }>()
const wrap = ref<HTMLElement | null>(null)
const error = ref('')
const current = ref(1)
const scale = ref(1.3)
let pdf: pdfjsLib.PDFDocumentProxy | null = null
let cancelled = false
let observer: IntersectionObserver | null = null
const rendered = new Set<number>()

pdfjsLib.GlobalWorkerOptions.workerSrc = workerUrl

// 每页先放占位（按首页尺寸估算高度），滚入视口时才真正渲染，避免大文档一次性卡死页面
async function render() {
  error.value = ''
  try {
    const bytes = await (await fetch(props.url, { credentials: 'include' })).arrayBuffer()
    if (cancelled) return
    pdf = await pdfjsLib.getDocument({ data: bytes }).promise
    emit('ready', pdf.numPages)
    const container = wrap.value
    if (!container) return
    container.innerHTML = ''
    rendered.clear()
    observer?.disconnect()
    observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        const holder = entry.target as HTMLElement
        const p = Number(holder.dataset.page)
        if (entry.isIntersecting && !rendered.has(p)) {
          rendered.add(p)
          observer?.unobserve(holder)
          renderPage(p, holder)
        }
      }
    }, { root: container, rootMargin: '600px 0px' })
    const first = await pdf.getPage(1)
    const baseHeight = first.getViewport({ scale: 1 }).height
    for (let p = 1; p <= pdf.numPages; p++) {
      const holder = document.createElement('div')
      holder.className = 'pdf-page pdf-placeholder'
      holder.dataset.page = String(p)
      holder.style.minHeight = `${Math.round(baseHeight * scale.value)}px`
      container.appendChild(holder)
      observer.observe(holder)
    }
    // 立即渲染前两页，减少首屏空白
    await renderPage(1, container.querySelector('[data-page="1"]') as HTMLElement)
    if (pdf.numPages > 1 && !cancelled) await renderPage(2, container.querySelector('[data-page="2"]') as HTMLElement)
    if (props.page && props.page > 2) jumpTo(props.page, false)
  } catch (e: any) {
    error.value = 'PDF 渲染失败：' + (e?.message || e)
  }
}

async function renderPage(p: number, holder: HTMLElement | null) {
  if (!pdf || cancelled || !holder) return
  holder.classList.remove('pdf-placeholder')
  holder.innerHTML = ''
  holder.style.minHeight = ''
  const page = await pdf.getPage(p)
  if (cancelled) return
  const viewport = page.getViewport({ scale: scale.value })
  const canvas = document.createElement('canvas')
  canvas.width = viewport.width
  canvas.height = viewport.height
  canvas.dataset.page = String(p)
  const label = document.createElement('div')
  label.className = 'pdf-page-label'
  label.textContent = `第 ${p} 页`
  holder.appendChild(canvas)
  holder.appendChild(label)
  await page.render({ canvas, viewport }).promise
}

function jumpTo(p: number, highlight = true) {
  const holder = wrap.value?.querySelector<HTMLElement>(`[data-page="${p}"]`)
  if (!holder) return
  if (!rendered.has(p)) {
    rendered.add(p)
    observer?.unobserve(holder)
    renderPage(p, holder)
  }
  holder.scrollIntoView({ behavior: highlight ? 'smooth' : 'auto', block: 'start' })
  if (highlight) {
    holder.classList.add('highlight')
    setTimeout(() => holder.classList.remove('highlight'), 1800)
  }
  current.value = p
}

async function changeScale(delta: number) {
  const next = Math.min(2.5, Math.max(0.6, +(scale.value + delta).toFixed(2)))
  if (next === scale.value) return
  scale.value = next
  rendered.clear()
  const container = wrap.value
  if (!container || !pdf) return
  for (const holder of Array.from(container.querySelectorAll<HTMLElement>('.pdf-page'))) {
    holder.innerHTML = ''
    holder.classList.add('pdf-placeholder')
    const p = Number(holder.dataset.page)
    const page = await pdf.getPage(p)
    holder.style.minHeight = `${Math.round(page.getViewport({ scale: 1 }).height * next)}px`
    observer?.observe(holder)
  }
  const first = container.querySelector<HTMLElement>('[data-page="1"]')
  if (first) { rendered.add(1); observer?.unobserve(first); renderPage(1, first) }
}

let lastPageProp = props.page ?? null
watch(() => props.page, (p) => { if (p && p !== lastPageProp) { lastPageProp = p; jumpTo(p) } })

onMounted(render)
onUnmounted(() => { cancelled = true; observer?.disconnect() })
</script>

<template>
  <div class="pdf-viewer">
    <div class="pdf-toolbar">
      <span class="muted">第 {{ current }} 页 · 缩放 {{ Math.round(scale * 100) }}%</span>
      <span class="spacer" />
      <button class="small tool" title="缩小" @click="changeScale(-0.2)"><ZoomOut :size="14" /></button>
      <button class="small tool" title="放大" @click="changeScale(0.2)"><ZoomIn :size="14" /></button>
    </div>
    <div v-if="error" class="notice error">{{ error }}（可改用"下载原件"查看）</div>
    <div ref="wrap" class="pdf-pages"></div>
  </div>
</template>

<style scoped>
.pdf-viewer { background: #525659; border-radius: 8px; padding: 14px; max-height: 78vh; overflow: auto; position: relative; }
.pdf-toolbar { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; gap: 6px; background: #525659; padding-bottom: 8px; }
.pdf-toolbar .muted { color: #c8cdd4; font-size: 11px; }
.pdf-toolbar .spacer { flex: 1; }
.pdf-toolbar .tool { background: rgba(255, 255, 255, .12); border-color: transparent; color: #fff; }
.pdf-toolbar .tool:hover { background: rgba(255, 255, 255, .22); }
.pdf-pages { display: flex; flex-direction: column; align-items: center; gap: 12px; }
.pdf-page { box-shadow: 0 3px 14px rgba(0, 0, 0, .35); background: #fff; border-radius: 3px; overflow: hidden; position: relative; scroll-margin-top: 40px; width: max-content; max-width: 100%; }
.pdf-page.pdf-placeholder { background: #6b7075; }
.pdf-page canvas { display: block; max-width: 100%; height: auto; }
.pdf-page.highlight { outline: 4px solid #f59e0b; }
.pdf-page-label { position: absolute; right: 6px; top: 4px; background: rgba(37, 99, 235, .85); color: #fff; font-size: 10px; padding: 2px 7px; border-radius: 10px; }
</style>

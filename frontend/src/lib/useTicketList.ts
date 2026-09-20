import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import type { Ticket } from './types'

export function useTicketList(defaultView = '') {
  const route = useRoute()
  const router = useRouter()
  const tickets = ref<Ticket[]>([])
  const total = ref(0)
  const page = ref(1)
  const size = 20
  const filters = ref({ q: '', status: '', priority: '', view: defaultView, sort: 'updated' })
  const loading = ref(false)
  const loadError = ref('')
  const selected = ref<Ticket | null>(null)
  const detailError = ref('')
  let listRequest = 0
  let detailRequest = 0
  const pages = computed(() => Math.max(1, Math.ceil(total.value / size)))

  async function load() {
    const request = ++listRequest
    loading.value = true
    loadError.value = ''
    try {
      const { data } = await api.get('/tickets', { params: { page: page.value, size, ...filters.value } })
      if (request !== listRequest) return
      tickets.value = data.items
      total.value = data.total
      if (page.value > pages.value) { page.value = pages.value; await load() }
    } catch (e) { if (request === listRequest) loadError.value = errorMessage(e, '工单加载失败') }
    finally { if (request === listRequest) loading.value = false }
  }

  async function loadSelected() {
    const request = ++detailRequest
    const id = Number(route.query.ticket)
    detailError.value = ''
    if (!Number.isSafeInteger(id) || id < 1) { selected.value = null; return }
    try {
      const { data } = await api.get(`/tickets/${id}`)
      if (request === detailRequest) selected.value = data
    } catch (e) {
      if (request === detailRequest) { selected.value = null; detailError.value = errorMessage(e, '无法打开工单') }
    }
  }
  function openTicket(t: Ticket) {
    return router.push({ query: { ...route.query, ticket: t.id } })
  }
  function closeTicket() {
    const { ticket, ...query } = route.query
    return router.replace({ query })
  }
  function search() { page.value = 1; return load() }
  function gotoPage(p: number) { page.value = p; return load() }
  async function changed() { await Promise.all([load(), loadSelected()]) }
  watch(() => route.query.ticket, () => { selected.value = null; void loadSelected() }, { immediate: true })
  onUnmounted(() => { ++listRequest; ++detailRequest })
  return { tickets, total, page, pages, filters, loading, loadError, selected, detailError, load, openTicket, closeTicket, search, gotoPage, changed }
}

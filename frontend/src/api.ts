import axios from 'axios'

/** 统一 API 前缀：fetch/EventSource/下载链接等不走 axios 的场景也要用它，避免与 baseURL 不一致 */
export const apiBase = import.meta.env.VITE_API_BASE || '/api'

export const api = axios.create({ baseURL: apiBase, timeout: 30000, withCredentials: true })

let redirecting = false

// 会话过期（401）统一跳回登录；429 等带服务端消息的错误透传给调用方，其余做全局兜底提示
api.interceptors.response.use(
  (res) => res,
  (error) => {
    const status = error?.response?.status
    if (status === 401 && !redirecting && !window.location.pathname.startsWith('/login')) {
      redirecting = true
      window.location.assign('/login?redirect=' + encodeURIComponent(window.location.pathname + window.location.search))
    }
    return Promise.reject(error)
  },
)

/** 统一取服务端错误消息（后端 ResponseStatusException 的 message 字段） */
export function errorMessage(e: any, fallback: string): string {
  return e?.response?.data?.message || e?.response?.data?.detail || e?.message || fallback
}

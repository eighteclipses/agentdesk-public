/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 业务 API 前缀，默认 /api（与 vite dev 代理、nginx 反代保持一致） */
  readonly VITE_API_BASE?: string
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

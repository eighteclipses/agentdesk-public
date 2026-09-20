<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { Home, SearchX } from 'lucide-vue-next'

const router = useRouter()
const auth = useAuth()
function goHome() {
  router.push(auth.isAdmin ? '/admin' : '/workspace')
}
</script>

<template>
  <section class="page notfound">
    <SearchX :size="44" />
    <h1>404</h1>
    <p class="muted">页面不存在，或链接已失效。</p>
    <button class="primary" @click="goHome"><Home :size="15" />返回工作台</button>
  </section>
</template>

<style scoped>
.notfound { text-align: center; padding-top: 12vh; }
.notfound svg { color: #a2acbb; margin-bottom: 14px; }
.notfound h1 { font-size: 42px; margin: 0 0 8px; color: #334155; }
.notfound p { margin: 0 0 22px; }
.notfound button { margin: 0 auto; }
</style>

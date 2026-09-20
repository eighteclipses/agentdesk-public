<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { useAuth } from '../stores/auth'
import { Bot } from 'lucide-vue-next'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const busy = ref(false)
const error = ref('')
const notice = ref('')
const departments = ref<{ id: number; name: string }[]>([])
const loginForm = ref({ username: '', password: '' })
const registerForm = ref({ username: '', displayName: '', password: '', departmentId: '' })
const passwordForm = ref({ currentPassword: '', newPassword: '' })
const mode = () => route.path
function destination() {
  const path = typeof route.query.redirect === 'string' ? route.query.redirect : ''
  return path.startsWith('/') && !path.startsWith('//') && !/^\/(login|register|change-password)/.test(path) ? path : auth.user?.role === 'ADMIN' ? '/admin' : '/workspace'
}

onMounted(async () => {
  try { departments.value = (await api.get('/auth/departments')).data } catch { /* 忽略 */ }
})

async function login() {
  error.value = ''; busy.value = true
  try {
    await auth.login(loginForm.value.username, loginForm.value.password)
    const u = auth.user!
    if (u.forcePasswordChange) await router.push({ path: '/change-password', query: route.query })
    else await router.push(destination())
  } catch (e: any) { error.value = e?.response?.data?.message || '登录失败' }
  finally { busy.value = false }
}

async function register() {
  error.value = ''
  if (!registerForm.value.username.trim() || !registerForm.value.displayName.trim() || !registerForm.value.password) { error.value = '请完整填写注册信息'; return }
  if (registerForm.value.password.length < 8) { error.value = '密码至少 8 位'; return }
  if (!registerForm.value.departmentId) { error.value = '请选择所属部门'; return }
  busy.value = true
  try {
    const r = await api.post('/auth/register', {
      username: registerForm.value.username.trim(),
      displayName: registerForm.value.displayName.trim(),
      password: registerForm.value.password,
      departmentId: Number(registerForm.value.departmentId),
    })
    notice.value = r.data.message
    await router.push('/login')
  } catch (e: any) { error.value = e?.response?.data?.message || '注册失败' }
  finally { busy.value = false }
}

async function changePassword() {
  error.value = ''; busy.value = true
  try {
    await auth.changePassword(passwordForm.value.currentPassword, passwordForm.value.newPassword)
    await router.push(destination())
  } catch (e: any) { error.value = e?.response?.data?.message || '密码修改失败' }
  finally { busy.value = false }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="brand large">
        <div class="brand-mark"><Bot :size="25" /></div>
        <div><strong>AgentDesk</strong><span>企业智能服务台</span></div>
      </div>
      <h1>{{ mode() === '/register' ? '创建员工账号' : mode() === '/change-password' ? '首次登录修改密码' : '登录企业服务台' }}</h1>
      <p class="muted">{{ mode() === '/register' ? '注册后由管理员审核部门和账号状态。' : mode() === '/change-password' ? '为了保护账号安全，请先设置新密码。' : '使用企业账号进入对应工作台。' }}</p>
      <div v-if="notice" class="notice success">{{ notice }}</div>
      <div v-if="error" class="notice error">{{ error }}</div>
      <form v-if="mode() === '/login'" @submit.prevent="login">
        <input v-model="loginForm.username" placeholder="用户名" autocomplete="username" />
        <input v-model="loginForm.password" placeholder="密码" type="password" autocomplete="current-password" />
        <button class="primary full" :disabled="busy">{{ busy ? '登录中...' : '登录' }}</button>
      </form>
      <form v-else-if="mode() === '/register'" @submit.prevent="register">
        <input v-model="registerForm.username" placeholder="用户名" />
        <input v-model="registerForm.displayName" placeholder="姓名" />
        <input v-model="registerForm.password" placeholder="密码（至少 8 位）" type="password" />
        <select v-model="registerForm.departmentId">
          <option value="" disabled>选择所属部门</option>
          <option v-for="d in departments" :key="d.id" :value="String(d.id)">{{ d.name }}</option>
        </select>
        <button class="primary full" :disabled="busy">{{ busy ? '提交中...' : '注册并申请审核' }}</button>
      </form>
      <form v-else @submit.prevent="changePassword">
        <input v-model="passwordForm.currentPassword" placeholder="当前密码" type="password" autocomplete="current-password" />
        <input v-model="passwordForm.newPassword" placeholder="新密码（至少 8 位）" type="password" autocomplete="new-password" />
        <button class="primary full" :disabled="busy">{{ busy ? '保存中...' : '保存新密码' }}</button>
      </form>
      <button v-if="mode() !== '/change-password'" class="link-button" @click="router.push(mode() === '/login' ? '/register' : '/login')">{{ mode() === '/login' ? '员工注册' : '返回登录' }}</button>
      <div v-if="mode() !== '/change-password'" class="demo-hint">
        ⚠️ 本地演示环境使用种子账号；共享或生产环境部署前请替换密码并移除演示数据。
      </div>
    </div>
  </div>
</template>

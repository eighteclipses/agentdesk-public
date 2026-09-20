<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, errorMessage } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, X, Users, KeyRound, Pencil } from 'lucide-vue-next'

const users = ref<any[]>([])
const departments = ref<any[]>([])
const departmentForm = ref({ name: '' })
const userForm = ref({ username: '', password: '', displayName: '', departmentId: '', role: 'EMPLOYEE' })
const busy = ref(false)
const creatingUser = ref(false)
const creatingDept = ref(false)
const loadError = ref('')

async function load() {
  loadError.value = ''
  try {
    const [d, u] = await Promise.all([api.get('/admin/departments'), api.get('/admin/users')])
    departments.value = d.data
    users.value = u.data
  } catch (e: any) { loadError.value = errorMessage(e, '组织数据加载失败') }
}
onMounted(load)

async function approve(id: number) {
  if (busy.value) return
  busy.value = true
  try { await api.post(`/admin/users/${id}/approve`); ElMessage.success('账号已激活'); await load() }
  catch (e: any) { ElMessage.error(errorMessage(e, '激活失败')) }
  finally { busy.value = false }
}
async function toggleUser(u: any) {
  if (busy.value) return
  busy.value = true
  try { await api.patch(`/admin/users/${u.id}`, { active: !u.active }); ElMessage.success(u.active ? '账号已停用' : '账号已启用'); await load() }
  catch (e: any) { ElMessage.error(errorMessage(e, '状态更新失败')) }
  finally { busy.value = false }
}
async function resetPassword(u: any) {
  const r = await ElMessageBox.prompt(`为「${u.display_name || u.username}」设置新的初始密码（用户下次登录会被要求修改）`, '重置密码', { inputPlaceholder: '新密码（至少 8 位）', inputType: 'password' }).catch(() => null)
  if (!r) return
  const pwd = String(r.value)
  if (pwd.length < 8) return ElMessage.warning('密码至少 8 位')
  if (busy.value) return
  busy.value = true
  try { await api.post(`/admin/users/${u.id}/reset-password`, { password: pwd }); ElMessage.success('密码已重置，用户下次登录需修改'); await load() }
  catch (e: any) { ElMessage.error(errorMessage(e, '重置密码失败')) }
  finally { busy.value = false }
}
async function renameDepartment(d: any) {
  const r = await ElMessageBox.prompt('修改部门名称', '编辑部门', { inputValue: d.name }).catch(() => null)
  if (!r || !String(r.value).trim()) return
  if (busy.value) return
  busy.value = true
  try { await api.patch(`/admin/departments/${d.id}`, { name: String(r.value).trim() }); ElMessage.success('部门已更新'); await load() }
  catch (e: any) { ElMessage.error(errorMessage(e, '部门更新失败')) }
  finally { busy.value = false }
}
/** 编辑用户：调整角色与所属部门（后端 PATCH /admin/users/{id} 支持） */
async function editUser(u: any) {
  const deptOptions = departments.value.map((d: any) => `${d.id} ${d.name}`).join('\n')
  const r = await ElMessageBox.prompt(
    `修改「${u.display_name || u.username}」的配置，格式一行一项：\nrole=EMPLOYEE|AGENT|ADMIN\ndepartmentId=部门编号（可用：${deptOptions}）`,
    '编辑用户',
    { inputPlaceholder: `role=${u.role}\ndepartmentId=${u.department_id || ''}`, inputValue: `role=${u.role}\ndepartmentId=${u.department_id || ''}` },
  ).catch(() => null)
  if (!r) return
  const lines = String(r.value).split('\n').map((s) => s.trim()).filter(Boolean)
  const patch: Record<string, unknown> = {}
  for (const line of lines) {
    const m = /^(\w+)\s*=\s*(.+)$/.exec(line)
    if (!m) continue
    if (m[1] === 'role') {
      if (!['EMPLOYEE', 'AGENT', 'ADMIN'].includes(m[2].toUpperCase())) return ElMessage.warning('角色只能是 EMPLOYEE / AGENT / ADMIN')
      patch.role = m[2].toUpperCase()
    } else if (m[1] === 'departmentId') {
      if (!/^\d+$/.test(m[2])) return ElMessage.warning('departmentId 必须是数字')
      patch.departmentId = Number(m[2])
    }
  }
  if (!Object.keys(patch).length) return ElMessage.warning('没有可保存的修改')
  if (busy.value) return
  busy.value = true
  try { await api.patch(`/admin/users/${u.id}`, patch); ElMessage.success('用户已更新'); await load() }
  catch (e: any) { ElMessage.error(errorMessage(e, '用户更新失败')) }
  finally { busy.value = false }
}
async function createDepartment() {
  if (!departmentForm.value.name.trim()) return ElMessage.warning('请填写部门名称')
  if (creatingDept.value) return
  creatingDept.value = true
  try {
    await api.post('/admin/departments', { name: departmentForm.value.name.trim() })
    departmentForm.value = { name: '' }
    ElMessage.success('部门已创建'); await load()
  } catch (e: any) { ElMessage.error(errorMessage(e, '部门创建失败')) }
  finally { creatingDept.value = false }
}
async function createAdminUser() {
  if (!userForm.value.username.trim() || !userForm.value.password || !userForm.value.displayName.trim() || !userForm.value.departmentId) return ElMessage.warning('请完整填写用户信息')
  if (userForm.value.password.length < 8) return ElMessage.warning('初始密码至少 8 位')
  if (creatingUser.value) return
  creatingUser.value = true
  try {
    await api.post('/admin/users', { ...userForm.value, username: userForm.value.username.trim(), displayName: userForm.value.displayName.trim(), departmentId: Number(userForm.value.departmentId) })
    userForm.value = { username: '', password: '', displayName: '', departmentId: '', role: 'EMPLOYEE' }
    ElMessage.success('用户已创建'); await load()
  } catch (e: any) { ElMessage.error(errorMessage(e, '用户创建失败')) }
  finally { creatingUser.value = false }
}
</script>

<template>
  <section class="page">
    <div class="page-head">
      <div><p class="eyebrow">ADMIN / ORGANIZATION</p><h1>组织与用户</h1><p class="muted">审核注册账号，维护部门归属和账号状态。</p></div>
    </div>
    <div v-if="loadError" class="notice error">{{ loadError }} <button class="small" @click="load">重试</button></div>
    <div class="grid-two">
      <div>
        <div class="panel">
          <div class="panel-title"><span>新增部门</span></div>
          <div class="inline-form"><input v-model="departmentForm.name" placeholder="部门名称" /><button class="primary" :disabled="creatingDept" @click="createDepartment">{{ creatingDept ? '创建中…' : '创建部门' }}</button></div>
        </div>
        <div class="panel table-panel">
          <div class="panel-title"><span>部门</span><span class="muted">{{ departments.length }} 个</span></div>
          <table><thead><tr><th>ID</th><th>部门名称</th><th>操作</th></tr></thead>
            <tbody><tr v-for="d in departments" :key="d.id"><td>{{ d.id }}</td><td>{{ d.name }}</td><td><button class="small" title="改名" :disabled="busy" @click="renameDepartment(d)"><Pencil :size="14" /></button></td></tr></tbody>
          </table>
        </div>
      </div>
      <div>
        <div class="panel">
          <div class="panel-title"><span>管理员创建用户</span></div>
          <div class="form-grid compact">
            <input v-model="userForm.username" placeholder="用户名" />
            <input v-model="userForm.displayName" placeholder="姓名" />
            <input v-model="userForm.password" placeholder="初始密码（至少 8 位）" type="password" />
            <select v-model="userForm.departmentId"><option value="" disabled>所属部门</option><option v-for="d in departments" :key="d.id" :value="String(d.id)">{{ d.name }}</option></select>
            <select v-model="userForm.role"><option value="EMPLOYEE">员工</option><option value="AGENT">处理人</option><option value="ADMIN">管理员</option></select>
          </div>
          <button class="primary" :disabled="creatingUser" @click="createAdminUser">{{ creatingUser ? '创建中…' : '创建用户' }}</button>
        </div>
        <div class="panel table-panel">
          <div class="panel-title"><span>用户账号</span><span class="muted">{{ users.length }} 个</span></div>
          <table>
            <thead><tr><th>用户</th><th>部门</th><th>角色/状态</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="u in users" :key="u.id">
                <td><strong>{{ u.display_name }}</strong><small>{{ u.username }} · #{{ u.id }}</small></td>
                <td>{{ u.department }}</td>
                <td>{{ u.role }}<small>{{ u.account_status }}</small></td>
                <td>
                  <button v-if="u.account_status === 'PENDING_APPROVAL'" class="approve" :disabled="busy" @click="approve(u.id)"><Check :size="14" />激活</button>
                  <template v-else>
                    <button class="small" :title="u.active ? '停用' : '启用'" :disabled="busy" @click="toggleUser(u)"><X v-if="u.active" :size="15" /><Check v-else :size="15" /></button>
                    <button class="small" title="编辑角色/部门" :disabled="busy" @click="editUser(u)"><Pencil :size="14" /></button>
                    <button class="small" title="重置密码" :disabled="busy" @click="resetPassword(u)"><KeyRound :size="14" /></button>
                  </template>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessageBox } from 'element-plus';import { ElMessage } from '@/app/messages'
import AsyncState from '@/components/AsyncState.vue'
import { registrationEnabled } from '@/app/siteMode'
import RegistrationInvitations from '@/components/RegistrationInvitations.vue'
import { listUsers, setUserBanned, getUserMemberships, setUserMembership } from '@/api/user'
import type { UserMembership } from '@/api/user'
import { readableError } from '@/api/errors'
import { formatShanghai } from '@/utils/format'
import type { User } from '@/api/types'
const page = ref(1), busyId = ref('')
const query = useQuery({ queryKey: ['admin', 'users', page], queryFn: () => listUsers({ current: page.value, pageSize: 20, sortField: 'createTime', sortOrder: 'descend' }) })
const userIds = computed(() => query.data.value?.records.map(user => user.id) ?? [])
const memberships = useQuery({ queryKey: computed(() => ['admin', 'memberships', userIds.value]), queryFn: ({ signal }) => getUserMemberships(userIds.value, signal), enabled: computed(() => userIds.value.length > 0), refetchInterval: 30000 })
const byUser = computed(() => new Map(memberships.data.value?.map(item => [item.userId, item]) ?? []))
const dialog = ref(false), saving = ref(false), selected = ref<{ user: User; membership: UserMembership } | null>(null)
const tier = ref<'FREE'|'VIP'>('VIP'), expiry = ref(''), reason = ref(''), formError = ref('')
function editMembership(user: User) {
  const membership = byUser.value.get(user.id)
  if (!membership) return
  selected.value = { user, membership: { ...membership } }
  tier.value = user.userRole === 'ban' ? 'FREE' : 'VIP'
  const date = new Date(membership.validUntil && Date.parse(membership.validUntil) > Date.now() ? membership.validUntil : Date.now() + 30 * 86400000)
  expiry.value = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(date).replace(' ', 'T')
  reason.value = ''; formError.value = ''; dialog.value = true
}
async function saveMembership() {
  if (!selected.value || saving.value) return
  const validUntil = tier.value === 'VIP' ? `${expiry.value}:00+08:00` : null
  if (!reason.value.trim()) { formError.value = '请填写操作原因'; return }
  if (validUntil && (!Number.isFinite(Date.parse(validUntil)) || Date.parse(validUntil) <= Date.now())) { formError.value = '到期时间必须晚于当前时间'; return }
  saving.value = true; formError.value = ''
  try {
    await setUserMembership(selected.value.user.id, { tier: tier.value, validUntil, expectedVersion: selected.value.membership.rowVersion, reason: reason.value.trim() })
    ElMessage.success({ message: tier.value === 'VIP' ? 'VIP 与到期时间已保存' : 'VIP 已取消' })
    dialog.value = false; await memberships.refetch()
  } catch (error) { formError.value = readableError(error); void memberships.refetch() }
  finally { saving.value = false }
}
async function toggleBan(row: User) {
  const banned = row.userRole === 'ban'
  try {
    await ElMessageBox.confirm(`确认${banned ? '解除封禁' : '封禁'}账号 ${row.userAccount ?? row.id}？`, banned ? '解除封禁' : '封禁用户', { type: banned ? 'info' : 'warning' })
    busyId.value = row.id; await setUserBanned(row.id, !banned)
    ElMessage.success({ message: banned ? '已解除封禁' : '用户已封禁' }); await query.refetch(); await memberships.refetch()
  } catch (error) { if (error !== 'cancel') ElMessage.error({ message: readableError(error) }) }
  finally { busyId.value = '' }
}
</script>
<template>
  <div class="page admin-page">
    <div class="page-head"><div><h1 class="page-title">用户管理</h1><p class="page-lead">管理用户封禁状态、VIP 资格和到期时间</p></div></div>
    <RegistrationInvitations v-if="registrationEnabled"/>
    <p v-if="memberships.isError.value" role="alert">会员信息读取失败：{{ readableError(memberships.error.value) }} <el-button @click="memberships.refetch()">重试</el-button></p>
    <section class="surface"><AsyncState :loading="query.isPending.value" :error="query.isError.value ? readableError(query.error.value) : undefined" :empty="query.data.value?.records.length === 0" @retry="query.refetch()">
      <el-table :data="query.data.value?.records">
        <el-table-column label="账号" prop="userAccount" min-width="170"/>
        <el-table-column label="昵称" prop="userName" min-width="120"/>
        <el-table-column label="状态" width="100"><template #default="{row}"><el-tag v-if="row.userRole === 'admin'">管理员</el-tag><el-tag v-else-if="row.userRole === 'ban'" type="danger">已封禁</el-tag><el-tag v-else type="success">正常</el-tag></template></el-table-column>
        <el-table-column label="会员" width="110"><template #default="{row}"><span v-if="row.userRole === 'admin'">—</span><el-tag v-else-if="byUser.get(row.id)?.vipActive" type="warning">VIP</el-tag><span v-else-if="byUser.get(row.id)?.tier === 'VIP'">{{ row.userRole === 'ban' ? '已停用' : '已到期' }}</span><span v-else>{{ byUser.has(row.id) ? '普通用户' : '加载中…' }}</span></template></el-table-column>
        <el-table-column label="VIP 到期时间（北京时间）" min-width="230"><template #default="{row}">{{ formatShanghai(byUser.get(row.id)?.validUntil) }}</template></el-table-column>
        <el-table-column label="操作" width="220" fixed="right"><template #default="{row}">
          <span v-if="row.userRole === 'admin'" class="muted">—</span>
          <template v-else><el-button v-if="row.userRole === 'user' || byUser.get(row.id)?.tier === 'VIP'" link type="primary" :disabled="!byUser.has(row.id) || memberships.isError.value" @click="editMembership(row)">{{ row.userRole === 'ban' ? '取消 VIP' : '设置 VIP' }}</el-button><el-button link :type="row.userRole === 'ban' ? 'primary' : 'danger'" :loading="busyId === row.id" @click="toggleBan(row)">{{ row.userRole === 'ban' ? '解除封禁' : '封禁' }}</el-button></template>
        </template></el-table-column>
      </el-table>
    </AsyncState></section>
    <div v-if="query.data.value" class="pager"><el-pagination v-model:current-page="page" :page-size="20" :total="query.data.value.total" layout="total,prev,pager,next"/></div>
    <el-dialog v-model="dialog" title="设置 VIP" width="min(520px, 94vw)" :close-on-click-modal="!saving" :show-close="!saving" :close-on-press-escape="!saving">
      <el-form label-position="top" @submit.prevent="saveMembership">
        <p>账号：{{ selected?.user.userAccount }}</p>
        <el-form-item label="会员类型"><el-radio-group v-model="tier" :disabled="saving"><el-radio-button value="VIP" :disabled="selected?.user.userRole !== 'user'">VIP</el-radio-button><el-radio-button value="FREE">普通用户（取消 VIP）</el-radio-button></el-radio-group></el-form-item>
        <el-form-item v-if="tier === 'VIP'" label="到期时间（北京时间 UTC+8）"><input v-model="expiry" type="datetime-local" aria-label="VIP 到期时间（北京时间）" class="expiry-input" :disabled="saving" required></el-form-item>
        <p class="muted">保存后立即生效，到期自动恢复普通用户的 AI 使用额度与权限。</p>
        <el-form-item label="操作原因"><el-input v-model="reason" aria-label="操作原因" type="textarea" maxlength="500" show-word-limit :disabled="saving"/></el-form-item>
        <p v-if="formError" role="alert" class="form-error">{{ formError }}</p>
        <el-button :disabled="saving" @click="dialog = false">取消</el-button><el-button native-type="submit" type="primary" :loading="saving">保存会员设置</el-button>
      </el-form>
    </el-dialog>
  </div>
</template>
<style scoped>
.admin-page{max-width:1280px}.pager{display:flex;justify-content:flex-end;margin-top:18px}.expiry-input{width:100%;padding:10px;background:var(--oj-surface);color:var(--oj-text);border:1px solid var(--oj-border);border-radius:6px;color-scheme:dark}.form-error{color:#f87171}
</style>

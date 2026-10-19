<script setup lang="ts">
import RefreshButton from '@/components/RefreshButton.vue'
import { ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessageBox } from 'element-plus';import { ElMessage } from '@/app/messages'
import { createInvitation, listInvitations, revokeInvitation } from '@/api/user'
import type { RegistrationInvitation } from '@/api/user'
import { readableError } from '@/api/errors'
import { formatShanghai } from '@/utils/format'
const page = ref(1), hours = ref(72), note = ref(''), busy = ref(false), error = ref('')
const code = ref(''), expiresAt = ref(''), showCode = ref(false)
const labels = { AVAILABLE: '可使用', USED: '已使用', REVOKED: '已撤销', EXPIRED: '已过期' }
const query = useQuery({ queryKey: ['admin', 'invitations', page], queryFn: () => listInvitations(page.value), refetchInterval: 30000 })
async function generate() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    const created = await createInvitation({ validityHours: hours.value, note: note.value.trim() })
    code.value = created.code; expiresAt.value = created.invitation.expiresAt; showCode.value = true
    note.value = ''; page.value = 1; await query.refetch()
  } catch (e) { error.value = readableError(e) }
  finally { busy.value = false }
}
async function revoke(row: RegistrationInvitation) {
  try {
    await ElMessageBox.confirm('撤销后，此邀请码将不能再用于注册。', '撤销邀请码', { type: 'warning' })
    await revokeInvitation(row.id); ElMessage.success({ message: '邀请码已撤销' }); await query.refetch()
  } catch (e) { if (e !== 'cancel' && e !== 'close') { ElMessage.error({ message: readableError(e) }); void query.refetch() } }
}
async function copy() {
  try { await navigator.clipboard.writeText(code.value); ElMessage.success({ message: '邀请码已复制' }) }
  catch { ElMessage.info({ message: '请选中下方邀请码手动复制' }) }
}
</script>
<template>
  <section class="surface invitations">
    <h2>注册邀请</h2><p class="muted">新账号必须使用你提供的一次性邀请码；注册成功后失效。</p>
    <el-form inline @submit.prevent="generate">
      <el-form-item label="有效期（小时）"><el-input-number v-model="hours" :min="1" :max="720" :precision="0" :disabled="busy" aria-label="邀请码有效期（小时）"/></el-form-item>
      <el-form-item label="备注"><el-input v-model="note" maxlength="120" :disabled="busy" placeholder="可填写受邀人，方便核对"/></el-form-item>
      <el-form-item><el-button type="primary" native-type="submit" :loading="busy">生成邀请码</el-button></el-form-item>
    </el-form>
    <p v-if="error || query.isError.value" role="alert" class="form-error">{{ error || readableError(query.error.value) }} <RefreshButton @click="query.refetch()" :loading="query.isFetching.value">刷新列表</RefreshButton></p>
    <el-table :data="query.data.value?.records" v-loading="query.isPending.value" empty-text="暂无邀请码">
      <el-table-column label="编号" prop="id" width="90"/>
      <el-table-column label="备注" prop="note" min-width="160"/>
      <el-table-column label="状态" width="100"><template #default="{row}"><el-tag :type="row.status === 'AVAILABLE' ? 'success' : 'info'">{{ labels[row.status as keyof typeof labels] }}</el-tag></template></el-table-column>
      <el-table-column label="到期时间（北京时间）" min-width="210"><template #default="{row}">{{ formatShanghai(row.expiresAt) }}</template></el-table-column>
      <el-table-column label="使用者 ID" min-width="180"><template #default="{row}">{{ row.usedBy || '—' }}</template></el-table-column>
      <el-table-column label="操作" width="100"><template #default="{row}"><el-button v-if="row.status === 'AVAILABLE'" type="danger" text @click="revoke(row)">撤销</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-if="(query.data.value?.total ?? 0) > 20" v-model:current-page="page" :page-size="20" :total="query.data.value?.total ?? 0" layout="prev,pager,next"/>
    <el-dialog v-model="showCode" title="邀请码已生成" width="min(560px,95vw)" :close-on-click-modal="false" @closed="code = ''">
      <p>邀请码仅在这里显示一次，请复制保存后交给受邀用户。</p>
      <el-input :model-value="code" readonly aria-label="已生成的邀请码"/><p>到期时间：{{ formatShanghai(expiresAt) }}</p>
      <template #footer><el-button @click="copy">复制邀请码</el-button><el-button type="primary" @click="showCode = false">已保存</el-button></template>
    </el-dialog>
  </section>
</template>
<style scoped>.invitations{padding:20px;margin-bottom:24px}h2{margin:0 0 8px;font-size:19px}.form-error{color:var(--oj-danger)}.el-pagination{justify-content:flex-end;margin-top:16px}</style>

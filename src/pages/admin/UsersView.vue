<script setup>
import { ref, onMounted } from 'vue'
import { users } from '@/services/db'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'

const auth = useAuthStore()
const toast = useToast()
const confirm = ref(null)
const list = ref([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    list.value = await users.list()
  } catch (e) {
    toast.error('사용자 목록을 불러오지 못했습니다.')
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function setRole(u, role) {
  if (u.id === auth.user.uid && role !== 'admin') {
    const ok = await confirm.value.ask({
      title: '본인 권한 변경',
      message: '본인 관리자 권한을 해제하면 더 이상 관리 화면을 사용할 수 없습니다. 계속할까요?',
      confirmText: '해제',
      danger: true,
    })
    if (!ok) return
  }
  try {
    await users.setRole(u.id, role)
    u.role = role
    toast.success(`${u.displayName} → ${role === 'admin' ? '관리자' : '일반'} 변경됨`)
  } catch (e) {
    toast.error('권한 변경 실패: ' + (e.message || e.code))
  }
}

async function setStock(u, on) {
  try {
    await users.setStockPerm(u.id, on)
    u.canStock = on
    toast.success(`${u.displayName} 입/출고 권한 ${on ? '부여' : '회수'}됨`)
  } catch (e) {
    toast.error('권한 변경 실패: ' + (e.message || e.code))
  }
}
</script>

<template>
  <div>
    <PageHeader title="사용자 관리" subtitle="권한(관리자/일반)과 입/출고 권한을 설정합니다. 일반 사용자는 기본 조회만 가능합니다." />

    <div class="card overflow-hidden">
      <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
      <div v-else-if="!list.length" class="p-10 text-center text-sm text-slate-400">사용자가 없습니다.</div>
      <table v-else class="w-full text-sm">
        <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th class="px-4 py-2.5 font-semibold">이름</th>
            <th class="px-4 py-2.5 font-semibold">이메일</th>
            <th class="px-4 py-2.5 font-semibold">권한</th>
            <th class="px-4 py-2.5 font-semibold">입/출고 권한</th>
            <th class="px-4 py-2.5 text-right font-semibold">변경</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-for="u in list" :key="u.id" class="hover:bg-slate-50/60">
            <td class="px-4 py-3 font-medium text-slate-800">
              {{ u.displayName }}
              <span v-if="u.id === auth.user.uid" class="badge bg-slate-100 text-[10px] text-slate-400">나</span>
            </td>
            <td class="px-4 py-3 text-slate-500">{{ u.email }}</td>
            <td class="px-4 py-3">
              <span class="badge" :class="u.role === 'admin' ? 'bg-brand-50 text-brand-700' : 'bg-slate-100 text-slate-500'">
                {{ u.role === 'admin' ? '관리자' : '일반' }}
              </span>
            </td>
            <td class="px-4 py-3">
              <!-- 관리자는 항상 입/출고 가능 -->
              <span v-if="u.role === 'admin'" class="badge bg-emerald-50 text-[11px] text-emerald-700">관리자(자동)</span>
              <button
                v-else
                class="badge cursor-pointer text-[11px]"
                :class="u.canStock ? 'bg-emerald-50 text-emerald-700 hover:bg-emerald-100' : 'bg-slate-100 text-slate-400 hover:bg-slate-200'"
                @click="setStock(u, !u.canStock)"
              >{{ u.canStock ? '허용 ✓ (회수)' : '조회만 (부여)' }}</button>
            </td>
            <td class="px-4 py-3 text-right">
              <button v-if="u.role !== 'admin'" class="btn-ghost btn-sm" @click="setRole(u, 'admin')">관리자로</button>
              <button v-else class="btn-ghost btn-sm text-rose-600" @click="setRole(u, 'user')">일반으로</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <ConfirmDialog ref="confirm" />
  </div>
</template>

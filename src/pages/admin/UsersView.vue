<script setup>
import { ref, onMounted, reactive } from 'vue'
import { users, complexes } from '@/services/db'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import BaseModal from '@/components/ui/BaseModal.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'

const auth = useAuthStore()
const toast = useToast()
const confirm = ref(null)
const list = ref([])
const loading = ref(true)
const complexList = ref([]) // 관리단지 선택 옵션

const ROLES = [
  { value: 'super', label: '슈퍼관리자', desc: '전체 권한 (사용자관리·감사로그·AI사용량 포함)' },
  { value: 'manager', label: '매니저', desc: '기준정보/상품/재고 전부. 사용자관리·감사로그·AI사용량 제외' },
  { value: 'registrar', label: '등록인', desc: '모바일에서 입고/출고 등록만' },
]
const roleLabel = (r) => ROLES.find((x) => x.value === r)?.label || r
const roleBadge = (r) => (r === 'super' ? 'bg-brand-50 text-brand-700' : r === 'manager' ? 'bg-emerald-50 text-emerald-700' : 'bg-slate-100 text-slate-500')

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
onMounted(async () => {
  load()
  try {
    complexList.value = await complexes.list()
  } catch (e) {
    /* 단지 목록 실패는 치명적이지 않음 — 관리단지는 직접 입력 가능 */
  }
})

/* 등록/수정 모달 */
const modal = ref(false)
const editing = ref(null) // null=신규, 아니면 대상 사용자
const form = reactive({ username: '', displayName: '', jobTitle: '', managedComplex: '', email: '', password: '', role: 'registrar' })
const saving = ref(false)

function openCreate() {
  editing.value = null
  Object.assign(form, { username: '', displayName: '', jobTitle: '', managedComplex: '', email: '', password: '', role: 'registrar' })
  modal.value = true
}
function openEdit(u) {
  editing.value = u
  Object.assign(form, { username: u.username, displayName: u.displayName || '', jobTitle: u.jobTitle || '', managedComplex: u.managedComplex || '', email: u.email || '', password: '', role: u.role })
  modal.value = true
}

async function save() {
  if (!form.username.trim()) return toast.error('아이디를 입력하세요.')
  if (!editing.value && (form.password || '').length < 6) return toast.error('비밀번호는 6자 이상이어야 합니다.')
  saving.value = true
  try {
    const payload = {
      username: form.username.trim(),
      displayName: form.displayName.trim(),
      jobTitle: form.jobTitle.trim(),
      managedComplex: form.managedComplex.trim(),
      email: form.email.trim(),
      role: form.role,
    }
    if (form.password) payload.password = form.password
    if (editing.value) {
      await users.update(editing.value.id, payload)
      toast.success('사용자 정보가 수정되었습니다.')
    } else {
      await users.create(payload)
      toast.success('사용자가 등록되었습니다.')
    }
    modal.value = false
    await load()
  } catch (e) {
    toast.error('저장 실패: ' + (e.message || e.code))
  } finally {
    saving.value = false
  }
}

async function remove(u) {
  if (u.id === auth.user.uid) return toast.error('본인 계정은 삭제할 수 없습니다.')
  const ok = await confirm.value.ask({
    title: '사용자 삭제',
    message: `'${u.displayName || u.username}' 계정을 삭제할까요? 되돌릴 수 없습니다.`,
    confirmText: '삭제',
    danger: true,
  })
  if (!ok) return
  try {
    await users.remove(u.id)
    toast.success('삭제되었습니다.')
    await load()
  } catch (e) {
    toast.error('삭제 실패: ' + (e.message || e.code))
  }
}
</script>

<template>
  <div>
    <PageHeader title="사용자 관리" subtitle="로그인 계정을 등록·수정·삭제하고 역할(슈퍼관리자/매니저/등록인)을 지정합니다.">
      <button class="btn-primary btn-sm" @click="openCreate">+ 사용자 등록</button>
    </PageHeader>

    <div class="card overflow-hidden">
      <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
      <div v-else-if="!list.length" class="p-10 text-center text-sm text-slate-400">사용자가 없습니다.</div>
      <table v-else class="w-full text-sm">
        <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th class="px-4 py-2.5 font-semibold">이름</th>
            <th class="px-4 py-2.5 font-semibold">직급</th>
            <th class="px-4 py-2.5 font-semibold">관리단지</th>
            <th class="px-4 py-2.5 font-semibold">아이디</th>
            <th class="px-4 py-2.5 font-semibold">이메일</th>
            <th class="px-4 py-2.5 font-semibold">역할</th>
            <th class="px-4 py-2.5 text-right font-semibold">관리</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-for="u in list" :key="u.id" class="hover:bg-slate-50/60">
            <td class="px-4 py-3 font-medium text-slate-800">
              {{ u.displayName }}
              <span v-if="u.id === auth.user.uid" class="badge bg-slate-100 text-[10px] text-slate-400">나</span>
            </td>
            <td class="px-4 py-3 text-slate-600">{{ u.jobTitle || '—' }}</td>
            <td class="px-4 py-3 text-slate-600">{{ u.managedComplex || '—' }}</td>
            <td class="px-4 py-3 text-slate-600">{{ u.username }}</td>
            <td class="px-4 py-3 text-slate-400">{{ u.email || '—' }}</td>
            <td class="px-4 py-3">
              <span class="badge" :class="roleBadge(u.role)">{{ roleLabel(u.role) }}</span>
            </td>
            <td class="px-4 py-3 text-right">
              <button class="btn-ghost btn-sm" @click="openEdit(u)">수정</button>
              <button v-if="u.id !== auth.user.uid" class="btn-ghost btn-sm text-rose-600" @click="remove(u)">삭제</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <BaseModal v-model="modal" :title="editing ? '사용자 수정' : '사용자 등록'">
      <div class="space-y-3">
        <div>
          <label class="label">이름</label>
          <input v-model="form.displayName" class="input" placeholder="홍길동" />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="label">직급</label>
            <input v-model="form.jobTitle" class="input" placeholder="소장 / 시설과장 등" />
          </div>
          <div>
            <label class="label">관리단지</label>
            <input v-model="form.managedComplex" class="input" list="complex-options" placeholder="랜드 / 허브 등" />
            <datalist id="complex-options">
              <option v-for="c in complexList" :key="c.id" :value="c.name" />
            </datalist>
          </div>
        </div>
        <div>
          <label class="label">아이디 <span class="text-rose-500">*</span></label>
          <input v-model="form.username" class="input" placeholder="로그인 아이디" autocomplete="off" />
        </div>
        <div>
          <label class="label">이메일 <span class="text-slate-400">(선택)</span></label>
          <input v-model="form.email" type="email" class="input" placeholder="you@m-park.co.kr" autocomplete="off" />
        </div>
        <div>
          <label class="label">비밀번호 <span v-if="editing" class="text-slate-400">(변경 시에만 입력)</span><span v-else class="text-rose-500">*</span></label>
          <input v-model="form.password" type="password" class="input" :placeholder="editing ? '비워두면 기존 비밀번호 유지' : '6자 이상'" autocomplete="new-password" />
        </div>
        <div>
          <label class="label">역할</label>
          <div class="space-y-1.5">
            <label v-for="r in ROLES" :key="r.value" class="flex cursor-pointer items-start gap-2 rounded-lg border p-2.5 transition"
                   :class="form.role === r.value ? 'border-brand-400 bg-brand-50/50' : 'border-slate-200 hover:bg-slate-50'">
              <input v-model="form.role" type="radio" :value="r.value" class="mt-0.5" />
              <div>
                <div class="text-sm font-medium text-slate-700">{{ r.label }}</div>
                <div class="text-[11px] text-slate-400">{{ r.desc }}</div>
              </div>
            </label>
          </div>
        </div>
      </div>
      <template #footer>
        <button class="btn-ghost" @click="modal = false">취소</button>
        <button class="btn-primary" :disabled="saving" @click="save">{{ saving ? '저장 중…' : '저장' }}</button>
      </template>
    </BaseModal>

    <ConfirmDialog ref="confirm" />
  </div>
</template>

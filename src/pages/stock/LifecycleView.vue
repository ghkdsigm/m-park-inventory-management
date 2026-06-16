<script setup>
import { ref, computed, onMounted } from 'vue'
import { skus, complexes, replaceLifecycle, listLifecycleLogs } from '@/services/db'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { useBusy } from '@/composables/useBusy'
import { lifecycleStatus, daysUntil, fmtDate, fmtDateTime, CYCLE_UNITS } from '@/utils/date'
import PageHeader from '@/components/ui/PageHeader.vue'
import BaseModal from '@/components/ui/BaseModal.vue'
import { resolveImage } from '@/utils/image'

const auth = useAuthStore()
const toast = useToast()
const { busy, run } = useBusy()

const loading = ref(true)
const all = ref([])
const complexList = ref([])
const fComplex = ref('')
const fStatus = ref('')
const search = ref('')

async function load() {
  loading.value = true
  try {
    const [sk, cx] = await Promise.all([skus.list(), complexes.list()])
    all.value = sk.filter((s) => s.lifecycleEnabled)
    complexList.value = cx
  } catch (e) {
    toast.error('불러오기 실패: ' + (e.message || e.code))
  } finally {
    loading.value = false
  }
}
onMounted(load)

const STATUS = [
  { v: '', t: '전체' },
  { v: 'over', t: '초과' },
  { v: 'soon', t: '임박(D-30)' },
  { v: 'ok', t: '정상' },
]
const statusMeta = {
  ok: { t: '정상', c: 'bg-emerald-50 text-emerald-700' },
  soon: { t: '임박', c: 'bg-amber-50 text-amber-700' },
  over: { t: '초과', c: 'bg-rose-50 text-rose-600' },
  none: { t: '미설정', c: 'bg-slate-100 text-slate-400' },
}
const unitLabel = (u) => CYCLE_UNITS.find((x) => x.v === u)?.t || u

const decorated = computed(() =>
  all.value.map((s) => ({ ...s, _st: lifecycleStatus(s.nextReplaceAt), _d: daysUntil(s.nextReplaceAt) }))
)
const filtered = computed(() =>
  decorated.value
    .filter((s) => {
      if (fComplex.value && s.complexId !== fComplex.value) return false
      if (fStatus.value && s._st !== fStatus.value) return false
      if (search.value) {
        const q = search.value.toLowerCase()
        return [s.code, s.productName, s.replaceReason].some((v) => (v || '').toLowerCase().includes(q))
      }
      return true
    })
    .sort((a, b) => (a._d ?? 1e9) - (b._d ?? 1e9))
)
const stats = computed(() => ({
  total: decorated.value.length,
  soon: decorated.value.filter((s) => s._st === 'soon').length,
  over: decorated.value.filter((s) => s._st === 'over').length,
}))

function ddayText(s) {
  if (s._d === null) return '예정일 없음'
  if (s._d < 0) return `${-s._d}일 초과`
  if (s._d === 0) return '오늘'
  return `D-${s._d}`
}

/* ---- 교체 처리 ---- */
const replaceModal = ref(false)
const target = ref(null)
const reason = ref('')
function openReplace(s) {
  target.value = s
  reason.value = s.replaceReason || ''
  replaceModal.value = true
}
async function doReplace() {
  try {
    const r = await replaceLifecycle(target.value.id, auth.actor, reason.value)
    toast.success(`교체 완료 · 다음 예정 ${fmtDate(r.nextReplaceAt) || '-'}`)
    replaceModal.value = false
    await load()
  } catch (e) {
    toast.error('교체 처리 실패: ' + (e.message || e.code))
  }
}

/* ---- 이력 ---- */
const histModal = ref(false)
const histLogs = ref([])
const histSku = ref(null)
async function openHistory(s) {
  histSku.value = s
  histModal.value = true
  histLogs.value = []
  try {
    histLogs.value = await listLifecycleLogs(s.id, 50)
  } catch (e) {
    toast.error('이력 조회 실패: ' + (e.message || e.code))
  }
}
</script>

<template>
  <div>
    <PageHeader title="연한관리" subtitle="주기적으로 교체해야 하는 SKU의 교체 예정일을 관리합니다. (주기 설정은 SKU관리에서)" />

    <div class="mb-4 grid grid-cols-3 gap-3">
      <div class="card p-3 text-center"><p class="text-xs text-slate-400">연한 대상</p><p class="text-xl font-bold text-slate-800">{{ stats.total }}</p></div>
      <div class="card p-3 text-center"><p class="text-xs text-slate-400">교체 임박</p><p class="text-xl font-bold text-amber-500">{{ stats.soon }}</p></div>
      <div class="card p-3 text-center"><p class="text-xs text-slate-400">교체 초과</p><p class="text-xl font-bold text-rose-500">{{ stats.over }}</p></div>
    </div>

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <input v-model="search" class="input w-auto flex-1 sm:max-w-xs" placeholder="SKU코드/상품명/사유 검색" />
      <select v-model="fComplex" class="input w-auto"><option value="">전체 단지</option><option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option></select>
      <select v-model="fStatus" class="input w-auto"><option v-for="s in STATUS" :key="s.v" :value="s.v">{{ s.t }}</option></select>
    </div>

    <div class="card overflow-x-auto scrollbar-slim">
      <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
      <div v-else-if="!filtered.length" class="p-10 text-center text-sm text-slate-400">연한관리 대상 SKU가 없습니다. (SKU관리에서 "연한관리 사용"을 켜세요)</div>
      <table v-else class="w-full min-w-[860px] text-sm">
        <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th class="px-3 py-2.5 font-semibold">SKU / 상품</th>
            <th class="px-3 py-2.5 font-semibold">주기</th>
            <th class="px-3 py-2.5 font-semibold">기준일</th>
            <th class="px-3 py-2.5 font-semibold">다음 교체예정</th>
            <th class="px-3 py-2.5 font-semibold">사유</th>
            <th class="px-3 py-2.5 font-semibold">상태</th>
            <th class="px-3 py-2.5 text-right font-semibold">처리</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-for="s in filtered" :key="s.id" class="hover:bg-slate-50/60" :class="s._st === 'over' ? 'bg-rose-50/40' : s._st === 'soon' ? 'bg-amber-50/30' : ''">
            <td class="px-3 py-2">
              <div class="flex items-center gap-2.5">
                <img :src="resolveImage(s)" class="h-9 w-9 shrink-0 rounded border border-slate-100 object-cover" alt="" />
                <div class="min-w-0">
                  <span class="badge bg-brand-50 font-mono text-brand-700">{{ s.code }}</span>
                  <p class="text-slate-700">{{ s.productName }}</p>
                  <p v-if="s.locationLabel" class="truncate text-[11px] text-slate-400">📍 {{ s.complexName }} › {{ s.locationLabel }}</p>
                </div>
              </div>
            </td>
            <td class="whitespace-nowrap px-3 py-2 text-slate-600">{{ s.cycleValue }}{{ unitLabel(s.cycleUnit) }}</td>
            <td class="whitespace-nowrap px-3 py-2 text-slate-500">{{ fmtDate(s.lastReplacedAt) || '—' }}</td>
            <td class="whitespace-nowrap px-3 py-2">
              <span class="font-medium text-slate-800">{{ fmtDate(s.nextReplaceAt) || '—' }}</span>
              <span class="ml-1 text-xs" :class="s._st === 'over' ? 'text-rose-500' : s._st === 'soon' ? 'text-amber-600' : 'text-slate-400'">({{ ddayText(s) }})</span>
            </td>
            <td class="px-3 py-2 text-slate-500">{{ s.replaceReason || '—' }}</td>
            <td class="px-3 py-2"><span class="badge" :class="statusMeta[s._st]?.c">{{ statusMeta[s._st]?.t }}</span></td>
            <td class="whitespace-nowrap px-3 py-2 text-right">
              <button class="btn-ghost btn-sm" @click="openHistory(s)">이력</button>
              <button class="btn-primary btn-sm ml-1" :disabled="busy" @click="openReplace(s)">교체 처리</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 교체 처리 모달 -->
    <BaseModal v-model="replaceModal" title="교체 처리" size="sm">
      <div v-if="target" class="space-y-3">
        <p class="text-sm text-slate-600"><b class="font-mono">{{ target.code }}</b> {{ target.productName }}</p>
        <p class="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500">
          최근 교체일이 <b>오늘</b>로 갱신되고, 주기({{ target.cycleValue }}{{ unitLabel(target.cycleUnit) }})만큼 더한 날짜로 다음 예정일이 재계산됩니다.
        </p>
        <div>
          <label class="label">교체 사유</label>
          <input v-model="reason" class="input" placeholder="예: 법정점검 / 마모 교체" />
        </div>
      </div>
      <template #footer>
        <button class="btn-ghost" @click="replaceModal = false">취소</button>
        <button class="btn-primary" :disabled="busy" @click="run(doReplace)">교체 완료</button>
      </template>
    </BaseModal>

    <!-- 이력 모달 -->
    <BaseModal v-model="histModal" title="교체 이력">
      <div v-if="histSku" class="mb-2 text-sm text-slate-600"><b class="font-mono">{{ histSku.code }}</b> {{ histSku.productName }}</div>
      <div v-if="!histLogs.length" class="py-6 text-center text-sm text-slate-400">교체 이력이 없습니다.</div>
      <ul v-else class="divide-y divide-slate-50 text-sm">
        <li v-for="l in histLogs" :key="l.id" class="flex items-center justify-between py-2">
          <div>
            <p class="text-slate-700">{{ fmtDate(l.replacedAt) }} 교체 <span class="text-slate-400">→ 다음 {{ fmtDate(l.nextReplaceAt) || '-' }}</span></p>
            <p class="text-xs text-slate-400">{{ l.reason || '사유 없음' }} · {{ l.byName }}</p>
          </div>
          <span class="text-xs text-slate-300">{{ fmtDateTime(l.at) }}</span>
        </li>
      </ul>
    </BaseModal>
  </div>
</template>

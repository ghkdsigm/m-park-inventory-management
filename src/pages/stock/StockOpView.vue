<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { skus, complexes, storageLocations, applyStock, listMovements } from '@/services/db'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import { resolveImage } from '@/utils/image'
import { fmtDateTime } from '@/utils/date'

const route = useRoute()
const auth = useAuthStore()
const toast = useToast()

const OP = {
  in: { title: '입고관리', sub: 'SKU를 선택하고 입고 수량을 입력하세요.', qtyLabel: '입고 수량', btn: '입고 처리', btnClass: 'bg-emerald-600 hover:bg-emerald-700', mode: 'add' },
  out: { title: '출고관리', sub: 'SKU를 선택하고 출고 수량을 입력하세요.', qtyLabel: '출고 수량', btn: '출고 처리', btnClass: 'bg-sky-600 hover:bg-sky-700', mode: 'add' },
  adjust: { title: '재고조정', sub: '실제 재고와 시스템 재고가 다를 때 보정합니다.', qtyLabel: '조정 후 재고수량', btn: '재고 조정', btnClass: 'bg-amber-500 hover:bg-amber-600', mode: 'set' },
  audit: { title: '재고실사', sub: '실물 카운트 결과를 입력해 재고를 확정합니다.', qtyLabel: '실사 재고수량', btn: '실사 확정', btnClass: 'bg-violet-600 hover:bg-violet-700', mode: 'set' },
}
const op = computed(() => route.meta.op)
const cfg = computed(() => OP[op.value])

// 재고조정 사유 (필수)
const REASONS = ['파손', '분실', '도난', '오입력 정정', '유통기한 경과', '입고 오류', '기타']
const reason = ref('')

const loading = ref(true)
const list = ref([])
const complexList = ref([])
const search = ref('')
const filterComplex = ref('')
const selectedId = ref('')
const qty = ref(1)
const memo = ref('')
const working = ref(false)
const movements = ref([])

// 보관위치(재고조정용) — 단지(SKU 고정) › 구역 › 상세구역 › 보관위치 연쇄 선택
const storageLocs = ref([])
const locId = ref('')
const fZone = ref('')
const fSub = ref('')
const savingLoc = ref(false)
// 선택된 SKU의 단지에 속한 보관위치
const locForComplex = computed(() => storageLocs.value.filter((l) => l.complexId === selected.value?.complexId))
// 구역/상세구역 후보는 보관위치 데이터에서 추출(추가 조회 없음)
const zoneChoices = computed(() => {
  const m = new Map()
  locForComplex.value.forEach((l) => l.zoneId && m.set(l.zoneId, l.zoneName))
  return [...m].map(([id, name]) => ({ id, name }))
})
const subChoices = computed(() => {
  const m = new Map()
  locForComplex.value.forEach((l) => {
    if (l.zoneId === fZone.value && l.subZoneId) m.set(l.subZoneId, l.subZoneName)
  })
  return [...m].map(([id, name]) => ({ id, name }))
})
// 연쇄 필터가 적용된 보관위치 목록
const locOptions = computed(() =>
  locForComplex.value.filter(
    (l) => (!fZone.value || l.zoneId === fZone.value) && (!fSub.value || l.subZoneId === fSub.value)
  )
)
// 사용자가 직접 바꿀 때만 하위 선택 정리 (사전 세팅 시엔 건드리지 않음)
function onZoneChange() {
  fSub.value = ''
  if (locId.value && !locOptions.value.find((l) => l.id === locId.value)) locId.value = ''
}
function onSubChange() {
  if (locId.value && !locOptions.value.find((l) => l.id === locId.value)) locId.value = ''
}

async function load() {
  loading.value = true
  try {
    ;[list.value, complexList.value, storageLocs.value] = await Promise.all([
      skus.list(),
      complexes.list(),
      storageLocations.list(),
    ])
  } catch (e) {
    toast.error('불러오기 실패: ' + (e.message || e.code))
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(op, () => {
  selectedId.value = ''
  movements.value = []
})

const filtered = computed(() =>
  list.value.filter((s) => {
    if (filterComplex.value && s.complexId !== filterComplex.value) return false
    if (search.value) {
      const q = search.value.toLowerCase()
      return [s.code, s.productName, s.spec].some((v) => (v || '').toLowerCase().includes(q))
    }
    return true
  })
)
const selected = computed(() => list.value.find((s) => s.id === selectedId.value) || null)

watch(selected, async (s) => {
  if (cfg.value.mode === 'set') qty.value = s ? s.qty : 0
  else qty.value = 1
  memo.value = ''
  reason.value = ''
  fZone.value = s?.zoneId || ''
  fSub.value = s?.subZoneId || ''
  locId.value = s?.storageLocationId || ''
  movements.value = s ? await listMovements(s.id, 6) : []
})

async function saveLocation(clear = false) {
  if (!selected.value) return
  savingLoc.value = true
  try {
    let loc = { storageLocationId: '', storageLocationCode: '', zoneId: '', zoneName: '', subZoneId: '', subZoneName: '', locationLabel: '' }
    if (!clear) {
      const sl = storageLocs.value.find((x) => x.id === locId.value)
      if (!sl) {
        savingLoc.value = false
        return toast.error('보관위치를 선택하세요.')
      }
      loc = {
        storageLocationId: sl.id,
        storageLocationCode: sl.code,
        zoneId: sl.zoneId || '',
        zoneName: sl.zoneName || '',
        subZoneId: sl.subZoneId || '',
        subZoneName: sl.subZoneName || '',
        locationLabel: sl.locationLabel || sl.name || '',
      }
    } else {
      locId.value = ''
    }
    await skus.setLocation(selected.value.id, loc)
    const idx = list.value.findIndex((s) => s.id === selected.value.id)
    if (idx > -1) list.value[idx] = { ...list.value[idx], ...loc }
    toast.success(clear ? '위치가 삭제되었습니다.' : '위치가 저장되었습니다.')
  } catch (e) {
    toast.error('위치 저장 실패: ' + (e.message || e.code))
  } finally {
    savingLoc.value = false
  }
}

// SKU 코드 직접 입력(스캐너) → Enter 로 선택
function pickByCode() {
  const code = search.value.trim()
  const hit = list.value.find((s) => s.code.toLowerCase() === code.toLowerCase())
  if (hit) {
    selectedId.value = hit.id
    search.value = ''
  } else {
    toast.error('일치하는 SKU 코드가 없습니다.')
  }
}

async function submit() {
  if (!selected.value) return toast.error('SKU를 선택하세요.')
  const v = Number(qty.value)
  if (!Number.isFinite(v) || v < 0) return toast.error('수량을 올바르게 입력하세요.')
  if (cfg.value.mode === 'add' && v <= 0) return toast.error('수량은 1 이상이어야 합니다.')
  if (op.value === 'adjust' && !reason.value) return toast.error('조정 사유를 선택하세요.')

  working.value = true
  try {
    const r = await applyStock(selected.value.id, op.value, v, auth.actor, memo.value, reason.value)
    toast.success(`${cfg.value.title} 완료 · 재고 ${r.before} → ${r.after}개`)
    // 로컬 반영
    const idx = list.value.findIndex((s) => s.id === selected.value.id)
    if (idx > -1) list.value[idx] = { ...list.value[idx], qty: r.after }
    movements.value = await listMovements(selected.value.id, 6)
    if (cfg.value.mode === 'add') qty.value = 1
  } catch (e) {
    toast.error(e.message || '처리 실패')
  } finally {
    working.value = false
  }
}

const typeLabel = { in: '입고', out: '출고', adjust: '조정', audit: '실사' }
const fmtTime = fmtDateTime
</script>

<template>
  <div>
    <PageHeader :title="cfg.title" :subtitle="cfg.sub" />

    <div class="grid gap-4 lg:grid-cols-5">
      <!-- SKU 선택 -->
      <div class="card lg:col-span-3">
        <div class="flex flex-wrap items-center gap-2 border-b border-slate-100 p-3">
          <input
            v-model="search"
            class="input w-auto flex-1"
            placeholder="SKU코드/상품명 검색 (코드 입력 후 Enter=바로선택)"
            @keyup.enter="pickByCode"
          />
          <select v-model="filterComplex" class="input w-auto">
            <option value="">전체 단지</option>
            <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
        </div>
        <div class="max-h-[60vh] overflow-y-auto scrollbar-slim">
          <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
          <div v-else-if="!filtered.length" class="p-10 text-center text-sm text-slate-400">SKU가 없습니다.</div>
          <button
            v-for="s in filtered"
            :key="s.id"
            class="flex w-full items-center justify-between gap-2 border-b border-slate-50 px-4 py-2.5 text-left hover:bg-slate-50"
            :class="selectedId === s.id ? 'bg-brand-50' : ''"
            @click="selectedId = s.id"
          >
            <img :src="resolveImage(s)" class="h-10 w-10 shrink-0 rounded-lg border border-slate-100 object-cover" alt="" />
            <div class="min-w-0 flex-1">
              <span class="badge bg-brand-50 font-mono text-brand-700">{{ s.code }}</span>
              <span class="ml-1 text-sm font-medium text-slate-700">{{ s.productName }}</span>
              <p class="truncate text-xs text-slate-400">{{ s.spec }} · {{ s.pathLabel }}</p>
            </div>
            <span class="shrink-0 text-sm font-semibold" :class="s.qty <= 0 ? 'text-rose-500' : 'text-slate-600'">{{ s.qty }}개</span>
          </button>
        </div>
      </div>

      <!-- 작업 패널 -->
      <div class="lg:col-span-2">
        <div class="card sticky top-4 p-5">
          <div v-if="!selected" class="py-10 text-center text-sm text-slate-400">왼쪽에서 SKU를 선택하세요.</div>
          <template v-else>
            <img :src="resolveImage(selected)" class="mb-3 h-32 w-full rounded-lg border border-slate-100 bg-slate-50 object-cover" alt="" />
            <p class="font-mono text-lg font-bold text-slate-800">{{ selected.code }}</p>
            <p class="text-sm text-slate-600">{{ selected.productName }} <span v-if="selected.spec" class="text-slate-400">· {{ selected.spec }}</span></p>
            <p class="text-xs text-slate-400">{{ selected.pathLabel }}</p>

            <div class="my-4 rounded-lg bg-slate-50 py-3 text-center">
              <span class="text-xs text-slate-400">현재 재고</span>
              <p class="text-3xl font-extrabold" :class="selected.qty <= 0 ? 'text-rose-500' : 'text-brand-600'">{{ selected.qty }}<span class="text-base text-slate-400">개</span></p>
            </div>

            <label class="label">{{ cfg.qtyLabel }}</label>
            <input v-model.number="qty" type="number" min="0" class="input mb-3 text-lg" />

            <div v-if="op === 'adjust'" class="mb-3">
              <label class="label">조정 사유 *</label>
              <select v-model="reason" class="input">
                <option value="">사유 선택</option>
                <option v-for="r in REASONS" :key="r" :value="r">{{ r }}</option>
              </select>
            </div>

            <!-- 보관위치 지정: 단지(고정) › 구역 › 상세구역 › 보관위치 연쇄 선택 -->
            <div v-if="op === 'adjust'" class="mb-3 rounded-lg border border-slate-200 p-3">
              <p class="mb-2 text-xs font-semibold text-slate-500">보관위치 지정</p>
              <div class="mb-2 rounded bg-slate-50 px-2 py-1 text-[11px] text-slate-500">단지: <b>{{ selected.complexName }}</b> <span class="text-slate-400">(SKU 기준 고정)</span></div>
              <div class="grid grid-cols-2 gap-2">
                <select v-model="fZone" class="input" @change="onZoneChange">
                  <option value="">구역 전체</option>
                  <option v-for="z in zoneChoices" :key="z.id" :value="z.id">{{ z.name }}</option>
                </select>
                <select v-model="fSub" class="input" :disabled="!fZone" @change="onSubChange">
                  <option value="">상세구역 전체</option>
                  <option v-for="s in subChoices" :key="s.id" :value="s.id">{{ s.name }}</option>
                </select>
              </div>
              <select v-model="locId" class="input mt-2">
                <option value="">보관위치 선택</option>
                <option v-for="l in locOptions" :key="l.id" :value="l.id">
                  {{ l.code }} · {{ [l.zoneName, l.subZoneName, l.name].filter(Boolean).join(' › ') || '단지 전체' }}
                </option>
              </select>
              <p v-if="!locForComplex.length" class="mt-1 text-[11px] text-amber-600">이 단지에 등록된 보관위치가 없습니다. 보관위치관리에서 먼저 등록하세요.</p>
              <div class="mt-2 flex gap-2">
                <button class="btn-ghost btn-sm flex-1" :disabled="savingLoc" @click="saveLocation(false)">위치 저장</button>
                <button class="btn-ghost btn-sm text-rose-600" :disabled="savingLoc || !selected.storageLocationId" @click="saveLocation(true)">위치 삭제</button>
              </div>
              <p v-if="selected.locationLabel || selected.storageLocationCode" class="mt-1.5 text-[11px] text-slate-400">
                현재 위치: {{ selected.complexName }}<span v-if="selected.locationLabel"> › {{ selected.locationLabel }}</span>
                <span v-if="selected.storageLocationCode" class="font-mono"> ({{ selected.storageLocationCode }})</span>
              </p>
            </div>

            <label class="label">메모 (선택)</label>
            <input v-model="memo" class="input mb-4" placeholder="거래처/사유 등" />

            <button class="btn w-full py-3 text-base text-white" :class="cfg.btnClass" :disabled="working" @click="submit">
              {{ working ? '처리 중…' : cfg.btn }}
            </button>

            <div v-if="movements.length" class="mt-4">
              <p class="mb-1 text-xs font-semibold text-slate-500">최근 이력</p>
              <ul class="divide-y divide-slate-50 text-xs">
                <li v-for="m in movements" :key="m.id" class="flex items-center justify-between py-1.5">
                  <span class="flex items-center gap-1.5">
                    <span class="badge bg-slate-100 text-[10px]">{{ typeLabel[m.type] }}</span>
                    <span class="text-slate-500">{{ m.byName }}</span>
                  </span>
                  <span class="text-slate-400">{{ m.before }}→{{ m.after }} · {{ fmtTime(m.at) }}</span>
                </li>
              </ul>
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

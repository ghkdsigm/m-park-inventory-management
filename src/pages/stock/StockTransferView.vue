<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { skus, complexes, storageLocations, transferStock } from '@/services/db'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import Pager from '@/components/ui/Pager.vue'
import { resolveImage } from '@/utils/image'
import { specText } from '@/utils/sku'

const auth = useAuthStore()
const toast = useToast()

const loading = ref(true)
const rows = ref([])
const complexList = ref([])
const storageLocs = ref([])
const search = ref('')
const filterComplex = ref('')
const sizes = [10, 30, 50]
const page = ref(1)
const pageSize = ref(30)
const total = ref(0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

const selected = ref(null) // 출발 재고행(StockRow)
const qty = ref(1)
const reason = ref('')
const memo = ref('')
const working = ref(false)
const imgOpen = ref(false)
const REASONS = ['단지간 이동', '위치 정리/재배치', '반품 이동', '기타']

// 도착 보관위치 선택 (단지 → 구역 → 상세구역 → 보관위치)
const toComplex = ref('')
const toZone = ref('')
const toSub = ref('')
const toLoc = ref('')
const locForComplex = computed(() => storageLocs.value.filter((l) => l.complexId === toComplex.value))
const zoneChoices = computed(() => {
  const m = new Map()
  locForComplex.value.forEach((l) => l.zoneId && m.set(l.zoneId, l.zoneName))
  return [...m].map(([id, name]) => ({ id, name }))
})
const subChoices = computed(() => {
  const m = new Map()
  locForComplex.value.forEach((l) => { if (l.zoneId === toZone.value && l.subZoneId) m.set(l.subZoneId, l.subZoneName) })
  return [...m].map(([id, name]) => ({ id, name }))
})
const locOptions = computed(() => locForComplex.value.filter((l) => (!toZone.value || l.zoneId === toZone.value) && (!toSub.value || l.subZoneId === toSub.value)))
watch(toComplex, () => { toZone.value = ''; toSub.value = ''; toLoc.value = '' })
watch(toZone, () => { toSub.value = ''; toLoc.value = '' })

async function fetchPage() {
  loading.value = true
  try {
    const r = await skus.page({ complexId: filterComplex.value, search: search.value.trim(), page: page.value, pageSize: pageSize.value })
    rows.value = r.rows
    total.value = r.total
  } catch (e) { toast.error('불러오기 실패: ' + (e.message || e.code)) } finally { loading.value = false }
}
onMounted(async () => {
  try { [complexList.value, storageLocs.value] = await Promise.all([complexes.list(), storageLocations.list()]) } catch (e) { /* */ }
  await fetchPage()
})
watch(filterComplex, () => { page.value = 1; fetchPage() })
watch(pageSize, () => { page.value = 1; fetchPage() })
watch(page, fetchPage)
let searchTimer = null
watch(search, () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => { page.value = 1; fetchPage() }, 350) })

function selectRow(s) {
  selected.value = s
  qty.value = s.qty
  reason.value = ''; memo.value = ''
  toComplex.value = ''; toZone.value = ''; toSub.value = ''; toLoc.value = ''
}

async function submit() {
  if (!auth.canStock) return toast.error('재고이동 권한이 없습니다. 관리자에게 문의하세요.')
  if (!selected.value) return toast.error('이동할 재고를 선택하세요.')
  if (!toLoc.value) return toast.error('도착 보관위치를 선택하세요.')
  if (toLoc.value === selected.value.storageLocationId) return toast.error('출발지와 도착지가 같습니다.')
  if (!reason.value) return toast.error('사유를 선택하세요.')
  const v = Number(qty.value)
  if (!Number.isFinite(v) || v <= 0) return toast.error('이동 수량을 입력하세요.')
  if (v > selected.value.qty) return toast.error(`재고 부족: 현재 ${selected.value.qty}개`)

  working.value = true
  try {
    const r = await transferStock({
      stockId: selected.value.stockId,
      toStorageLocationId: toLoc.value,
      qty: v,
      reason: reason.value === '기타' ? (memo.value || '기타') : reason.value,
    })
    toast.success(r.relocated ? '위치가 이동되었습니다.' : `재고이동 완료 · ${v}개`)
    selected.value = null
    await fetchPage()
  } catch (e) { toast.error(e.message || '이동 실패') } finally { working.value = false }
}
</script>

<template>
  <div>
    <PageHeader title="재고이동" subtitle="재고(위치)를 골라 다른 보관위치로 이동합니다. 다른 단지로 옮기면 출고+입고가 자동 기록됩니다." />
    <div class="grid gap-4 lg:grid-cols-5">
      <!-- 출발 재고행 -->
      <div class="card lg:col-span-3">
        <div class="flex flex-wrap items-center gap-2 border-b border-slate-100 p-3">
          <select v-model="filterComplex" class="input w-auto">
            <option value="">전체 단지</option>
            <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
          <input v-model="search" class="input w-full flex-1 sm:w-auto" placeholder="SKU코드 / 상품명 검색" />
          <select v-model="pageSize" class="input w-auto sm:ml-auto">
            <option v-for="n in sizes" :key="n" :value="n">{{ n }}개씩</option>
          </select>
        </div>
        <div class="max-h-[60vh] overflow-y-auto scrollbar-slim">
          <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
          <div v-else-if="!rows.length" class="p-10 text-center text-sm text-slate-400">재고가 없습니다.</div>
          <button v-for="s in rows" :key="s.stockId" class="flex w-full items-center justify-between gap-2 border-b border-slate-50 px-4 py-2.5 text-left hover:bg-slate-50"
            :class="selected?.stockId === s.stockId ? 'bg-brand-50' : ''" @click="selectRow(s)">
            <img :src="resolveImage(s)" class="h-10 w-10 shrink-0 rounded-lg border border-slate-100 object-cover" alt="" />
            <div class="min-w-0 flex-1">
              <span class="badge bg-brand-50 font-mono text-brand-700">{{ s.code }}</span>
              <span class="ml-1 text-sm font-medium text-slate-700">{{ s.productName }}</span>
              <p class="truncate text-xs text-slate-400">📍 {{ s.complexName }}<span v-if="s.locationLabel"> › {{ s.locationLabel }}</span></p>
            </div>
            <span class="shrink-0 text-sm font-semibold" :class="s.qty <= 0 ? 'text-rose-500' : 'text-slate-600'">{{ s.qty }}개</span>
          </button>
        </div>
        <Pager v-if="total" v-model:page="page" :total="total" :total-pages="totalPages" class="border-t border-slate-100" />
      </div>

      <!-- 이동 패널 -->
      <div class="lg:col-span-2">
        <div class="card sticky top-4 p-5">
          <div v-if="!selected" class="py-10 text-center text-sm text-slate-400">왼쪽에서 이동할 재고를 선택하세요.</div>
          <template v-else>
            <img :src="resolveImage(selected)" class="mb-3 h-40 w-full cursor-zoom-in rounded-lg border border-slate-100 bg-slate-50 object-contain p-1" alt="상품 이미지" title="클릭하면 크게 보기" @click="imgOpen = true" />
            <p class="font-mono text-lg font-bold text-slate-800">{{ selected.code }}</p>
            <p class="text-sm text-slate-600">{{ selected.productName }} <span v-if="specText(selected)" class="text-slate-400">· {{ specText(selected) }}</span></p>
            <div class="my-4 flex items-center justify-between rounded-lg bg-slate-50 px-3 py-3">
              <div class="text-center">
                <span class="text-[11px] text-slate-400">출발</span>
                <p class="text-sm font-semibold text-slate-700">{{ selected.complexName }}</p>
                <p class="text-[11px] text-slate-400">{{ selected.locationLabel || '위치 미지정' }}</p>
              </div>
              <svg class="h-5 w-5 text-slate-300" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12h14M13 6l6 6-6 6" stroke-linecap="round" stroke-linejoin="round" /></svg>
              <div class="text-center">
                <span class="text-[11px] text-slate-400">현재 재고</span>
                <p class="text-2xl font-extrabold text-brand-600">{{ selected.qty }}<span class="text-sm text-slate-400">개</span></p>
              </div>
            </div>

            <div class="mb-3 rounded-lg border border-slate-200 p-3">
              <p class="mb-2 text-xs font-semibold text-slate-500">도착 보관위치 <span class="text-rose-500">*</span></p>
              <select v-model="toComplex" class="input mb-2">
                <option value="">단지 선택</option>
                <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option>
              </select>
              <div class="grid grid-cols-2 gap-2">
                <select v-model="toZone" class="input" :disabled="!toComplex">
                  <option value="">구역 전체</option>
                  <option v-for="z in zoneChoices" :key="z.id" :value="z.id">{{ z.name }}</option>
                </select>
                <select v-model="toSub" class="input" :disabled="!toZone">
                  <option value="">상세구역 전체</option>
                  <option v-for="sz in subChoices" :key="sz.id" :value="sz.id">{{ sz.name }}</option>
                </select>
              </div>
              <select v-model="toLoc" class="input mt-2">
                <option value="">보관위치 선택</option>
                <option v-for="l in locOptions" :key="l.id" :value="l.id">{{ l.code }} · {{ [l.zoneName, l.subZoneName, l.name].filter(Boolean).join(' › ') || '단지 전체' }}</option>
              </select>
              <p v-if="toComplex && !locForComplex.length" class="mt-1 text-[11px] text-amber-600">이 단지에 보관위치가 없습니다.</p>
            </div>

            <label class="label">이동 수량 * <span class="font-normal text-slate-400">(최대 {{ selected.qty }})</span></label>
            <input v-model.number="qty" type="number" min="1" :max="selected.qty" class="input mb-3 text-lg" />

            <div class="mb-3">
              <label class="label">사유 / 구분 *</label>
              <select v-model="reason" class="input">
                <option value="">사유 선택</option>
                <option v-for="r in REASONS" :key="r" :value="r">{{ r }}</option>
              </select>
            </div>
            <div v-if="reason === '기타'" class="mb-4">
              <label class="label">상세 사유</label>
              <input v-model="memo" class="input" placeholder="상세 사유를 입력하세요" />
            </div>

            <button class="btn w-full bg-indigo-600 py-3 text-base text-white hover:bg-indigo-700" :disabled="working" @click="submit">
              {{ working ? '처리 중…' : '재고 이동' }}
            </button>
          </template>
        </div>
      </div>
    </div>
    <Teleport to="body">
      <div v-if="imgOpen && selected" class="fixed inset-0 z-[60] flex items-center justify-center bg-black/80 p-4" @click="imgOpen = false">
        <img :src="resolveImage(selected)" class="max-h-[90vh] max-w-full rounded-lg object-contain" alt="" />
        <button class="absolute right-4 top-4 rounded-full bg-white/20 p-2 text-white hover:bg-white/30" @click.stop="imgOpen = false">
          <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 6l12 12M18 6L6 18" stroke-linecap="round" /></svg>
        </button>
      </div>
    </Teleport>
  </div>
</template>

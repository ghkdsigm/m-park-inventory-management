<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { skus, complexes, categories, productCodes, productDetails, storageLocations, applyStock, listMovements, voidMovement } from '@/services/db'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import Pager from '@/components/ui/Pager.vue'
import BaseModal from '@/components/ui/BaseModal.vue'
import { resolveImage } from '@/utils/image'
import { fmtDateTime } from '@/utils/date'
import { specText } from '@/utils/sku'

const route = useRoute()
const auth = useAuthStore()
const toast = useToast()

const OP = {
  in: { title: '입고관리', sub: 'SKU를 선택하고 입고 수량을 입력하세요.', qtyLabel: '신규 입고 수량', btn: '입고 처리', btnClass: 'bg-emerald-600 hover:bg-emerald-700', mode: 'add' },
  out: { title: '출고관리', sub: 'SKU를 선택하고 출고 수량을 입력하세요.', qtyLabel: '신규 출고 수량', btn: '출고 처리', btnClass: 'bg-sky-600 hover:bg-sky-700', mode: 'add' },
  adjust: { title: '재고조정', sub: '실제 재고와 시스템 재고가 다를 때 보정합니다.', qtyLabel: '조정 후 재고수량', btn: '재고 조정', btnClass: 'bg-amber-500 hover:bg-amber-600', mode: 'set' },
  audit: { title: '재고실사', sub: '실물 카운트 결과를 입력해 재고를 확정합니다.', qtyLabel: '실사 재고수량', btn: '실사 확정', btnClass: 'bg-violet-600 hover:bg-violet-700', mode: 'set' },
}
const op = computed(() => route.meta.op)
const cfg = computed(() => OP[op.value])

// 작업별 사유/구분 (필수)
const ADJUST_REASONS = ['위치 지정', '위치 지정 변경', '파손', '분실', '도난', '오입력 정정', '유통기한 경과', '입고 오류', '기타']
const IN_REASONS = ['구매입고', '반품입고', '이동입고', '재고보충', '생산입고', '기타']
const OUT_REASONS = ['판매/사용', '폐기', '반품출고', '이동출고', '샘플/전시', '기타']
const reason = ref('')
const reasonOptions = computed(() => (op.value === 'in' ? IN_REASONS : op.value === 'out' ? OUT_REASONS : ADJUST_REASONS))
const reasonLabel = computed(() => (op.value === 'adjust' ? '조정 사유' : '사유 / 구분'))

const loading = ref(true)
const rows = ref([])
const complexList = ref([])
const categoryList = ref([])
const productCodeList = ref([])
const productDetailList = ref([])
const search = ref('')
// 서버 페이징
const sizes = [10, 30, 50]
const page = ref(1)
const pageSize = ref(30)
const total = ref(0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
const filterComplex = ref('')
const fCategory = ref('')
const fProductCode = ref('')
const fProductDetail = ref('')
const catOptions = computed(() => (filterComplex.value ? categoryList.value.filter((c) => c.complexId === filterComplex.value) : categoryList.value))
const pcOptions = computed(() => (fCategory.value ? productCodeList.value.filter((p) => p.categoryId === fCategory.value) : productCodeList.value))
const pdOptions = computed(() => (fProductCode.value ? productDetailList.value.filter((d) => d.productCodeId === fProductCode.value) : productDetailList.value))
watch(filterComplex, () => { fCategory.value = ''; fProductCode.value = ''; fProductDetail.value = '' })
watch(fCategory, () => { fProductCode.value = ''; fProductDetail.value = '' })
watch(fProductCode, () => { fProductDetail.value = '' })
const selectedSku = ref(null)
const qty = ref(1)
const memo = ref('')
const working = ref(false)
const movements = ref([])
const imgModal = ref(false)

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

async function fetchPage() {
  loading.value = true
  try {
    const r = await skus.page({
      complexId: filterComplex.value,
      categoryId: fCategory.value,
      productCodeId: fProductCode.value,
      productDetailId: fProductDetail.value,
      search: search.value.trim(),
      page: page.value,
      pageSize: pageSize.value,
    })
    rows.value = r.rows
    total.value = r.total
  } catch (e) {
    toast.error('불러오기 실패: ' + (e.message || e.code))
  } finally {
    loading.value = false
  }
}
async function loadMasters() {
  try {
    ;[complexList.value, storageLocs.value, categoryList.value, productCodeList.value, productDetailList.value] =
      await Promise.all([complexes.list(), storageLocations.list(), categories.list(), productCodes.list(), productDetails.list()])
  } catch (e) {
    /* 옵션 로드 실패 무시 */
  }
}
onMounted(async () => { await loadMasters(); await fetchPage() })

watch([filterComplex, fCategory, fProductCode, fProductDetail], () => { page.value = 1; fetchPage() })
watch(pageSize, () => { page.value = 1; fetchPage() })
watch(page, fetchPage)
let searchTimer = null
watch(search, () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => { page.value = 1; fetchPage() }, 350) })

watch(op, () => { selectedSku.value = null; movements.value = [] })

const selected = selectedSku
function selectSku(s) { selectedSku.value = s }

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
    selectedSku.value = { ...selectedSku.value, ...loc }
    const idx = rows.value.findIndex((s) => s.id === selectedSku.value.id)
    if (idx > -1) rows.value[idx] = { ...rows.value[idx], ...loc }
    toast.success(clear ? '위치가 삭제되었습니다.' : '위치가 저장되었습니다.')
  } catch (e) {
    toast.error('위치 저장 실패: ' + (e.message || e.code))
  } finally {
    savingLoc.value = false
  }
}

// SKU 코드 직접 입력(스캐너) → Enter 로 선택 (서버 조회)
async function pickByCode() {
  const code = search.value.trim()
  if (!code) return
  try {
    const hit = await skus.getByCode(code)
    if (hit) {
      selectedSku.value = hit
      search.value = ''
    } else {
      toast.error('일치하는 SKU 코드가 없습니다.')
    }
  } catch (e) {
    toast.error('조회 실패: ' + (e.message || e.code))
  }
}

async function submit() {
  if ((op.value === 'in' || op.value === 'out') && !auth.canStock) return toast.error('입/출고 권한이 없습니다. 관리자에게 문의하세요.')
  if (!selected.value) return toast.error('SKU를 선택하세요.')
  const v = Number(qty.value)
  if (!Number.isFinite(v) || v < 0) return toast.error('수량을 올바르게 입력하세요.')
  if (cfg.value.mode === 'add' && v <= 0) return toast.error('수량은 1 이상이어야 합니다.')
  if (!reason.value) return toast.error('사유를 선택하세요.')
  // 메모(거래처/사유)는 "기타" 일 때만 의미 있음
  const memoVal = reason.value === '기타' ? memo.value : ''

  working.value = true
  try {
    const skuId = selected.value.id
    const r = await applyStock(skuId, op.value, v, auth.actor, memoVal, reason.value)
    toast.success(`${cfg.value.title} 완료 · 재고 ${r.before} → ${r.after}개`)
    // 로컬 반영
    if (selectedSku.value && selectedSku.value.id === skuId) selectedSku.value = { ...selectedSku.value, qty: r.after }
    const idx = rows.value.findIndex((s) => s.id === skuId)
    if (idx > -1) rows.value[idx] = { ...rows.value[idx], qty: r.after }
    movements.value = await listMovements(skuId, 6)
    if (cfg.value.mode === 'add') qty.value = 1
  } catch (e) {
    toast.error(e.message || '처리 실패')
  } finally {
    working.value = false
  }
}

const typeLabel = { in: '입고', out: '출고', adjust: '조정', audit: '실사', void: '취소' }
const fmtTime = fmtDateTime

/* ---------- 처리 취소(역분개) ---------- */
const VOID_REASONS = ['수량 오기입', '방향 오선택(입출고 바뀜)', '중복 처리', '기타']
const voidTarget = ref(null)
const voidReason = ref('')
const voidMemo = ref('')
const voiding = ref(false)
const helpOpen = ref(false)

function isToday(at) {
  if (!at) return false
  const d = new Date(at)
  const now = new Date()
  return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
}
// 본인 등록 + 당일 + 미취소 + 취소전표 아님 → 현장 취소 가능
function canVoid(m) {
  return m.byUserId === auth.user?.id && m.type !== 'void' && !m.voided && isToday(m.at)
}
function openVoid(m) {
  voidTarget.value = m
  voidReason.value = ''
  voidMemo.value = ''
}
async function confirmVoid() {
  if (!voidTarget.value) return
  if (!voidReason.value) return toast.error('취소 사유를 선택하세요.')
  const reasonText = voidReason.value === '기타' ? (voidMemo.value || '').trim() : voidReason.value
  if (voidReason.value === '기타' && !reasonText) return toast.error('취소 사유를 입력하세요.')
  voiding.value = true
  try {
    const r = await voidMovement(voidTarget.value.id, reasonText)
    toast.success(`처리를 취소했습니다 · 재고 ${r.before}→${r.after}개`)
    const skuId = voidTarget.value.skuId
    // 로컬 재고 반영
    if (selectedSku.value && selectedSku.value.id === skuId) selectedSku.value = { ...selectedSku.value, qty: r.after }
    const idx = rows.value.findIndex((s) => s.id === skuId)
    if (idx > -1) rows.value[idx] = { ...rows.value[idx], qty: r.after }
    voidTarget.value = null
    if (selected.value) movements.value = await listMovements(selected.value.id, 6)
  } catch (e) {
    toast.error(e.message || '취소 실패')
  } finally {
    voiding.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader :title="cfg.title" :subtitle="cfg.sub" />

    <div class="grid gap-4 lg:grid-cols-5">
      <!-- SKU 선택 -->
      <div class="card lg:col-span-3">
        <div class="flex flex-wrap items-center gap-2 border-b border-slate-100 p-3">
          <select v-model="filterComplex" class="input w-auto">
            <option value="">전체 단지</option>
            <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
          <select v-model="fCategory" class="input w-auto">
            <option value="">전체 카테고리</option>
            <option v-for="c in catOptions" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
          <select v-model="fProductCode" class="input w-auto">
            <option value="">전체 제품코드</option>
            <option v-for="p in pcOptions" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
          <select v-model="fProductDetail" class="input w-auto">
            <option value="">전체 상세코드</option>
            <option v-for="d in pdOptions" :key="d.id" :value="d.id">{{ d.name }}</option>
          </select>
          <input
            v-model="search"
            class="input w-full flex-1 sm:w-auto"
            placeholder="SKU코드/상품명 검색 (코드 입력 후 Enter=바로선택)"
            @keyup.enter="pickByCode"
          />
          <select v-model="pageSize" class="input w-auto sm:ml-auto">
            <option v-for="n in sizes" :key="n" :value="n">{{ n }}개씩</option>
          </select>
        </div>
        <div class="max-h-[60vh] overflow-y-auto scrollbar-slim">
          <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
          <div v-else-if="!rows.length" class="p-10 text-center text-sm text-slate-400">SKU가 없습니다.</div>
          <button
            v-for="s in rows"
            :key="s.id"
            class="flex w-full items-center justify-between gap-2 border-b border-slate-50 px-4 py-2.5 text-left hover:bg-slate-50"
            :class="selected?.id === s.id ? 'bg-brand-50' : ''"
            @click="selectSku(s)"
          >
            <img :src="resolveImage(s)" class="h-10 w-10 shrink-0 rounded-lg border border-slate-100 object-cover" alt="" />
            <div class="min-w-0 flex-1">
              <span class="badge bg-brand-50 font-mono text-brand-700">{{ s.code }}</span>
              <span class="ml-1 text-sm font-medium text-slate-700">{{ s.productName }}</span>
              <p class="truncate text-xs text-slate-400">{{ specText(s) ? specText(s) + ' · ' : '' }}{{ s.pathLabel }}</p>
            </div>
            <span class="shrink-0 text-sm font-semibold" :class="s.qty <= 0 ? 'text-rose-500' : 'text-slate-600'">{{ s.qty }}개</span>
          </button>
        </div>
        <Pager v-if="total" v-model:page="page" :total="total" :total-pages="totalPages" class="border-t border-slate-100" />
      </div>

      <!-- 작업 패널 -->
      <div class="lg:col-span-2">
        <div class="card sticky top-4 p-5">
          <div v-if="!selected" class="py-10 text-center text-sm text-slate-400">왼쪽에서 SKU를 선택하세요.</div>
          <template v-else>
            <img :src="resolveImage(selected)" class="mb-3 h-48 w-full cursor-zoom-in rounded-lg border border-slate-100 bg-slate-50 object-contain p-1" alt="" title="클릭하면 크게 보기" @click="imgModal = true" />
            <p class="font-mono text-lg font-bold text-slate-800">{{ selected.code }}</p>
            <p class="text-sm text-slate-600">{{ selected.productName }} <span v-if="specText(selected)" class="text-slate-400">· {{ specText(selected) }}</span></p>
            <p class="text-xs text-slate-400">{{ selected.pathLabel }}</p>

            <div class="my-4 rounded-lg bg-slate-50 py-3 text-center">
              <span class="text-xs text-slate-400">현재 재고</span>
              <p class="text-3xl font-extrabold" :class="selected.qty <= 0 ? 'text-rose-500' : 'text-brand-600'">{{ selected.qty }}<span class="text-base text-slate-400">개</span></p>
            </div>

            <label class="label">{{ cfg.qtyLabel }}</label>
            <input v-model.number="qty" type="number" min="0" class="input mb-3 text-lg" />

            <div class="mb-3">
              <label class="label">{{ reasonLabel }} *</label>
              <select v-model="reason" class="input">
                <option value="">사유 선택</option>
                <option v-for="r in reasonOptions" :key="r" :value="r">{{ r }}</option>
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

            <div v-if="reason === '기타'" class="mb-4">
              <label class="label">거래처 / 사유</label>
              <input v-model="memo" class="input" placeholder="거래처명 또는 상세 사유를 입력하세요" />
            </div>

            <button class="btn w-full py-3 text-base text-white" :class="cfg.btnClass" :disabled="working" @click="submit">
              {{ working ? '처리 중…' : cfg.btn }}
            </button>

            <div v-if="movements.length" class="mt-4">
              <div class="mb-1 flex items-center justify-between">
                <p class="text-xs font-semibold text-slate-500">최근 이력</p>
                <button
                  class="flex h-5 w-5 items-center justify-center rounded-full bg-slate-100 text-[11px] font-bold text-slate-500 hover:bg-slate-200"
                  title="취소 안내"
                  @click="helpOpen = true"
                >?</button>
              </div>
              <ul class="divide-y divide-slate-50 text-xs">
                <li v-for="m in movements" :key="m.id" class="flex items-center justify-between gap-2 py-1.5">
                  <span class="flex min-w-0 flex-wrap items-center gap-1.5">
                    <span class="badge text-[10px]" :class="m.type === 'void' ? 'bg-rose-50 text-rose-600' : 'bg-slate-100'">{{ typeLabel[m.type] }}</span>
                    <span v-if="m.voided" class="badge bg-slate-100 text-[10px] text-slate-400 line-through">취소됨</span>
                    <span class="text-slate-500">{{ m.byName }}</span>
                    <span v-if="m.reason" class="text-slate-400">· {{ m.reason }}</span>
                  </span>
                  <span class="flex shrink-0 items-center gap-2">
                    <span class="text-slate-400">{{ m.before }}→{{ m.after }} · {{ fmtTime(m.at) }}</span>
                    <button
                      v-if="canVoid(m)"
                      class="rounded-md border border-rose-200 px-1.5 py-0.5 text-[10px] font-medium text-rose-600 hover:bg-rose-50"
                      @click="openVoid(m)"
                    >취소</button>
                  </span>
                </li>
              </ul>
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- 이미지 크게 보기 -->
    <Teleport to="body">
      <Transition name="fade">
        <div
          v-if="imgModal && selected"
          class="fixed inset-0 z-[60] flex items-center justify-center bg-black/80 p-4"
          @click="imgModal = false"
        >
          <img :src="resolveImage(selected)" class="max-h-[90vh] max-w-full rounded-lg object-contain" alt="" />
          <button class="absolute right-4 top-4 rounded-full bg-white/20 p-2 text-white hover:bg-white/30" @click.stop="imgModal = false">
            <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 6l12 12M18 6L6 18" stroke-linecap="round" /></svg>
          </button>
        </div>
      </Transition>
    </Teleport>

    <!-- 처리 취소 모달 -->
    <BaseModal :model-value="!!voidTarget" size="sm" title="처리 취소" @update:model-value="voidTarget = null">
      <div v-if="voidTarget" class="space-y-3">
        <div class="rounded-lg bg-slate-50 p-3 text-sm">
          <p><span class="badge bg-slate-100 text-[10px]">{{ typeLabel[voidTarget.type] }}</span> <b>{{ voidTarget.qty }}개</b> · {{ voidTarget.skuCode }}</p>
          <p class="mt-1 text-xs text-slate-500">{{ voidTarget.before }}→{{ voidTarget.after }}개 · {{ fmtTime(voidTarget.at) }}</p>
          <p class="mt-1 text-xs text-slate-400">취소하면 이 처리를 되돌리는 역분개가 기록되며, 원래 이력은 보존됩니다.</p>
        </div>
        <div>
          <label class="label">취소 사유 *</label>
          <select v-model="voidReason" class="input">
            <option value="">사유 선택</option>
            <option v-for="r in VOID_REASONS" :key="r" :value="r">{{ r }}</option>
          </select>
        </div>
        <input v-if="voidReason === '기타'" v-model="voidMemo" class="input" placeholder="상세 사유를 입력하세요" />
      </div>
      <template #footer>
        <button class="btn-ghost" :disabled="voiding" @click="voidTarget = null">닫기</button>
        <button class="btn bg-rose-600 text-white hover:bg-rose-700" :disabled="voiding" @click="confirmVoid">{{ voiding ? '취소 중…' : '취소 확정' }}</button>
      </template>
    </BaseModal>

    <!-- 취소 안내 팝업 -->
    <BaseModal v-model="helpOpen" size="sm" title="처리 취소 안내">
      <div class="space-y-3 text-sm text-slate-600">
        <p>현장에서 <b>본인이 등록한 당일 처리</b>만 직접 취소할 수 있습니다.</p>
        <p>다음의 경우에는 취소 버튼이 보이지 않거나 취소가 거부됩니다:</p>
        <ul class="list-disc space-y-1 pl-5 text-slate-500">
          <li>처리한 날짜가 <b>지난</b> 경우 (당일 한정)</li>
          <li>다른 담당자가 등록한 처리</li>
          <li>그 사이 <b>재고가 변동</b>되어 되돌리면 수량이 맞지 않는 경우</li>
        </ul>
        <p class="rounded-lg bg-amber-50 p-3 text-amber-700">
          이때는 <b>관리자에게 재고 정정을 요청</b>하세요. 관리자는 재고조정/실사로 바로잡을 수 있습니다.
        </p>
      </div>
      <template #footer>
        <button class="btn-primary" @click="helpOpen = false">확인</button>
      </template>
    </BaseModal>
  </div>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>

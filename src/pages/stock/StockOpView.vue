<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import {
  skus, categories, productCodes, complexes, storageLocations,
  inboundStock, outboundStock, adjustStock, listMovements, voidMovement,
} from '@/services/db'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import Pager from '@/components/ui/Pager.vue'
import { resolveImage } from '@/utils/image'
import { fmtDateTime } from '@/utils/date'
import { specText } from '@/utils/sku'

const route = useRoute()
const auth = useAuthStore()
const toast = useToast()

const OP = {
  in:     { title: '입고관리', sub: 'SKU와 보관위치를 선택해 입고합니다. (위치 필수)', qtyLabel: '입고 수량', btn: '입고 처리', btnClass: 'bg-emerald-600 hover:bg-emerald-700' },
  out:    { title: '출고관리', sub: '재고(위치)를 선택해 출고합니다.',                qtyLabel: '출고 수량', btn: '출고 처리', btnClass: 'bg-sky-600 hover:bg-sky-700' },
  adjust: { title: '재고조정', sub: '재고(위치)별 수량을 보정합니다.',                qtyLabel: '조정 후 수량', btn: '재고 조정', btnClass: 'bg-amber-500 hover:bg-amber-600' },
  audit:  { title: '재고실사', sub: '실물 카운트로 재고(위치)를 확정합니다.',          qtyLabel: '실사 수량', btn: '실사 확정', btnClass: 'bg-violet-600 hover:bg-violet-700' },
}
const op = computed(() => route.meta.op)
const cfg = computed(() => OP[op.value])
const isInbound = computed(() => op.value === 'in')
const isSet = computed(() => op.value === 'adjust' || op.value === 'audit')

const IN_REASONS = ['구매입고', '반품입고', '생산입고', '재고보충', '기타']
const OUT_REASONS = ['판매/사용', '폐기', '반품출고', '샘플/전시', '기타']
const ADJUST_REASONS = ['파손', '분실', '도난', '오입력 정정', '유통기한 경과', '기타']
const reason = ref('')
const reasonOptions = computed(() => (isInbound.value ? IN_REASONS : op.value === 'out' ? OUT_REASONS : ADJUST_REASONS))

// 출고 상세
const requestDept = ref('')
const requester = ref('')
const handler = ref('')

const loading = ref(true)
const search = ref('')
const qty = ref(1)
const memo = ref('')
const working = ref(false)
const selected = ref(null) // 입고=변형 SKU, 그외=재고행(StockRow)
const movements = ref([])
const imgOpen = ref(false)

const variants = ref([])   // 입고용 SKU(변형) 목록
const locBySku = ref({})   // skuId → 현재 재고 위치들(재고행)
const rows = ref([])       // 출고/조정/실사용 재고행 목록
const complexList = ref([])
const storageLocs = ref([])
const categoryList = ref([])
const productCodeList = ref([])

const filterComplex = ref('')
const fCategory = ref('')
const fProductCode = ref('')
const pcOptions = computed(() => (fCategory.value ? productCodeList.value.filter((p) => p.categoryId === fCategory.value) : productCodeList.value))
watch(fCategory, () => { fProductCode.value = '' })

// 서버 페이징 (재고행)
const sizes = [10, 30, 50]
const page = ref(1)
const pageSize = ref(30)
const total = ref(0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

// 입고용 위치 선택 (단지 → 구역 → 상세구역 → 보관위치) — 필수
const inComplex = ref('')
const inZone = ref('')
const inSub = ref('')
const inLoc = ref('')
// 입고: 재고창고/공용만 (사용처 전용 제외)
const locForComplex = computed(() => storageLocs.value.filter((l) => l.complexId === inComplex.value && l.type !== 'usage'))
const zoneChoices = computed(() => {
  const m = new Map()
  locForComplex.value.forEach((l) => l.zoneId && m.set(l.zoneId, l.zoneName))
  return [...m].map(([id, name]) => ({ id, name }))
})
const subChoices = computed(() => {
  const m = new Map()
  locForComplex.value.forEach((l) => { if (l.zoneId === inZone.value && l.subZoneId) m.set(l.subZoneId, l.subZoneName) })
  return [...m].map(([id, name]) => ({ id, name }))
})
const locOptions = computed(() => locForComplex.value.filter((l) => (!inZone.value || l.zoneId === inZone.value) && (!inSub.value || l.subZoneId === inSub.value)))
watch(inComplex, () => { inZone.value = ''; inSub.value = ''; inLoc.value = '' })
watch(inZone, () => { inSub.value = ''; inLoc.value = '' })

// 출고 사용처 선택 (사용처/공용 위치코드, 재고창고 전용 제외)
const outComplex = ref('')
const outZone = ref('')
const outSub = ref('')
const outLoc = ref('')
const outLocForComplex = computed(() => storageLocs.value.filter((l) => l.complexId === outComplex.value && l.type !== 'warehouse'))
const outZoneChoices = computed(() => {
  const m = new Map()
  outLocForComplex.value.forEach((l) => l.zoneId && m.set(l.zoneId, l.zoneName))
  return [...m].map(([id, name]) => ({ id, name }))
})
const outSubChoices = computed(() => {
  const m = new Map()
  outLocForComplex.value.forEach((l) => { if (l.zoneId === outZone.value && l.subZoneId) m.set(l.subZoneId, l.subZoneName) })
  return [...m].map(([id, name]) => ({ id, name }))
})
const outLocOptions = computed(() => outLocForComplex.value.filter((l) => (!outZone.value || l.zoneId === outZone.value) && (!outSub.value || l.subZoneId === outSub.value)))
watch(outComplex, () => { outZone.value = ''; outSub.value = ''; outLoc.value = '' })
watch(outZone, () => { outSub.value = ''; outLoc.value = '' })
const outLocLabel = computed(() => {
  const l = storageLocs.value.find((x) => x.id === outLoc.value)
  if (!l) return ''
  const path = [l.complexName, l.locationLabel || l.name].filter(Boolean).join(' › ')
  return l.code ? `${path} (${l.code})` : path
})
// 폐기/반품출고는 사용처 개념이 없어 숨김
const NO_USAGE_REASONS = ['폐기', '반품출고']
const showUsage = computed(() => op.value === 'out' && !NO_USAGE_REASONS.includes(reason.value))
watch(reason, () => { if (!showUsage.value) { outComplex.value = ''; outZone.value = ''; outSub.value = ''; outLoc.value = '' } })

// 재고행 필터/조회
const filteredVariants = computed(() =>
  variants.value.filter((v) =>
    (!fCategory.value || v.categoryId === fCategory.value) &&
    (!fProductCode.value || v.productCodeId === fProductCode.value) &&
    (!search.value || (`${v.code} ${v.productName} ${v.spec || ''}`).toLowerCase().includes(search.value.toLowerCase()))
  )
)

async function loadMasters() {
  try {
    ;[complexList.value, storageLocs.value, categoryList.value, productCodeList.value] =
      await Promise.all([complexes.list(), storageLocations.list(), categories.list(), productCodes.list()])
  } catch (e) { /* 무시 */ }
}
async function loadVariants() { try { variants.value = await skus.list() } catch (e) { /* */ } }
async function loadInboundLocations() {
  try {
    const r = await skus.page({ pageSize: 1000 })
    const map = {}
    ;(r.rows || []).forEach((row) => { if (row.qty > 0) (map[row.skuId] ||= []).push(row) })
    locBySku.value = map
  } catch (e) { /* 위치 로드 실패 무시 */ }
}
function locInfo(s) {
  const arr = locBySku.value[s.id] || []
  if (!arr.length) return { text: '재고 없음', muted: true }
  if (arr.length === 1) {
    const l = arr[0]
    return { text: (l.complexName || '') + (l.locationLabel ? ' › ' + l.locationLabel : '') || '위치 미지정', muted: false }
  }
  return { text: `여러 위치 (${arr.length}곳)`, muted: false }
}
async function fetchRows() {
  loading.value = true
  try {
    const r = await skus.page({
      complexId: filterComplex.value, categoryId: fCategory.value, productCodeId: fProductCode.value,
      search: search.value.trim(), page: page.value, pageSize: pageSize.value,
    })
    rows.value = r.rows
    total.value = r.total
  } catch (e) { toast.error('불러오기 실패: ' + (e.message || e.code)) } finally { loading.value = false }
}

async function reload() {
  selected.value = null; movements.value = []
  loading.value = true
  if (isInbound.value) { await loadVariants(); await loadInboundLocations(); loading.value = false }
  else await fetchRows()
}
onMounted(async () => { await loadMasters(); await reload() })
watch(op, reload)
watch([filterComplex, fCategory, fProductCode], () => { if (!isInbound.value) { page.value = 1; fetchRows() } })
watch(pageSize, () => { if (!isInbound.value) { page.value = 1; fetchRows() } })
watch(page, () => { if (!isInbound.value) fetchRows() })
let searchTimer = null
watch(search, () => { if (!isInbound.value) { clearTimeout(searchTimer); searchTimer = setTimeout(() => { page.value = 1; fetchRows() }, 350) } })

async function selectItem(x) {
  selected.value = x
  qty.value = isSet.value ? (x.qty || 0) : 1
  memo.value = ''; reason.value = ''
  requestDept.value = ''; requester.value = ''
  handler.value = auth.user?.name || ''
  outComplex.value = ''; outZone.value = ''; outSub.value = ''; outLoc.value = ''
  const skuId = x.skuId || x.id
  movements.value = skuId ? await listMovements(skuId, 6) : []
}

async function submit() {
  if (!selected.value) return toast.error(isInbound.value ? 'SKU를 선택하세요.' : '재고를 선택하세요.')
  const v = Number(qty.value)
  if (!Number.isFinite(v) || v < 0) return toast.error('수량을 올바르게 입력하세요.')
  if (!isSet.value && v <= 0) return toast.error('수량은 1 이상이어야 합니다.')
  if (!reason.value) return toast.error('사유를 선택하세요.')
  const memoVal = reason.value === '기타' ? memo.value : ''

  working.value = true
  try {
    let r
    if (isInbound.value) {
      if (!inLoc.value) { working.value = false; return toast.error('보관위치를 선택하세요. (입고는 위치 필수)') }
      r = await inboundStock(selected.value.id, inLoc.value, v, memoVal, reason.value)
    } else if (op.value === 'out') {
      r = await outboundStock(selected.value.stockId, v, memoVal, reason.value, {
        usagePlace: outLocLabel.value, requestDept: requestDept.value.trim(),
        requester: requester.value.trim(), handler: handler.value.trim(),
      })
    } else {
      r = await adjustStock(selected.value.stockId, op.value, v, memoVal, reason.value)
    }
    toast.success(`${cfg.value.title} 완료 · 재고 ${r.before} → ${r.after}개`)
    if (isInbound.value) { await loadVariants(); await loadInboundLocations(); selected.value = null }
    else { await fetchRows(); const again = rows.value.find((s) => s.stockId === selected.value?.stockId); selected.value = again || null }
  } catch (e) {
    toast.error(e.message || '처리 실패')
  } finally { working.value = false }
}

const typeLabel = { in: '입고', out: '출고', adjust: '조정', audit: '실사', void: '취소' }
const fmtTime = fmtDateTime

/* 처리 취소 */
const VOID_REASONS = ['수량 오기입', '방향 오선택', '중복 처리', '기타']
const voidTarget = ref(null)
const voidReason = ref('')
const voidMemo = ref('')
const voiding = ref(false)
function isToday(at) { if (!at) return false; const d = new Date(at); const n = new Date(); return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate() }
function canVoid(m) { return m.byUserId === auth.user?.id && m.type !== 'void' && !m.voided && isToday(m.at) }
function openVoid(m) { voidTarget.value = m; voidReason.value = ''; voidMemo.value = '' }
async function confirmVoid() {
  if (!voidTarget.value) return
  if (!voidReason.value) return toast.error('취소 사유를 선택하세요.')
  const reasonText = voidReason.value === '기타' ? (voidMemo.value || '').trim() : voidReason.value
  voiding.value = true
  try {
    await voidMovement(voidTarget.value.id, reasonText)
    toast.success('처리를 취소했습니다.')
    voidTarget.value = null
    if (!isInbound.value) await fetchRows()
    if (selected.value) movements.value = await listMovements(selected.value.skuId || selected.value.id, 6)
  } catch (e) { toast.error(e.message || '취소 실패') } finally { voiding.value = false }
}
</script>

<template>
  <div>
    <PageHeader :title="cfg.title" :subtitle="cfg.sub" />

    <div class="grid gap-4 lg:grid-cols-5">
      <!-- 선택 목록 -->
      <div class="card lg:col-span-3">
        <div class="flex flex-wrap items-center gap-2 border-b border-slate-100 p-3">
          <select v-if="!isInbound" v-model="filterComplex" class="input w-auto">
            <option value="">전체 단지</option>
            <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
          <select v-model="fCategory" class="input w-auto">
            <option value="">전체 카테고리</option>
            <option v-for="c in categoryList" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
          <select v-model="fProductCode" class="input w-auto">
            <option value="">전체 제품코드</option>
            <option v-for="p in pcOptions" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
          <input v-model="search" class="input w-full flex-1 sm:w-auto" placeholder="SKU코드/상품명 검색" />
          <select v-if="!isInbound" v-model="pageSize" class="input w-auto sm:ml-auto">
            <option v-for="n in sizes" :key="n" :value="n">{{ n }}개씩</option>
          </select>
        </div>
        <div class="max-h-[60vh] overflow-y-auto scrollbar-slim">
          <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
          <!-- 입고: 변형 SKU 목록 -->
          <template v-if="isInbound">
            <div v-if="!filteredVariants.length" class="p-10 text-center text-sm text-slate-400">SKU가 없습니다. 먼저 SKU관리에서 등록하세요.</div>
            <button v-for="s in filteredVariants" :key="s.id" class="flex w-full items-center gap-2 border-b border-slate-50 px-4 py-2.5 text-left hover:bg-slate-50"
              :class="selected?.id === s.id ? 'bg-brand-50' : ''" @click="selectItem(s)">
              <img :src="resolveImage(s)" class="h-10 w-10 shrink-0 rounded-lg border border-slate-100 object-cover" alt="" />
              <div class="min-w-0 flex-1">
                <span class="badge bg-brand-50 font-mono text-brand-700">{{ s.code }}</span>
                <span class="ml-1 text-sm font-medium text-slate-700">{{ s.productName }}</span>
                <p class="truncate text-xs text-slate-400">{{ specText(s) }}</p>
              </div>
              <div class="w-32 shrink-0 text-right text-xs" :class="locInfo(s).muted ? 'text-slate-300' : 'text-slate-500'">
                <span class="text-[10px] text-slate-300">현재위치</span>
                <p class="truncate">📍 {{ locInfo(s).text }}</p>
              </div>
            </button>
          </template>
          <!-- 출고/조정/실사: 재고행 목록 -->
          <template v-else>
            <div v-if="!rows.length" class="p-10 text-center text-sm text-slate-400">재고가 없습니다.</div>
            <button v-for="s in rows" :key="s.stockId" class="flex w-full items-center justify-between gap-2 border-b border-slate-50 px-4 py-2.5 text-left hover:bg-slate-50"
              :class="selected?.stockId === s.stockId ? 'bg-brand-50' : ''" @click="selectItem(s)">
              <img :src="resolveImage(s)" class="h-10 w-10 shrink-0 rounded-lg border border-slate-100 object-cover" alt="" />
              <div class="min-w-0 flex-1">
                <span class="badge bg-brand-50 font-mono text-brand-700">{{ s.code }}</span>
                <span class="ml-1 text-sm font-medium text-slate-700">{{ s.productName }}</span>
                <p class="truncate text-xs text-slate-400">📍 {{ s.complexName }}<span v-if="s.locationLabel"> › {{ s.locationLabel }}</span></p>
              </div>
              <span class="shrink-0 text-sm font-semibold" :class="s.qty <= 0 ? 'text-rose-500' : 'text-slate-600'">{{ s.qty }}개</span>
            </button>
          </template>
        </div>
        <Pager v-if="!isInbound && total" v-model:page="page" :total="total" :total-pages="totalPages" class="border-t border-slate-100" />
      </div>

      <!-- 작업 패널 -->
      <div class="lg:col-span-2">
        <div class="card sticky top-4 p-5">
          <div v-if="!selected" class="py-10 text-center text-sm text-slate-400">왼쪽에서 {{ isInbound ? 'SKU를' : '재고를' }} 선택하세요.</div>
          <template v-else>
            <img :src="resolveImage(selected)" class="mb-3 h-40 w-full cursor-zoom-in rounded-lg border border-slate-100 bg-slate-50 object-contain p-1" alt="상품 이미지" title="클릭하면 크게 보기" @click="imgOpen = true" />
            <p class="font-mono text-lg font-bold text-slate-800">{{ selected.code }}</p>
            <p class="text-sm text-slate-600">{{ selected.productName }} <span v-if="specText(selected)" class="text-slate-400">· {{ specText(selected) }}</span></p>
            <p v-if="!isInbound" class="text-xs text-slate-400">📍 {{ selected.complexName }}<span v-if="selected.locationLabel"> › {{ selected.locationLabel }}</span></p>

            <div v-if="!isInbound" class="my-4 rounded-lg bg-slate-50 py-3 text-center">
              <span class="text-xs text-slate-400">현재 재고</span>
              <p class="text-3xl font-extrabold" :class="selected.qty <= 0 ? 'text-rose-500' : 'text-brand-600'">{{ selected.qty }}<span class="text-base text-slate-400">개</span></p>
            </div>

            <!-- 입고: 보관위치(필수) -->
            <div v-if="isInbound" class="my-3 rounded-lg border border-slate-200 p-3">
              <p class="mb-2 text-xs font-semibold text-slate-500">보관위치 <span class="text-rose-500">*</span> <span class="font-normal text-slate-400">(입고는 위치 필수)</span></p>
              <select v-model="inComplex" class="input mb-2">
                <option value="">단지 선택</option>
                <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option>
              </select>
              <div class="grid grid-cols-2 gap-2">
                <select v-model="inZone" class="input" :disabled="!inComplex">
                  <option value="">구역 전체</option>
                  <option v-for="z in zoneChoices" :key="z.id" :value="z.id">{{ z.name }}</option>
                </select>
                <select v-model="inSub" class="input" :disabled="!inZone">
                  <option value="">상세구역 전체</option>
                  <option v-for="sz in subChoices" :key="sz.id" :value="sz.id">{{ sz.name }}</option>
                </select>
              </div>
              <select v-model="inLoc" class="input mt-2">
                <option value="">보관위치 선택</option>
                <option v-for="l in locOptions" :key="l.id" :value="l.id">{{ l.code }} · {{ [l.zoneName, l.subZoneName, l.name].filter(Boolean).join(' › ') || '단지 전체' }}</option>
              </select>
              <p v-if="inComplex && !locForComplex.length" class="mt-1 text-[11px] text-amber-600">이 단지에 보관위치가 없습니다. 보관위치관리에서 먼저 등록하세요.</p>
            </div>

            <label class="label">{{ cfg.qtyLabel }}</label>
            <input v-model.number="qty" type="number" min="0" class="input mb-3 text-lg" />

            <div class="mb-3">
              <label class="label">사유 / 구분 *</label>
              <select v-model="reason" class="input">
                <option value="">사유 선택</option>
                <option v-for="r in reasonOptions" :key="r" :value="r">{{ r }}</option>
              </select>
            </div>
            <div v-if="reason === '기타'" class="mb-4">
              <label class="label">거래처 / 상세 사유</label>
              <input v-model="memo" class="input" placeholder="상세 사유를 입력하세요" />
            </div>

            <!-- 출고 상세 -->
            <div v-if="op === 'out'" class="mb-4 space-y-2 rounded-lg border border-slate-200 p-3">
              <p class="text-xs font-semibold text-slate-500">출고 상세</p>
              <div class="rounded-md bg-slate-50 px-2 py-1.5 text-xs text-slate-500">
                출고위치 📍 {{ selected.complexName }}<span v-if="selected.locationLabel"> › {{ selected.locationLabel }}</span>
              </div>
              <div v-if="showUsage">
                <label class="label">사용처 <span class="font-normal text-slate-400">(사용처/공용 위치코드)</span></label>
                <select v-model="outComplex" class="input mb-1">
                  <option value="">단지 선택</option>
                  <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option>
                </select>
                <div class="grid grid-cols-2 gap-1">
                  <select v-model="outZone" class="input" :disabled="!outComplex">
                    <option value="">구역 전체</option>
                    <option v-for="z in outZoneChoices" :key="z.id" :value="z.id">{{ z.name }}</option>
                  </select>
                  <select v-model="outSub" class="input" :disabled="!outZone">
                    <option value="">상세구역 전체</option>
                    <option v-for="sz in outSubChoices" :key="sz.id" :value="sz.id">{{ sz.name }}</option>
                  </select>
                </div>
                <select v-model="outLoc" class="input mt-1">
                  <option value="">사용처 위치 선택</option>
                  <option v-for="l in outLocOptions" :key="l.id" :value="l.id">{{ l.code }} · {{ [l.zoneName, l.subZoneName, l.name].filter(Boolean).join(' › ') || l.complexName }}</option>
                </select>
                <p v-if="outComplex && !outLocForComplex.length" class="mt-1 text-[11px] text-amber-600">이 단지에 사용처/공용 위치코드가 없습니다. 위치코드관리에서 타입을 지정하세요.</p>
              </div>
              <div>
                <label class="label">요청부서</label>
                <input v-model="requestDept" class="input" placeholder="예: 시설관리팀" />
              </div>
              <div class="grid grid-cols-2 gap-2">
                <div><label class="label">요청자</label><input v-model="requester" class="input" placeholder="예: 홍길동" /></div>
                <div><label class="label">담당자</label><input v-model="handler" class="input" placeholder="예: 김철수" /></div>
              </div>
            </div>

            <button class="btn w-full py-3 text-base text-white" :class="cfg.btnClass" :disabled="working" @click="submit">
              {{ working ? '처리 중…' : cfg.btn }}
            </button>

            <div v-if="movements.length" class="mt-4">
              <p class="mb-1 text-xs font-semibold text-slate-500">최근 이력</p>
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
                    <button v-if="canVoid(m)" class="rounded-md border border-rose-200 px-1.5 py-0.5 text-[10px] font-medium text-rose-600 hover:bg-rose-50" @click="openVoid(m)">취소</button>
                  </span>
                </li>
              </ul>
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- 처리 취소 모달 -->
    <div v-if="voidTarget" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" @click.self="voidTarget = null">
      <div class="w-full max-w-sm rounded-2xl bg-white p-5">
        <p class="mb-3 text-sm font-semibold">처리 취소</p>
        <div class="mb-3 rounded-lg bg-slate-50 p-3 text-sm">
          <p><span class="badge bg-slate-100 text-[10px]">{{ typeLabel[voidTarget.type] }}</span> <b>{{ voidTarget.qty }}개</b> · {{ voidTarget.skuCode }}</p>
          <p class="mt-1 text-xs text-slate-500">{{ voidTarget.before }}→{{ voidTarget.after }}개 · {{ fmtTime(voidTarget.at) }}</p>
        </div>
        <select v-model="voidReason" class="input mb-2">
          <option value="">취소 사유 선택</option>
          <option v-for="r in VOID_REASONS" :key="r" :value="r">{{ r }}</option>
        </select>
        <input v-if="voidReason === '기타'" v-model="voidMemo" class="input mb-2" placeholder="상세 사유" />
        <div class="flex justify-end gap-2">
          <button class="btn-ghost" :disabled="voiding" @click="voidTarget = null">닫기</button>
          <button class="btn bg-rose-600 text-white hover:bg-rose-700" :disabled="voiding" @click="confirmVoid">{{ voiding ? '취소 중…' : '취소 확정' }}</button>
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

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { skus, products, categories, productCodes, productDetails, complexes, quotes } from '@/services/db'
import { makeQrBatch } from '@/services/qr'
import { useToast } from '@/composables/useToast'
import { useBusy } from '@/composables/useBusy'
import Pager from '@/components/ui/Pager.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import BaseModal from '@/components/ui/BaseModal.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import QrModal from '@/components/qr/QrModal.vue'
import ImageUploader from '@/components/ui/ImageUploader.vue'
import { resolveImage } from '@/utils/image'
import { addCycle, fmtDate, CYCLE_UNITS } from '@/utils/date'

const toast = useToast()
const { busy: saving, run } = useBusy()
const quotePrice = ref(null) // 연결된 최근 견적 단가(표준단가 힌트)

/* 견적에서 불러오기 (새 제품 연결) — 미연결 견적품목을 새 SKU 에 연결 */
const pendingQuoteItem = ref(null) // 저장 시 연결할 견적품목
const quotePickerModal = ref(false)
const unmatchedItems = ref([])
const quoteSearch = ref('')
const unmatchedFiltered = computed(() => {
  const s = quoteSearch.value.trim().toLowerCase()
  if (!s) return unmatchedItems.value
  return unmatchedItems.value.filter((it) =>
    `${it.rawName} ${it.spec || ''} ${it.vendorName || ''}`.toLowerCase().includes(s))
})
async function openQuotePicker() {
  try {
    unmatchedItems.value = await quotes.unmatched()
    quoteSearch.value = ''
    quotePickerModal.value = true
  } catch (e) { toast.error('견적 품목 조회 실패: ' + (e.message || e.code)) }
}
function pickQuoteItem(it) {
  pendingQuoteItem.value = it
  if (!form.spec) form.spec = it.spec || ''
  form.price = it.unitPrice || 0
  quotePickerModal.value = false
}
function clearPendingQuote() { pendingQuoteItem.value = null }
const confirm = ref(null)

const loading = ref(true)
const rows = ref([]) // 현재 페이지 SKU(변형)
const total = ref(0)
const productList = ref([])
const complexList = ref([])
const categoryList = ref([])
const productCodeList = ref([])
const productDetailList = ref([])
const search = ref('')
const fComplex = ref('')
const fCategory = ref('')
const fProductCode = ref('')
const fProductDetail = ref('')
const fProduct = ref('')
const fColor = ref('')
const fRelease = ref('')
const fProduction = ref('')
const priceMin = ref('')
const priceMax = ref('')
const selected = ref(new Set())

// 조회 필터 옵션 (카테고리 › 제품코드 › 상세코드 › 상품)
const pcFilterOptions = computed(() => (fCategory.value ? productCodeList.value.filter((p) => p.categoryId === fCategory.value) : productCodeList.value))
const pdFilterOptions = computed(() => (fProductCode.value ? productDetailList.value.filter((d) => d.productCodeId === fProductCode.value) : productDetailList.value))
const productFilterOptions = computed(() => productList.value.filter((p) =>
  (!fCategory.value || p.categoryId === fCategory.value) &&
  (!fProductCode.value || p.productCodeId === fProductCode.value) &&
  (!fProductDetail.value || p.productDetailId === fProductDetail.value)
))
watch(fCategory, () => { fProductCode.value = ''; fProductDetail.value = ''; fProduct.value = '' })
watch(fProductCode, () => { fProductDetail.value = ''; fProduct.value = '' })
watch(fProductDetail, () => { fProduct.value = '' })

const colorOptions = ref([])
const releaseYearOptions = ref([])
const productionYearOptions = ref([])

function resetFilters() {
  search.value = ''; fComplex.value = ''; fCategory.value = ''; fProductCode.value = ''; fProductDetail.value = ''; fProduct.value = ''
  fColor.value = ''; fRelease.value = ''; fProduction.value = ''; priceMin.value = ''; priceMax.value = ''
}

// 서버 페이징
const sizes = [10, 30, 50]
const page = ref(1)
const pageSize = ref(10)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
function curFilters() {
  return {
    complexId: fComplex.value, categoryId: fCategory.value, productCodeId: fProductCode.value, productDetailId: fProductDetail.value,
    productId: fProduct.value, color: fColor.value, releaseYear: fRelease.value, productionYear: fProduction.value,
    priceMin: priceMin.value, priceMax: priceMax.value, search: search.value.trim(),
  }
}
async function fetchPage() {
  loading.value = true
  try {
    const r = await skus.managePage({ ...curFilters(), page: page.value, pageSize: pageSize.value })
    rows.value = r.rows
    total.value = r.total
  } catch (e) { toast.error('불러오기 실패: ' + (e.message || e.code)) } finally { loading.value = false }
}
// 필터/페이지크기 변경 → 1페이지부터 재조회, 페이지 이동 → 해당 페이지
watch([fComplex, fCategory, fProductCode, fProductDetail, fProduct, fColor, fRelease, fProduction, priceMin, priceMax], () => { page.value = 1; fetchPage() })
watch(pageSize, () => { page.value = 1; fetchPage() })
watch(page, fetchPage)
let searchTimer = null
watch(search, () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => { page.value = 1; fetchPage() }, 350) })

const printSheet = ref([])
const printing = ref(false)
const qrModal = ref(false)
const qrSku = ref(null)

async function loadMasters() {
  try {
    ;[productList.value, categoryList.value, productCodeList.value, productDetailList.value, complexList.value] =
      await Promise.all([products.list(), categories.list(), productCodes.list(), productDetails.list(), complexes.list()])
    const opt = await skus.filterOptions()
    colorOptions.value = opt.colors
    releaseYearOptions.value = opt.releaseYears
    productionYearOptions.value = opt.productionYears
  } catch (e) { /* 옵션 로드 실패 무시 */ }
}
async function load() {
  await loadMasters()
  await fetchPage()
}
onMounted(load)

/* ---- 생성/수정 (변형만) ---- */
const modal = ref(false)
const editing = ref(null)
const blankForm = () => ({
  productId: '', complexId: '', complexName: '', code: '', spec: '', dimW: '', dimL: '', dimH: '', dimD: '',
  color: '', releaseYear: '', productionYear: '', purpose: '', purposeSel: '',
  imageUrl: '', price: 0, safetyStock: 0,
  lifecycleEnabled: false, cycleValue: 0, cycleUnit: 'month', lastReplacedAt: '', replaceReason: '', lifecycleNote: '',
})
const nextReplacePreview = computed(() => {
  if (!form.lifecycleEnabled || !Number(form.cycleValue)) return ''
  const base = form.lastReplacedAt ? new Date(form.lastReplacedAt) : new Date()
  return fmtDate(addCycle(base, form.cycleValue, form.cycleUnit))
})
const form = reactive(blankForm())

// 상품 선택 시 표준단가 상속(가격 단일 출처)
watch(() => form.productId, (pid) => {
  if (editing.value || !pid) return
  const p = productList.value.find((x) => x.id === pid)
  if (p && (!form.price || Number(form.price) === 0)) form.price = p.price || 0
})

// 상품 찾기 연쇄 필터 (카테고리>제품코드>상세코드)
const psel = reactive({ categoryId: '', productCodeId: '', productDetailId: '' })
const pCategoryOptions = computed(() => categoryList.value)
const pCodeOptions = computed(() => (psel.categoryId ? productCodeList.value.filter((p) => p.categoryId === psel.categoryId) : []))
const pDetailOptions = computed(() => (psel.productCodeId ? productDetailList.value.filter((d) => d.productCodeId === psel.productCodeId) : []))
const filteredProducts = computed(() =>
  productList.value.filter((p) =>
    (!psel.categoryId || p.categoryId === psel.categoryId) &&
    (!psel.productCodeId || p.productCodeId === psel.productCodeId) &&
    (!psel.productDetailId || p.productDetailId === psel.productDetailId)
  ).sort((a, b) => (a.name > b.name ? 1 : -1))
)
function onPselCategory() { psel.productCodeId = ''; psel.productDetailId = ''; clearInvalidProduct() }
function onPselCode() { psel.productDetailId = ''; clearInvalidProduct() }
function clearInvalidProduct() { if (form.productId && !filteredProducts.value.find((p) => p.id === form.productId)) form.productId = '' }

function hasAnyAttr() {
  const text = [form.spec, form.color, form.releaseYear, form.productionYear, form.purpose].some((v) => String(v ?? '').trim() !== '')
  const hasDim = [form.dimW, form.dimL, form.dimH, form.dimD].some((v) => String(v ?? '').trim() !== '')
  return text || hasDim || Number(form.price) > 0 || Number(form.safetyStock) > 0
}

const PURPOSES = ['판매용', '전시용', '내부비치', '소모', '기타']
function onPurposeSel() { form.purpose = form.purposeSel === '기타' ? '' : form.purposeSel }

function dimText(s) {
  const p = []
  if (s.dimW) p.push(`W${s.dimW}`)
  if (s.dimL) p.push(`L${s.dimL}`)
  if (s.dimH) p.push(`H${s.dimH}`)
  if (s.dimD) p.push(`D${s.dimD}`)
  return p.length ? p.join(' × ') + ' cm' : ''
}
const priceDisplay = computed({
  get: () => (form.price === '' || form.price === null || form.price === undefined ? '' : Number(form.price).toLocaleString()),
  set: (v) => { form.price = Number(String(v).replace(/[^\d]/g, '')) || 0 },
})

function openCreate() {
  if (!productList.value.length) return toast.error('먼저 상품을 등록하세요.')
  editing.value = null
  quotePrice.value = null
  pendingQuoteItem.value = null
  Object.assign(form, blankForm())
  Object.assign(psel, { categoryId: '', productCodeId: '', productDetailId: '' })
  modal.value = true
}
function openEdit(s) {
  editing.value = s
  quotePrice.value = null
  pendingQuoteItem.value = null
  quotes.forSku(s.id).then((q) => { quotePrice.value = q ? q.unitPrice : null }).catch(() => {})
  Object.assign(form, {
    productId: s.productId, complexId: s.complexId || '', complexName: s.complexName || '', code: s.code, spec: s.spec || '',
    dimW: s.dimW ?? '', dimL: s.dimL ?? '', dimH: s.dimH ?? '', dimD: s.dimD ?? '',
    color: s.color || '', releaseYear: s.releaseYear || '', productionYear: s.productionYear || '',
    purpose: s.purpose || '',
    purposeSel: s.purpose ? (['판매용', '전시용', '내부비치', '소모'].includes(s.purpose) ? s.purpose : '기타') : '',
    imageUrl: s.imageUrl || '', price: s.price || 0, safetyStock: s.safetyStock || 0,
    lifecycleEnabled: !!s.lifecycleEnabled, cycleValue: s.cycleValue || 0, cycleUnit: s.cycleUnit || 'month',
    lastReplacedAt: '', replaceReason: s.replaceReason || '', lifecycleNote: s.lifecycleNote || '',
  })
  modal.value = true
}

async function save() {
  if (!form.productId) return toast.error('상품을 선택하세요.')
  if (!hasAnyAttr()) return toast.error('규격·색상·출시년도·생산년도·구매목적·단가·안전재고 중 최소 1개는 입력해야 합니다.')
  const product = productList.value.find((p) => p.id === form.productId)
  const complexName = complexList.value.find((c) => c.id === form.complexId)?.name || ''
  const cx = { complexId: form.complexId || null, complexName }
  const baseDate = form.lastReplacedAt ? new Date(form.lastReplacedAt) : null
  const lifecycle = {
    lifecycleEnabled: !!form.lifecycleEnabled,
    cycleValue: Number(form.cycleValue) || 0,
    cycleUnit: form.cycleUnit || 'month',
    replaceReason: form.replaceReason.trim(),
    lifecycleNote: form.lifecycleNote.trim(),
  }
  const num = (v) => (v === '' || v === null || v === undefined ? null : Number(v))
  const dims = { dimW: num(form.dimW), dimL: num(form.dimL), dimH: num(form.dimH), dimD: num(form.dimD) }
  try {
    if (editing.value) {
      await skus.update(editing.value.id, {
        // 상품 기준 필드는 상품에서 다시 실어 보존(수정 시 덮어쓰기 방지)
        productId: product.id, productName: product.name, ...cx,
        categoryId: product.categoryId, productCodeId: product.productCodeId, productDetailId: product.productDetailId,
        pathLabel: product.pathLabel || '',
        spec: form.spec.trim(), ...dims, color: form.color.trim(),
        releaseYear: String(form.releaseYear || '').trim(), productionYear: String(form.productionYear || '').trim(),
        purpose: form.purpose.trim(), imageUrl: form.imageUrl || '',
        price: Number(form.price) || 0, safetyStock: Number(form.safetyStock) || 0, ...lifecycle,
      })
      toast.success('SKU가 수정되었습니다.')
    } else {
      const r = await skus.create({
        spec: form.spec.trim(), ...dims, color: form.color.trim(),
        releaseYear: form.releaseYear, productionYear: form.productionYear, purpose: form.purpose.trim(),
        imageUrl: form.imageUrl || '', ...lifecycle,
        productMainImageUrl: product.mainImageUrl || '', price: form.price, safetyStock: form.safetyStock,
        productId: product.id, productName: product.name, ...cx,
        categoryId: product.categoryId, productCodeId: product.productCodeId, productDetailId: product.productDetailId,
        pathLabel: product.pathLabel || '',
      })
      let extra = ''
      if (pendingQuoteItem.value) {
        try { await quotes.linkItem(pendingQuoteItem.value.quoteItemId, r.id); extra = ' · 견적 연결됨' } catch (_) { /* 링크 실패는 무시 */ }
      }
      toast.success(`SKU 생성 완료 (${r.code})${extra} · 재고는 입고에서 위치별로 등록됩니다`)
    }
    modal.value = false
    await load()
  } catch (e) { toast.error('저장 실패: ' + (e.message || e.code)) }
}

async function remove(s) {
  const ok = await confirm.value.ask({ title: 'SKU 삭제', message: `SKU "${s.code}"를 삭제할까요?\n연결된 재고행도 함께 삭제됩니다.`, confirmText: '삭제', danger: true })
  if (!ok) return
  try { await skus.remove(s.id); toast.success('삭제되었습니다.'); await load() } catch (e) { toast.error('삭제 실패: ' + (e.message || e.code)) }
}

/* ---- 선택/출력 ---- */
const allChecked = computed(() => rows.value.length > 0 && rows.value.every((s) => selected.value.has(s.id)))
function toggleAll() {
  if (allChecked.value) rows.value.forEach((s) => selected.value.delete(s.id))
  else rows.value.forEach((s) => selected.value.add(s.id))
  selected.value = new Set(selected.value)
}
function toggle(id) { selected.value.has(id) ? selected.value.delete(id) : selected.value.add(id); selected.value = new Set(selected.value) }
function showQr(s) { qrSku.value = s; qrModal.value = true }

/* ---- 상세 조회 팝업 (읽기 전용) ---- */
const detailModal = ref(false)
const detailSku = ref(null)
function openDetail(s) { detailSku.value = s; detailModal.value = true }
async function copyCode(code) {
  const text = code || ''
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      const ta = document.createElement('textarea')
      ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0'
      document.body.appendChild(ta); ta.select(); document.execCommand('copy'); document.body.removeChild(ta)
    }
    toast.success('SKU 코드 복사됨')
  } catch (e) { toast.error('복사 실패') }
}
function cycleUnitText(u) { return CYCLE_UNITS.find((x) => x.v === u)?.t || u }
function editFromDetail() { detailModal.value = false; openEdit(detailSku.value) }

async function printSelected() {
  if (!selected.value.size) return toast.error('출력할 SKU를 선택하세요.')
  printing.value = true
  try {
    const targets = await skus.listByIds([...selected.value])
    printSheet.value = await makeQrBatch(targets)
    await new Promise((r) => setTimeout(r, 300))
    window.print()
    toast.success(`${targets.length}건 출력 준비 완료`)
  } catch (e) { toast.error('출력 실패: ' + (e.message || e.code)) } finally { printing.value = false }
}
</script>

<template>
  <div>
    <PageHeader title="SKU관리" subtitle="품목 변형(규격·색상·치수 등) 단위. 실제 재고 수량은 재고현황/입고에서 위치별로 관리됩니다.">
      <button class="btn-ghost no-print" :disabled="printing || !selected.size" @click="printSelected">
        {{ printing ? '준비 중…' : `QR 출력 (${selected.size})` }}
      </button>
      <button class="btn-primary no-print" @click="openCreate">+ SKU 추가</button>
    </PageHeader>

    <div class="no-print mb-3 flex flex-wrap items-center gap-2">
      <AppSelect v-model="fComplex" class="w-auto"><option value="">전체 단지</option><option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option></AppSelect>
      <AppSelect v-model="fCategory" class="w-auto"><option value="">전체 카테고리</option><option v-for="c in categoryList" :key="c.id" :value="c.id">{{ c.name }}</option></AppSelect>
      <AppSelect v-model="fProductCode" class="w-auto"><option value="">전체 제품코드</option><option v-for="p in pcFilterOptions" :key="p.id" :value="p.id">{{ p.name }}</option></AppSelect>
      <AppSelect v-model="fProductDetail" class="w-auto"><option value="">전체 상세코드</option><option v-for="d in pdFilterOptions" :key="d.id" :value="d.id">{{ d.name }}</option></AppSelect>
      <AppSelect v-model="fProduct" class="w-auto"><option value="">전체 상품</option><option v-for="p in productFilterOptions" :key="p.id" :value="p.id">{{ p.name }} ({{ p.code }})</option></AppSelect>
      <AppSelect v-model="fColor" class="w-auto"><option value="">전체 색상</option><option v-for="c in colorOptions" :key="c" :value="c">{{ c }}</option></AppSelect>
      <AppSelect v-model="fRelease" class="w-auto"><option value="">출시년도</option><option v-for="y in releaseYearOptions" :key="y" :value="String(y)">{{ y }}</option></AppSelect>
      <AppSelect v-model="fProduction" class="w-auto"><option value="">생산년도</option><option v-for="y in productionYearOptions" :key="y" :value="String(y)">{{ y }}</option></AppSelect>
      <div class="flex items-center gap-1">
        <input v-model="priceMin" type="number" min="0" class="input w-24" placeholder="단가 최소" />
        <span class="text-slate-400">~</span>
        <input v-model="priceMax" type="number" min="0" class="input w-24" placeholder="최대" />
      </div>
      <input v-model="search" class="input w-full sm:w-64" placeholder="SKU코드 / 상품명 / 규격 / 색상 / 목적 검색" />
      <button class="btn-ghost btn-sm" @click="resetFilters">초기화</button>
      <AppSelect v-model="pageSize" class="w-auto sm:ml-auto"><option v-for="n in sizes" :key="n" :value="n">{{ n }}개씩</option></AppSelect>
    </div>

    <div class="card no-print">
      <div class="overflow-x-auto scrollbar-slim">
      <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
      <div v-else-if="!rows.length" class="p-10 text-center text-sm text-slate-400">등록된 SKU가 없습니다.</div>
      <table v-else class="w-full min-w-[920px] text-sm">
        <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th class="w-10 px-3 py-2.5"><input type="checkbox" class="rounded border-slate-300" :checked="allChecked" @change="toggleAll" /></th>
            <th class="px-3 py-2.5 font-semibold">SKU 코드</th>
            <th class="px-3 py-2.5 font-semibold">SKU명 (상품 · 규격)</th>
            <th class="px-3 py-2.5 font-semibold">단지</th>
            <th class="px-3 py-2.5 font-semibold">치수</th>
            <th class="px-3 py-2.5 font-semibold">색상</th>
            <th class="px-3 py-2.5 font-semibold">출시</th>
            <th class="px-3 py-2.5 font-semibold">생산</th>
            <th class="px-3 py-2.5 text-right font-semibold">표준단가</th>
            <th class="px-3 py-2.5 text-right font-semibold">안전재고</th>
            <th class="px-3 py-2.5 text-right font-semibold">관리</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-for="s in rows" :key="s.id" class="hover:bg-slate-50/60" :class="selected.has(s.id) ? 'bg-brand-50/40' : ''">
            <td class="px-3 py-2.5"><input type="checkbox" class="rounded border-slate-300" :checked="selected.has(s.id)" @change="toggle(s.id)" /></td>
            <td class="cursor-pointer px-3 py-2.5" title="상세 보기" @click="openDetail(s)"><span class="badge bg-brand-50 font-mono text-brand-700 hover:bg-brand-100">{{ s.code }}</span></td>
            <td class="cursor-pointer px-3 py-2.5" title="상세 보기" @click="openDetail(s)">
              <div class="flex items-center gap-2.5">
                <img :src="resolveImage(s)" class="h-10 w-10 shrink-0 rounded-lg border border-slate-100 object-cover" alt="" />
                <div class="min-w-0">
                  <p class="font-medium text-slate-800 hover:text-brand-700">{{ s.productName }}<span v-if="s.spec" class="text-brand-600"> · {{ s.spec }}</span></p>
                  <p class="truncate text-[11px] text-slate-300">{{ s.pathLabel }}</p>
                </div>
              </div>
            </td>
            <td class="px-3 py-2.5"><span v-if="s.complexName" class="badge bg-brand-50 text-brand-700">{{ s.complexName }}</span><span v-else class="text-slate-300">—</span></td>
            <td class="px-3 py-2.5 text-xs text-slate-600">{{ dimText(s) || '—' }}</td>
            <td class="px-3 py-2.5 text-slate-600">{{ s.color || '—' }}</td>
            <td class="px-3 py-2.5 text-slate-500">{{ s.releaseYear || '—' }}</td>
            <td class="px-3 py-2.5 text-slate-500">{{ s.productionYear || '—' }}</td>
            <td class="px-3 py-2.5 text-right text-slate-700">{{ Number(s.price).toLocaleString() }}원</td>
            <td class="px-3 py-2.5 text-right text-slate-500">{{ s.safetyStock }}</td>
            <td class="px-3 py-2.5 text-right">
              <button class="btn-ghost btn-sm" @click="showQr(s)">QR</button>
              <button class="btn-ghost btn-sm ml-1" @click="openEdit(s)">수정</button>
              <button class="btn-ghost btn-sm ml-1 text-rose-600" @click="remove(s)">삭제</button>
            </td>
          </tr>
        </tbody>
      </table>
      </div>
      <Pager v-if="total" v-model:page="page" :total="total" :total-pages="totalPages" class="border-t border-slate-100" />
    </div>

    <!-- 인쇄 라벨 시트 -->
    <div class="print-only">
      <div class="grid grid-cols-3 gap-3 p-2">
        <div v-for="item in printSheet" :key="item.id" class="flex break-inside-avoid items-center gap-2 rounded-lg border border-dashed border-slate-400 p-2">
          <img :src="item.qrDataUrl" class="h-20 w-20 shrink-0" alt="QR" />
          <div class="min-w-0 text-[10px] leading-tight">
            <p class="font-mono font-bold text-black">{{ item.code }}</p>
            <p class="truncate text-slate-700">{{ item.productName }}</p>
            <p class="text-slate-500">{{ dimText(item) || item.spec }}</p>
            <p class="mt-0.5 break-words text-slate-400">{{ item.pathLabel }}</p>
          </div>
        </div>
      </div>
    </div>

    <BaseModal v-model="modal" :title="editing ? 'SKU 수정' : 'SKU 추가'">
      <div class="space-y-3">
        <!-- 견적에서 불러오기 (새 제품 연결) -->
        <div v-if="!editing" class="rounded-lg border border-brand-100 bg-brand-50/40 p-2">
          <div v-if="pendingQuoteItem" class="flex items-center justify-between gap-2 text-sm">
            <div class="min-w-0 truncate">
              <span class="text-xs text-brand-700">📄 견적 연결:</span>
              <b class="text-slate-800">{{ pendingQuoteItem.rawName }}</b>
              <span class="text-xs text-slate-400">· {{ pendingQuoteItem.vendorName }} {{ pendingQuoteItem.quoteDate }} · {{ pendingQuoteItem.qty }}{{ pendingQuoteItem.unit || '개' }} · {{ Number(pendingQuoteItem.unitPrice).toLocaleString() }}원</span>
            </div>
            <button type="button" class="btn-ghost btn-sm shrink-0" @click="clearPendingQuote">해제</button>
          </div>
          <button v-else type="button" class="btn-ghost btn-sm w-full" @click="openQuotePicker">📄 견적에서 불러오기 (새 제품 연결)</button>
        </div>

        <div v-if="!editing" class="grid grid-cols-3 gap-2 rounded-lg bg-slate-50 p-2">
          <AppSelect v-model="psel.categoryId" class="text-sm w-full" @change="onPselCategory">
            <option value="">전체 카테고리</option>
            <option v-for="c in pCategoryOptions" :key="c.id" :value="c.id">{{ c.name }}</option>
          </AppSelect>
          <AppSelect v-model="psel.productCodeId" class="text-sm w-full" :disabled="!psel.categoryId" @change="onPselCode">
            <option value="">전체 제품코드</option>
            <option v-for="p in pCodeOptions" :key="p.id" :value="p.id">{{ p.name }}</option>
          </AppSelect>
          <AppSelect v-model="psel.productDetailId" class="text-sm w-full" :disabled="!psel.productCodeId" @change="clearInvalidProduct">
            <option value="">전체 상세코드(선택)</option>
            <option v-for="d in pDetailOptions" :key="d.id" :value="d.id">{{ d.name }}</option>
          </AppSelect>
        </div>
        <div>
          <label class="label">상품 * <span class="text-slate-400">({{ filteredProducts.length }}건)</span></label>
          <AppSelect v-model="form.productId" fluid class="w-full" :disabled="!!editing">
            <option value="">상품 선택</option>
            <option v-for="p in (editing ? productList : filteredProducts)" :key="p.id" :value="p.id">{{ p.name }} ({{ p.code }}{{ p.pathLabel ? ' · ' + p.pathLabel : '' }})</option>
          </AppSelect>
        </div>
        <div>
          <label class="label">단지 <span class="text-slate-400">(같은 품목·규격도 단지별 별도 SKU)</span></label>
          <AppSelect v-model="form.complexId" class="w-full">
            <option value="">단지 선택 안 함</option>
            <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option>
          </AppSelect>
        </div>
        <div>
          <label class="label">SKU 코드</label>
          <input v-if="editing" :value="form.code" class="input bg-slate-50 font-mono text-slate-400" readonly />
          <p v-else class="rounded-lg border border-dashed border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-400">상품코드 기반 자동 생성 (예: P-000001-001)</p>
        </div>
        <p class="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-700">규격·색상·출시년도·생산년도·구매목적·단가·안전재고 중 <b>최소 1개</b>는 입력해야 저장됩니다. 재고 수량은 <b>입고</b>에서 위치별로 등록합니다.</p>
        <div>
          <label class="label">규격 / 사양 <span class="text-slate-400">(SKU 구분 — 관리자가 직접 입력. 예: 2구, 3구, 방수형)</span></label>
          <input v-model="form.spec" class="input" placeholder="예: 2구 / 3구 / 5m / 방수형" />
        </div>
        <div>
          <label class="label">치수 (cm · 선택)</label>
          <div class="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <div class="flex items-center gap-1"><span class="shrink-0 text-xs text-slate-500">가로</span><input v-model="form.dimW" type="number" min="0" step="0.1" class="input px-2" /><span class="text-xs text-slate-400">cm</span></div>
            <div class="flex items-center gap-1"><span class="shrink-0 text-xs text-slate-500">세로</span><input v-model="form.dimL" type="number" min="0" step="0.1" class="input px-2" /><span class="text-xs text-slate-400">cm</span></div>
            <div class="flex items-center gap-1"><span class="shrink-0 text-xs text-slate-500">높이</span><input v-model="form.dimH" type="number" min="0" step="0.1" class="input px-2" /><span class="text-xs text-slate-400">cm</span></div>
            <div class="flex items-center gap-1"><span class="shrink-0 text-xs text-slate-500">깊이</span><input v-model="form.dimD" type="number" min="0" step="0.1" class="input px-2" /><span class="text-xs text-slate-400">cm</span></div>
          </div>
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div><label class="label">색상</label><input v-model="form.color" class="input" placeholder="예: 화이트" /></div>
          <div><label class="label">출시년도</label><input v-model="form.releaseYear" type="number" min="1900" max="2999" class="input" placeholder="예: 2023" /></div>
          <div><label class="label">생산년도</label><input v-model="form.productionYear" type="number" min="1900" max="2999" class="input" placeholder="예: 2024" /></div>
          <div>
            <label class="label">구매목적</label>
            <AppSelect v-model="form.purposeSel" class="w-full" @change="onPurposeSel">
              <option value="">선택</option>
              <option v-for="p in PURPOSES" :key="p" :value="p">{{ p }}</option>
            </AppSelect>
          </div>
          <div v-if="form.purposeSel === '기타'" class="col-span-2"><label class="label">구매목적 직접 입력</label><input v-model="form.purpose" class="input" /></div>
          <div>
            <label class="label">표준단가(원)</label>
            <input v-model="priceDisplay" inputmode="numeric" class="input" placeholder="0" />
            <p v-if="quotePrice != null" class="mt-1 text-[11px] text-brand-600">
              최근 견적가 {{ Number(quotePrice).toLocaleString() }}원
              <button type="button" class="ml-1 underline" @click="form.price = quotePrice">적용</button>
            </p>
          </div>
          <div><label class="label">안전재고</label><input v-model.number="form.safetyStock" type="number" min="0" class="input" /></div>
        </div>
        <div>
          <label class="label">SKU 이미지 (선택)</label>
          <ImageUploader v-model="form.imageUrl" prefix="skus" size="sm" />
          <p class="mt-1 text-[11px] text-slate-400">없으면 상품 대표 이미지가 자동 표시됩니다.</p>
        </div>

        <div class="rounded-lg border border-slate-200 p-3">
          <label class="flex items-center gap-2 text-sm font-semibold text-slate-700">
            <input v-model="form.lifecycleEnabled" type="checkbox" class="rounded border-slate-300" /> 연한관리(주기 교체) 사용
          </label>
          <div v-if="form.lifecycleEnabled" class="mt-3 space-y-3">
            <div>
              <label class="label">교체주기</label>
              <div class="flex gap-2">
                <input v-model.number="form.cycleValue" type="number" min="0" class="input" />
                <AppSelect v-model="form.cycleUnit" class="w-24"><option v-for="u in CYCLE_UNITS" :key="u.v" :value="u.v">{{ u.t }}</option></AppSelect>
              </div>
            </div>
            <div><label class="label">교체 사유/유형</label><input v-model="form.replaceReason" class="input" placeholder="예: 법정점검 / 마모 / 위생" /></div>
            <div><label class="label">비고</label><input v-model="form.lifecycleNote" class="input" /></div>
            <p v-if="nextReplacePreview" class="rounded-lg bg-brand-50 px-3 py-2 text-xs text-brand-700">다음 교체 예정일(기준 오늘): <b>{{ nextReplacePreview }}</b> <span class="text-slate-400">— 실제 예정일은 입고/교체 시점에 계산됩니다</span></p>
          </div>
        </div>
      </div>
      <template #footer>
        <button class="btn-ghost" @click="modal = false">취소</button>
        <button class="btn-primary" :disabled="saving" @click="run(save)">{{ editing ? '수정' : '생성' }}</button>
      </template>
    </BaseModal>

    <!-- SKU 상세 조회 (읽기 전용) -->
    <BaseModal v-model="detailModal" title="SKU 상세">
      <div v-if="detailSku" class="space-y-4">
        <div class="flex gap-4">
          <img :src="resolveImage(detailSku)" class="h-28 w-28 shrink-0 rounded-lg border border-slate-100 bg-slate-50 object-contain p-1" alt="" />
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-1.5">
              <span class="badge bg-brand-50 font-mono text-brand-700">{{ detailSku.code }}</span>
              <button type="button" class="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-brand-600" title="SKU 코드 복사" @click="copyCode(detailSku.code)">
                <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="12" height="12" rx="2" /><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1" /></svg>
              </button>
            </div>
            <p class="mt-1 text-lg font-bold text-slate-800">{{ detailSku.productName }}<span v-if="detailSku.spec" class="text-brand-600"> · {{ detailSku.spec }}</span></p>
            <p class="mt-0.5 text-xs text-slate-400">{{ detailSku.pathLabel || '경로 미지정' }}</p>
          </div>
        </div>

        <dl class="grid grid-cols-2 gap-x-4 gap-y-3 text-sm sm:grid-cols-3">
          <div><dt class="text-xs text-slate-400">치수</dt><dd class="text-slate-700">{{ dimText(detailSku) || '—' }}</dd></div>
          <div><dt class="text-xs text-slate-400">색상</dt><dd class="text-slate-700">{{ detailSku.color || '—' }}</dd></div>
          <div><dt class="text-xs text-slate-400">구매목적</dt><dd class="text-slate-700">{{ detailSku.purpose || '—' }}</dd></div>
          <div><dt class="text-xs text-slate-400">출시년도</dt><dd class="text-slate-700">{{ detailSku.releaseYear || '—' }}</dd></div>
          <div><dt class="text-xs text-slate-400">생산년도</dt><dd class="text-slate-700">{{ detailSku.productionYear || '—' }}</dd></div>
          <div><dt class="text-xs text-slate-400">표준단가</dt><dd class="font-semibold text-slate-800">{{ Number(detailSku.price || 0).toLocaleString() }}원</dd></div>
          <div><dt class="text-xs text-slate-400">안전재고</dt><dd class="text-slate-700">{{ detailSku.safetyStock ?? 0 }}</dd></div>
        </dl>

        <div class="rounded-lg border border-slate-200 p-3">
          <p class="mb-1 text-xs font-semibold text-slate-500">연한관리</p>
          <template v-if="detailSku.lifecycleEnabled">
            <p class="text-sm text-slate-700">교체주기: <b>{{ detailSku.cycleValue }}{{ cycleUnitText(detailSku.cycleUnit) }}</b></p>
            <p v-if="detailSku.replaceReason" class="mt-0.5 text-xs text-slate-500">사유/유형: {{ detailSku.replaceReason }}</p>
            <p v-if="detailSku.lifecycleNote" class="mt-0.5 text-xs text-slate-400">비고: {{ detailSku.lifecycleNote }}</p>
          </template>
          <p v-else class="text-sm text-slate-400">사용 안 함</p>
        </div>
        <p class="text-[11px] text-slate-400">실제 재고 수량·위치는 재고현황/입고에서 관리됩니다.</p>
      </div>
      <template #footer>
        <button class="btn-ghost" @click="showQr(detailSku)">QR</button>
        <button class="btn-ghost" @click="editFromDetail">수정</button>
        <button class="btn-primary" @click="detailModal = false">닫기</button>
      </template>
    </BaseModal>

    <!-- 견적 품목 선택(새 제품 연결) -->
    <BaseModal v-model="quotePickerModal" title="견적 품목에서 불러오기" size="lg">
      <input v-model="quoteSearch" class="input mb-2" placeholder="품명·규격·업체 검색" />
      <div class="max-h-[50vh] overflow-auto scrollbar-slim rounded-lg border border-slate-100">
        <div v-if="!unmatchedFiltered.length" class="p-8 text-center text-sm text-slate-400">미연결 견적 품목이 없습니다.</div>
        <button v-for="it in unmatchedFiltered" :key="it.quoteItemId" type="button"
          class="flex w-full items-center justify-between gap-2 border-b border-slate-50 px-3 py-2 text-left hover:bg-brand-50"
          @click="pickQuoteItem(it)">
          <div class="min-w-0">
            <div class="truncate text-sm font-medium text-slate-800">{{ it.rawName }}<span v-if="it.spec" class="ml-1 text-xs text-slate-400">{{ it.spec }}</span></div>
            <div class="text-[11px] text-slate-400">{{ it.vendorName }} · {{ it.quoteDate || '-' }}</div>
          </div>
          <div class="shrink-0 text-right text-xs text-slate-500">
            <div>{{ it.qty }}{{ it.unit || '개' }}</div>
            <div>{{ Number(it.unitPrice).toLocaleString() }}원</div>
          </div>
        </button>
      </div>
      <p class="mt-2 text-[11px] text-slate-400">선택하면 규격·단가가 채워지고, 저장 시 이 SKU 에 견적이 연결됩니다.</p>
      <template #footer>
        <button class="btn-ghost" @click="quotePickerModal = false">닫기</button>
      </template>
    </BaseModal>

    <QrModal v-model="qrModal" :sku="qrSku" />
    <ConfirmDialog ref="confirm" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { skus, products, complexes, categories, productCodes, productDetails } from '@/services/db'
import { makeQrBatch } from '@/services/qr'
import { useToast } from '@/composables/useToast'
import { useBusy } from '@/composables/useBusy'
import { usePagination } from '@/composables/usePagination'
import Pager from '@/components/ui/Pager.vue'
import BaseModal from '@/components/ui/BaseModal.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import QrModal from '@/components/qr/QrModal.vue'
import ImageUploader from '@/components/ui/ImageUploader.vue'
import { resolveImage } from '@/utils/image'
import { addCycle, fmtDate, CYCLE_UNITS } from '@/utils/date'

const toast = useToast()
const { busy: saving, run } = useBusy()
const confirm = ref(null)

const loading = ref(true)
const list = ref([])
const productList = ref([])
const complexList = ref([])
const categoryList = ref([])
const productCodeList = ref([])
const productDetailList = ref([])
const search = ref('')
const filterComplex = ref('')
const fColor = ref('')
const fRelease = ref('')
const fProduction = ref('')
const priceMin = ref('')
const priceMax = ref('')
const selected = ref(new Set())

// 목록에 존재하는 값들로 셀렉트 옵션 구성
const distinct = (key) =>
  [...new Set(list.value.map((s) => s[key]).filter((v) => v !== null && v !== undefined && String(v).trim() !== ''))]
const colorOptions = computed(() => distinct('color').sort())
const releaseYearOptions = computed(() => distinct('releaseYear').sort((a, b) => Number(b) - Number(a)))
const productionYearOptions = computed(() => distinct('productionYear').sort((a, b) => Number(b) - Number(a)))

function resetFilters() {
  search.value = ''
  filterComplex.value = ''
  fColor.value = ''
  fRelease.value = ''
  fProduction.value = ''
  priceMin.value = ''
  priceMax.value = ''
}

const printSheet = ref([])
const printing = ref(false)
const qrModal = ref(false)
const qrSku = ref(null)

async function load() {
  loading.value = true
  try {
    ;[list.value, productList.value, complexList.value, categoryList.value, productCodeList.value, productDetailList.value] =
      await Promise.all([skus.list(), products.list(), complexes.list(), categories.list(), productCodes.list(), productDetails.list()])
  } catch (e) {
    toast.error('불러오기 실패: ' + (e.message || e.code))
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() =>
  list.value.filter((s) => {
    if (filterComplex.value && s.complexId !== filterComplex.value) return false
    if (fColor.value && s.color !== fColor.value) return false
    if (fRelease.value && String(s.releaseYear) !== fRelease.value) return false
    if (fProduction.value && String(s.productionYear) !== fProduction.value) return false
    const price = Number(s.price) || 0
    if (priceMin.value !== '' && price < Number(priceMin.value)) return false
    if (priceMax.value !== '' && price > Number(priceMax.value)) return false
    if (search.value) {
      const q = search.value.toLowerCase()
      return [s.code, s.productName, s.spec, s.purpose, s.pathLabel].some((v) => (v || '').toLowerCase().includes(q))
    }
    return true
  })
)
const { paged, page, pageSize, sizes, total, totalPages } = usePagination(filtered)

/* ---- 생성/수정 ---- */
const modal = ref(false)
const editing = ref(null)
const blankForm = () => ({
  productId: '', code: '', spec: '', dimW: '', dimL: '', dimH: '', dimD: '',
  color: '', releaseYear: '', productionYear: '', purpose: '', purposeSel: '',
  imageUrl: '', price: 0, qty: 0, safetyStock: 0,
  // 연한관리
  lifecycleEnabled: false, cycleValue: 0, cycleUnit: 'month', lastReplacedAt: '', replaceReason: '', lifecycleNote: '',
})

// 폼 기준 다음 교체예정일 미리보기
const nextReplacePreview = computed(() => {
  if (!form.lifecycleEnabled || !Number(form.cycleValue)) return ''
  const base = form.lastReplacedAt ? new Date(form.lastReplacedAt) : new Date()
  return fmtDate(addCycle(base, form.cycleValue, form.cycleUnit))
})
const form = reactive(blankForm())

// 상품 찾기용 연쇄 필터 (단지>카테고리>제품코드>상세코드). 비우면 전체
const psel = reactive({ complexId: '', categoryId: '', productCodeId: '', productDetailId: '' })
const pCategoryOptions = computed(() => (psel.complexId ? categoryList.value.filter((c) => c.complexId === psel.complexId) : []))
const pCodeOptions = computed(() => (psel.categoryId ? productCodeList.value.filter((p) => p.categoryId === psel.categoryId) : []))
const pDetailOptions = computed(() => (psel.productCodeId ? productDetailList.value.filter((d) => d.productCodeId === psel.productCodeId) : []))
const filteredProducts = computed(() =>
  productList.value
    .filter(
      (p) =>
        (!psel.complexId || p.complexId === psel.complexId) &&
        (!psel.categoryId || p.categoryId === psel.categoryId) &&
        (!psel.productCodeId || p.productCodeId === psel.productCodeId) &&
        (!psel.productDetailId || p.productDetailId === psel.productDetailId)
    )
    .sort((a, b) => (a.name > b.name ? 1 : -1))
)
function onPselComplex() { psel.categoryId = ''; psel.productCodeId = ''; psel.productDetailId = ''; clearInvalidProduct() }
function onPselCategory() { psel.productCodeId = ''; psel.productDetailId = ''; clearInvalidProduct() }
function onPselCode() { psel.productDetailId = ''; clearInvalidProduct() }
function clearInvalidProduct() {
  if (form.productId && !filteredProducts.value.find((p) => p.id === form.productId)) form.productId = ''
}

// SKU 구분을 위한 속성: 최소 1개 이상 입력 필요
function hasAnyAttr() {
  const text = [form.spec, form.color, form.releaseYear, form.productionYear, form.purpose].some(
    (v) => String(v ?? '').trim() !== ''
  )
  const hasDim = [form.dimW, form.dimL, form.dimH, form.dimD].some((v) => String(v ?? '').trim() !== '')
  return text || hasDim || Number(form.price) > 0 || Number(form.qty) > 0 || Number(form.safetyStock) > 0
}

// 구매목적 셀렉트 (기타 시 직접 입력)
const PURPOSES = ['판매용', '전시용', '내부비치', '소모', '기타']
function onPurposeSel() {
  form.purpose = form.purposeSel === '기타' ? '' : form.purposeSel
}

// 치수 텍스트 (W 가로 / L 세로 / H 높이 / D 깊이, cm)
function dimText(s) {
  const p = []
  if (s.dimW) p.push(`W${s.dimW}`)
  if (s.dimL) p.push(`L${s.dimL}`)
  if (s.dimH) p.push(`H${s.dimH}`)
  if (s.dimD) p.push(`D${s.dimD}`)
  return p.length ? p.join(' × ') + ' cm' : ''
}

// 단가 입력 콤마 표시
const priceDisplay = computed({
  get: () => (form.price === '' || form.price === null || form.price === undefined ? '' : Number(form.price).toLocaleString()),
  set: (v) => {
    form.price = Number(String(v).replace(/[^\d]/g, '')) || 0
  },
})

function openCreate() {
  if (!productList.value.length) return toast.error('먼저 상품을 등록하세요.')
  editing.value = null
  Object.assign(form, blankForm())
  Object.assign(psel, { complexId: '', categoryId: '', productCodeId: '', productDetailId: '' })
  modal.value = true
}
function openEdit(s) {
  editing.value = s
  Object.assign(form, {
    productId: s.productId,
    code: s.code,
    spec: s.spec || '',
    dimW: s.dimW ?? '',
    dimL: s.dimL ?? '',
    dimH: s.dimH ?? '',
    dimD: s.dimD ?? '',
    color: s.color || '',
    releaseYear: s.releaseYear || '',
    productionYear: s.productionYear || '',
    purpose: s.purpose || '',
    purposeSel: s.purpose ? (['판매용', '전시용', '내부비치', '소모'].includes(s.purpose) ? s.purpose : '기타') : '',
    imageUrl: s.imageUrl || '',
    price: s.price || 0,
    qty: s.qty || 0,
    safetyStock: s.safetyStock || 0,
    lifecycleEnabled: !!s.lifecycleEnabled,
    cycleValue: s.cycleValue || 0,
    cycleUnit: s.cycleUnit || 'month',
    lastReplacedAt: fmtDate(s.lastReplacedAt),
    replaceReason: s.replaceReason || '',
    lifecycleNote: s.lifecycleNote || '',
  })
  modal.value = true
}

function attrLine(s) {
  const a = []
  if (s.spec) a.push(s.spec)
  if (s.color) a.push(s.color)
  if (s.releaseYear) a.push(`출시 ${s.releaseYear}`)
  if (s.productionYear) a.push(`생산 ${s.productionYear}`)
  if (s.purpose) a.push(s.purpose)
  return a.join(' · ')
}

async function save() {
  if (!form.productId) return toast.error('상품을 선택하세요.')
  if (!hasAnyAttr())
    return toast.error('규격·색상·출시년도·생산년도·구매목적·단가·재고·안전재고 중 최소 1개는 입력해야 합니다.')
  const product = productList.value.find((p) => p.id === form.productId)
  // 연한관리 공통 페이로드 (다음 교체예정일 계산)
  const baseDate = form.lastReplacedAt ? new Date(form.lastReplacedAt) : null
  const lifecycle = {
    lifecycleEnabled: !!form.lifecycleEnabled,
    cycleValue: Number(form.cycleValue) || 0,
    cycleUnit: form.cycleUnit || 'month',
    lastReplacedAt: baseDate,
    nextReplaceAt: form.lifecycleEnabled && Number(form.cycleValue) ? addCycle(baseDate || new Date(), form.cycleValue, form.cycleUnit) : null,
    replaceReason: form.replaceReason.trim(),
    lifecycleNote: form.lifecycleNote.trim(),
  }
  // 치수(cm): 빈값은 null
  const num = (v) => (v === '' || v === null || v === undefined ? null : Number(v))
  const dims = { dimW: num(form.dimW), dimL: num(form.dimL), dimH: num(form.dimH), dimD: num(form.dimD) }
  try {
    if (editing.value) {
      // 재고수량은 입출고로만 변경, 코드는 수정 불가 (메타만 수정)
      await skus.update(editing.value.id, {
        spec: form.spec.trim(),
        ...dims,
        color: form.color.trim(),
        releaseYear: String(form.releaseYear || '').trim(),
        productionYear: String(form.productionYear || '').trim(),
        purpose: form.purpose.trim(),
        imageUrl: form.imageUrl || '',
        price: Number(form.price) || 0,
        safetyStock: Number(form.safetyStock) || 0,
        ...lifecycle,
      })
      toast.success('SKU가 수정되었습니다.')
    } else {
      const r = await skus.create({
        spec: form.spec.trim(),
        ...dims,
        color: form.color.trim(),
        releaseYear: form.releaseYear,
        productionYear: form.productionYear,
        purpose: form.purpose.trim(),
        imageUrl: form.imageUrl || '',
        ...lifecycle,
        productMainImageUrl: product.mainImageUrl || '',
        price: form.price,
        qty: form.qty,
        safetyStock: form.safetyStock,
        productId: product.id,
        productName: product.name,
        complexId: product.complexId,
        complexName: product.complexName,
        categoryId: product.categoryId,
        productCodeId: product.productCodeId,
        productDetailId: product.productDetailId,
        pathLabel: product.pathLabel || product.complexName,
      })
      toast.success(`SKU 생성 완료 (${r.code}) · QR 자동 발급`)
    }
    modal.value = false
    await load()
  } catch (e) {
    toast.error('저장 실패: ' + (e.message || e.code))
  }
}

async function remove(s) {
  const ok = await confirm.value.ask({
    title: 'SKU 삭제',
    message: `SKU "${s.code}" (재고 ${s.qty}개)를 삭제할까요?`,
    confirmText: '삭제',
    danger: true,
  })
  if (!ok) return
  try {
    await skus.remove(s.id)
    toast.success('삭제되었습니다.')
    await load()
  } catch (e) {
    toast.error('삭제 실패: ' + (e.message || e.code))
  }
}

/* ---- 선택/출력 ---- */
const allChecked = computed(() => filtered.value.length > 0 && filtered.value.every((s) => selected.value.has(s.id)))
function toggleAll() {
  if (allChecked.value) filtered.value.forEach((s) => selected.value.delete(s.id))
  else filtered.value.forEach((s) => selected.value.add(s.id))
  selected.value = new Set(selected.value)
}
function toggle(id) {
  selected.value.has(id) ? selected.value.delete(id) : selected.value.add(id)
  selected.value = new Set(selected.value)
}

function showQr(s) {
  qrSku.value = s
  qrModal.value = true
}

async function printSelected() {
  const targets = list.value.filter((s) => selected.value.has(s.id))
  if (!targets.length) return toast.error('출력할 SKU를 선택하세요.')
  printing.value = true
  try {
    printSheet.value = await makeQrBatch(targets)
    await new Promise((r) => setTimeout(r, 300))
    window.print()
    toast.success(`${targets.length}건 출력 준비 완료`)
  } catch (e) {
    toast.error('출력 실패: ' + (e.message || e.code))
  } finally {
    printing.value = false
  }
}

const statusMeta = {
  in_stock: { t: '정상', c: 'bg-emerald-50 text-emerald-700' },
  low: { t: '부족', c: 'bg-amber-50 text-amber-700' },
  out: { t: '품절', c: 'bg-rose-50 text-rose-600' },
}
</script>

<template>
  <div>
    <PageHeader title="SKU관리" subtitle="실제 재고는 SKU 단위로 관리됩니다. 생성 시 QR이 자동 발급됩니다.">
      <button class="btn-ghost no-print" :disabled="printing || !selected.size" @click="printSelected">
        {{ printing ? '준비 중…' : `QR 출력 (${selected.size})` }}
      </button>
      <button class="btn-primary no-print" @click="openCreate">+ SKU 추가</button>
    </PageHeader>

    <div class="no-print mb-3 flex flex-wrap items-center gap-2">
      <select v-model="filterComplex" class="input w-auto">
        <option value="">전체 단지</option>
        <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option>
      </select>
      <select v-model="fColor" class="input w-auto">
        <option value="">전체 색상</option>
        <option v-for="c in colorOptions" :key="c" :value="c">{{ c }}</option>
      </select>
      <select v-model="fRelease" class="input w-auto">
        <option value="">출시년도</option>
        <option v-for="y in releaseYearOptions" :key="y" :value="String(y)">{{ y }}</option>
      </select>
      <select v-model="fProduction" class="input w-auto">
        <option value="">생산년도</option>
        <option v-for="y in productionYearOptions" :key="y" :value="String(y)">{{ y }}</option>
      </select>
      <div class="flex items-center gap-1">
        <input v-model="priceMin" type="number" min="0" class="input w-24" placeholder="단가 최소" />
        <span class="text-slate-400">~</span>
        <input v-model="priceMax" type="number" min="0" class="input w-24" placeholder="최대" />
      </div>
      <input v-model="search" class="input w-full sm:w-64" placeholder="SKU코드/상품명/구매목적 검색" />
      <button class="btn-ghost btn-sm" @click="resetFilters">초기화</button>
      <select v-model="pageSize" class="input w-auto sm:ml-auto">
        <option v-for="n in sizes" :key="n" :value="n">{{ n }}개씩</option>
      </select>
    </div>

    <div class="card no-print">
      <div class="overflow-x-auto scrollbar-slim">
      <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
      <div v-else-if="!filtered.length" class="p-10 text-center text-sm text-slate-400">등록된 SKU가 없습니다.</div>
      <table v-else class="w-full min-w-[1040px] text-sm">
        <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th class="w-10 px-3 py-2.5"><input type="checkbox" class="rounded border-slate-300" :checked="allChecked" @change="toggleAll" /></th>
            <th class="px-3 py-2.5 font-semibold">SKU 코드</th>
            <th class="px-3 py-2.5 font-semibold">상품</th>
            <th class="px-3 py-2.5 font-semibold">규격</th>
            <th class="px-3 py-2.5 font-semibold">색상</th>
            <th class="px-3 py-2.5 font-semibold">출시</th>
            <th class="px-3 py-2.5 font-semibold">생산</th>
            <th class="px-3 py-2.5 text-right font-semibold">단가</th>
            <th class="px-3 py-2.5 font-semibold">재고</th>
            <th class="px-3 py-2.5 font-semibold">상태</th>
            <th class="px-3 py-2.5 text-right font-semibold">관리</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-for="s in paged" :key="s.id" class="hover:bg-slate-50/60" :class="selected.has(s.id) ? 'bg-brand-50/40' : ''">
            <td class="px-3 py-2.5"><input type="checkbox" class="rounded border-slate-300" :checked="selected.has(s.id)" @change="toggle(s.id)" /></td>
            <td class="px-3 py-2.5">
              <span class="badge bg-brand-50 font-mono text-brand-700">{{ s.code }}</span>
              <span class="ml-1 badge bg-slate-100 text-[10px] text-slate-400">QR</span>
            </td>
            <td class="px-3 py-2.5">
              <div class="flex items-center gap-2.5">
                <img :src="resolveImage(s)" class="h-10 w-10 shrink-0 rounded-lg border border-slate-100 object-cover" alt="" />
                <div class="min-w-0">
                  <p class="font-medium text-slate-800">{{ s.productName }}</p>
                  <p class="truncate text-[11px] text-slate-300">{{ s.pathLabel }}</p>
                </div>
              </div>
            </td>
            <td class="px-3 py-2.5 text-xs text-slate-600">{{ dimText(s) || s.spec || '—' }}</td>
            <td class="px-3 py-2.5 text-slate-600">{{ s.color || '—' }}</td>
            <td class="px-3 py-2.5 text-slate-500">{{ s.releaseYear || '—' }}</td>
            <td class="px-3 py-2.5 text-slate-500">{{ s.productionYear || '—' }}</td>
            <td class="px-3 py-2.5 text-right text-slate-700">{{ Number(s.price).toLocaleString() }}원</td>
            <td class="px-3 py-2.5 font-semibold text-slate-700">{{ s.qty }}개</td>
            <td class="px-3 py-2.5"><span class="badge" :class="statusMeta[s.status]?.c">{{ statusMeta[s.status]?.t }}</span></td>
            <td class="px-3 py-2.5 text-right">
              <button class="btn-ghost btn-sm" @click="showQr(s)">QR</button>
              <button class="btn-ghost btn-sm ml-1" @click="openEdit(s)">수정</button>
              <button class="btn-ghost btn-sm ml-1 text-rose-600" @click="remove(s)">삭제</button>
            </td>
          </tr>
        </tbody>
      </table>
      </div>
      <Pager v-if="filtered.length" v-model:page="page" :total="total" :total-pages="totalPages" class="border-t border-slate-100" />
    </div>

    <!-- 인쇄 라벨 시트 -->
    <div class="print-only">
      <div class="grid grid-cols-3 gap-3 p-2">
        <div v-for="item in printSheet" :key="item.id" class="flex break-inside-avoid items-center gap-2 rounded-lg border border-dashed border-slate-400 p-2">
          <img :src="item.qrDataUrl" class="h-20 w-20 shrink-0" alt="QR" />
          <div class="min-w-0 text-[10px] leading-tight">
            <p class="font-mono font-bold text-black">{{ item.code }}</p>
            <p class="truncate text-slate-700">{{ item.productName }}</p>
            <p class="text-slate-500">{{ item.spec }}</p>
            <p v-if="[item.color, item.releaseYear, item.productionYear].some(Boolean)" class="text-slate-600">
              {{ [item.color, item.releaseYear && '출시 ' + item.releaseYear, item.productionYear && '생산 ' + item.productionYear].filter(Boolean).join(' · ') }}
            </p>
            <p class="break-words font-semibold text-black">📍 {{ item.locationLabel ? item.complexName + ' > ' + item.locationLabel : (item.complexName || '위치 미지정') }}</p>
            <p class="mt-0.5 break-words text-slate-400">{{ item.pathLabel }}</p>
          </div>
        </div>
      </div>
    </div>

    <BaseModal v-model="modal" :title="editing ? 'SKU 수정' : 'SKU 추가'">
      <div class="space-y-3">
        <!-- 상품 찾기: 단지>카테고리>제품코드>(상세코드 선택) 로 좁히기 -->
        <div v-if="!editing" class="grid grid-cols-2 gap-2 rounded-lg bg-slate-50 p-2">
          <select v-model="psel.complexId" class="input text-sm" @change="onPselComplex">
            <option value="">전체 단지</option>
            <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
          <select v-model="psel.categoryId" class="input text-sm" :disabled="!psel.complexId" @change="onPselCategory">
            <option value="">전체 카테고리</option>
            <option v-for="c in pCategoryOptions" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
          <select v-model="psel.productCodeId" class="input text-sm" :disabled="!psel.categoryId" @change="onPselCode">
            <option value="">전체 제품코드</option>
            <option v-for="p in pCodeOptions" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
          <select v-model="psel.productDetailId" class="input text-sm" :disabled="!psel.productCodeId" @change="clearInvalidProduct">
            <option value="">전체 상세코드(선택)</option>
            <option v-for="d in pDetailOptions" :key="d.id" :value="d.id">{{ d.name }}</option>
          </select>
        </div>
        <div>
          <label class="label">상품 * <span class="text-slate-400">({{ filteredProducts.length }}건)</span></label>
          <select v-model="form.productId" class="input" :disabled="!!editing">
            <option value="">상품 선택</option>
            <option v-for="p in (editing ? productList : filteredProducts)" :key="p.id" :value="p.id">{{ p.name }} ({{ p.code }} · {{ p.pathLabel || p.complexName }})</option>
          </select>
        </div>
        <div>
          <label class="label">SKU 코드</label>
          <input v-if="editing" :value="form.code" class="input bg-slate-50 font-mono text-slate-400" readonly />
          <p v-else class="rounded-lg border border-dashed border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-400">
            상품코드 기반 자동 생성 (예: P-000001-001)
          </p>
        </div>
        <p class="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-700">
          아래 SKU 구분 속성(규격·색상·출시년도·생산년도·구매목적·단가·재고·안전재고) 중 <b>최소 1개</b>는 입력해야 저장됩니다.
        </p>
        <div>
          <label class="label">규격 (cm · 선택)</label>
          <div class="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <div class="flex items-center gap-1"><span class="shrink-0 text-xs text-slate-500">가로</span><input v-model="form.dimW" type="number" min="0" step="0.1" class="input px-2" /><span class="text-xs text-slate-400">cm</span></div>
            <div class="flex items-center gap-1"><span class="shrink-0 text-xs text-slate-500">세로</span><input v-model="form.dimL" type="number" min="0" step="0.1" class="input px-2" /><span class="text-xs text-slate-400">cm</span></div>
            <div class="flex items-center gap-1"><span class="shrink-0 text-xs text-slate-500">높이</span><input v-model="form.dimH" type="number" min="0" step="0.1" class="input px-2" /><span class="text-xs text-slate-400">cm</span></div>
            <div class="flex items-center gap-1"><span class="shrink-0 text-xs text-slate-500">깊이</span><input v-model="form.dimD" type="number" min="0" step="0.1" class="input px-2" /><span class="text-xs text-slate-400">cm</span></div>
          </div>
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="label">색상</label>
            <input v-model="form.color" class="input" placeholder="예: 화이트" />
          </div>
          <div>
            <label class="label">출시년도</label>
            <input v-model="form.releaseYear" type="number" min="1900" max="2999" class="input" placeholder="예: 2023" />
          </div>
          <div>
            <label class="label">생산년도</label>
            <input v-model="form.productionYear" type="number" min="1900" max="2999" class="input" placeholder="예: 2024" />
          </div>
          <div>
            <label class="label">구매목적</label>
            <select v-model="form.purposeSel" class="input" @change="onPurposeSel">
              <option value="">선택</option>
              <option v-for="p in PURPOSES" :key="p" :value="p">{{ p }}</option>
            </select>
          </div>
          <div v-if="form.purposeSel === '기타'" class="col-span-2">
            <label class="label">구매목적 직접 입력</label>
            <input v-model="form.purpose" class="input" placeholder="구매목적을 입력하세요" />
          </div>
          <div>
            <label class="label">단가(원)</label>
            <input v-model="priceDisplay" inputmode="numeric" class="input" placeholder="0" />
          </div>
          <div>
            <label class="label">안전재고</label>
            <input v-model.number="form.safetyStock" type="number" min="0" class="input" />
          </div>
        </div>
        <div>
          <label class="label">SKU 이미지 (선택)</label>
          <ImageUploader v-model="form.imageUrl" prefix="skus" size="sm" />
          <p class="mt-1 text-[11px] text-slate-400">없으면 상품 대표 이미지가 자동 표시됩니다.</p>
        </div>

        <!-- 연한관리 (주기 교체) -->
        <div class="rounded-lg border border-slate-200 p-3">
          <label class="flex items-center gap-2 text-sm font-semibold text-slate-700">
            <input v-model="form.lifecycleEnabled" type="checkbox" class="rounded border-slate-300" />
            연한관리(주기 교체) 사용
          </label>
          <div v-if="form.lifecycleEnabled" class="mt-3 space-y-3">
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="label">교체주기</label>
                <div class="flex gap-2">
                  <input v-model.number="form.cycleValue" type="number" min="0" class="input" />
                  <select v-model="form.cycleUnit" class="input w-24">
                    <option v-for="u in CYCLE_UNITS" :key="u.v" :value="u.v">{{ u.t }}</option>
                  </select>
                </div>
              </div>
              <div>
                <label class="label">기준일(설치/최근교체)</label>
                <input v-model="form.lastReplacedAt" type="date" class="input" />
              </div>
            </div>
            <div>
              <label class="label">교체 사유/유형</label>
              <input v-model="form.replaceReason" class="input" placeholder="예: 법정점검 / 마모 / 위생 / 배터리" />
            </div>
            <div>
              <label class="label">비고</label>
              <input v-model="form.lifecycleNote" class="input" />
            </div>
            <p v-if="nextReplacePreview" class="rounded-lg bg-brand-50 px-3 py-2 text-xs text-brand-700">다음 교체 예정일: <b>{{ nextReplacePreview }}</b></p>
          </div>
        </div>

        <div v-if="!editing">
          <label class="label">초기 재고수량</label>
          <input v-model.number="form.qty" type="number" min="0" class="input" />
          <p class="mt-1 text-xs text-slate-400">생성 후 재고는 입고/출고/조정으로만 변경됩니다.</p>
        </div>
      </div>
      <template #footer>
        <button class="btn-ghost" @click="modal = false">취소</button>
        <button class="btn-primary" :disabled="saving" @click="run(save)">{{ editing ? '수정' : '생성' }}</button>
      </template>
    </BaseModal>

    <QrModal v-model="qrModal" :sku="qrSku" />
    <ConfirmDialog ref="confirm" />
  </div>
</template>

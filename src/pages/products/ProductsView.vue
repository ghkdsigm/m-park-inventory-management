<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { products, categories, productCodes, productDetails, complexes, storageLocations } from '@/services/db'
import { useToast } from '@/composables/useToast'
import { useBusy } from '@/composables/useBusy'
import Pager from '@/components/ui/Pager.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import BaseModal from '@/components/ui/BaseModal.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import ImageUploader from '@/components/ui/ImageUploader.vue'
import { uploadImage, deleteImageByUrl } from '@/services/storage'
import { resolveProductImage, NO_IMAGE } from '@/utils/image'

const toast = useToast()
const { busy: saving, run } = useBusy()
const confirm = ref(null)

// 기준정보 체인 (카테고리·제품코드 필수, 제품상세코드만 선택) — 단지는 재고(SKU) 축으로 분리
const CHAIN = [
  { col: 'categories', board: categories, label: '카테고리', idField: 'categoryId', nameField: 'categoryName', required: true },
  { col: 'productCodes', board: productCodes, label: '제품코드', idField: 'productCodeId', nameField: 'productCodeName', parentId: 'categoryId', required: true },
  { col: 'productDetails', board: productDetails, label: '제품상세코드', idField: 'productDetailId', nameField: 'productDetailName', parentId: 'productCodeId' },
]

const loading = ref(true)
const list = ref([])
const data = reactive({}) // 기준정보 목록
const complexList = ref([]) // 단지 목록
const locList = ref([])     // 보관위치(보관위치) 목록
const search = ref('')
// 필터 기본값 '' → 셀렉트 "전체 …" 가 기본 선택되도록
const filterSel = reactive(Object.fromEntries(CHAIN.map((c) => [c.col, ''])))

// 위치 축 필터 (단지 → 구역 → 보관위치)
const filterComplex = ref('')
const filterZone = ref('')
const filterLoc = ref('')
const locForFilterComplex = computed(() => locList.value.filter((l) => l.complexId === filterComplex.value))
const zoneFilterChoices = computed(() => {
  const m = new Map()
  locForFilterComplex.value.forEach((l) => l.zoneId && m.set(l.zoneId, l.zoneName))
  return [...m].map(([id, name]) => ({ id, name }))
})
const locFilterOptions = computed(() => locForFilterComplex.value.filter((l) => !filterZone.value || l.zoneId === filterZone.value))
watch(filterComplex, () => { filterZone.value = ''; filterLoc.value = '' })
watch(filterZone, () => { filterLoc.value = '' })
watch([filterComplex, filterZone, filterLoc], () => { page.value = 1; fetchPage() })

async function loadMasters() {
  try {
    await Promise.all([
      ...CHAIN.map(async (c) => (data[c.col] = await c.board.list())),
      (async () => (complexList.value = await complexes.list()))(),
      (async () => (locList.value = await storageLocations.list()))(),
    ])
  } catch (e) { /* 옵션 로드 실패 무시 */ }
}
async function load() {
  await loadMasters()
  await fetchPage()
}
onMounted(load)

function options(idx, sel) {
  const c = CHAIN[idx]
  const all = data[c.col] || []
  if (idx === 0) return all
  const prev = CHAIN[idx - 1]
  const prevId = sel[prev.col]
  if (!prevId) return []
  return all.filter((d) => d[prev.idField] === prevId)
}

// 서버 페이징
const sizes = [10, 30, 50]
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
function curFilters() {
  return {
    complexId: filterComplex.value, zoneId: filterZone.value, storageLocationId: filterLoc.value,
    categoryId: filterSel.categories, productCodeId: filterSel.productCodes, productDetailId: filterSel.productDetails,
    search: search.value.trim(),
  }
}
async function fetchPage() {
  loading.value = true
  try {
    const r = await products.managePage({ ...curFilters(), page: page.value, pageSize: pageSize.value })
    list.value = r.rows
    total.value = r.total
  } catch (e) { toast.error('불러오기 실패: ' + (e.message || e.code)) } finally { loading.value = false }
}
// 상위 필터 변경 → 하위 무효 선택 정리 + 1페이지부터 재조회
watch(
  () => CHAIN.map((c) => filterSel[c.col]).join('|'),
  () => {
    CHAIN.forEach((c, i) => {
      if (i === 0) return
      if (filterSel[c.col] && !options(i, filterSel).find((o) => o.id === filterSel[c.col])) filterSel[c.col] = ''
    })
    page.value = 1
    fetchPage()
  }
)
let searchTimer = null
watch(search, () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => { page.value = 1; fetchPage() }, 350) })
watch(pageSize, () => { page.value = 1; fetchPage() })
watch(page, fetchPage)

/* ---- 생성/수정 ---- */
const modal = ref(false)
const editing = ref(null)
const form = reactive({ code: '', name: '', maker: '', barcode: '', note: '', price: 0, mainImageUrl: '', images: [], sel: {}, complexId: '', storageLocationId: '' })

// 선택한 단지의 보관위치만
const formLocs = computed(() => locList.value.filter((l) => l.complexId === form.complexId))
function onFormComplexChange() { form.storageLocationId = '' }
const selectedComplex = computed(() => complexList.value.find((c) => c.id === form.complexId))
const selectedLoc = computed(() => locList.value.find((l) => l.id === form.storageLocationId))
const selectedCategory = computed(() => (data.categories || []).find((c) => c.id === form.sel.categories) || null)
// 채번 미리보기: {단지코드}-{카테고리}-0001 (순번은 단지+카테고리별 1부터)
const codePreview = computed(() => {
  if (!selectedComplex.value || !selectedCategory.value) return ''
  const catToken = (selectedCategory.value.name || '').replace(/\s/g, '')
  return `${selectedComplex.value.code}-${catToken}-0001`
})
const addingImg = ref(false)
const galleryInput = ref(null)

async function onAddImages(e) {
  const files = Array.from(e.target.files || [])
  if (!files.length) return
  addingImg.value = true
  try {
    for (const f of files) {
      const { url } = await uploadImage(f, 'products/gallery')
      form.images.push(url)
    }
    toast.success(`이미지 ${files.length}장 추가됨`)
  } catch (err) {
    toast.error('업로드 실패: ' + (err.message || err.code))
  } finally {
    addingImg.value = false
    if (galleryInput.value) galleryInput.value.value = ''
  }
}
function removeImage(url) {
  form.images = form.images.filter((u) => u !== url)
  deleteImageByUrl(url)
}
const formOptions = (idx) => options(idx, form.sel)

function openCreate() {
  if (!(data.categories || []).length) return toast.error('먼저 카테고리를 1개 이상 등록하세요.')
  editing.value = null
  Object.assign(form, { code: '', name: '', maker: '', barcode: '', note: '', price: 0, mainImageUrl: '', images: [], sel: {}, complexId: '', storageLocationId: '' })
  CHAIN.forEach((c) => (form.sel[c.col] = filterSel[c.col] || ''))
  modal.value = true
}
function openEdit(p) {
  editing.value = p
  Object.assign(form, {
    code: p.code || '', name: p.name, maker: p.maker || '', barcode: p.barcode || '', note: p.note || '', price: p.price ?? 0,
    mainImageUrl: p.mainImageUrl || '', images: Array.isArray(p.images) ? [...p.images] : [], sel: {},
    complexId: p.complexId || '', storageLocationId: p.storageLocationId || '',
  })
  CHAIN.forEach((c) => (form.sel[c.col] = p[c.idField] || ''))
  modal.value = true
}
watch(
  () => CHAIN.map((c) => form.sel[c.col]).join('|'),
  () => {
    CHAIN.forEach((c, i) => {
      if (i === 0) return
      if (form.sel[c.col] && !formOptions(i).find((o) => o.id === form.sel[c.col])) form.sel[c.col] = ''
    })
  }
)

async function save() {
  if (!form.name.trim()) return toast.error('상품명을 입력하세요.')
  if (!editing.value) {
    if (!form.complexId) return toast.error('단지(필수)를 선택하세요.')
    // 보관위치는 선택(미지정 시 입고 때 지정). 카테고리 필수는 아래 CHAIN 검사에서 처리.
  }
  for (const c of CHAIN) {
    if (c.required && !form.sel[c.col]) return toast.error(`${c.label}(필수)를 선택하세요.`)
  }

  const payload = {
    name: form.name.trim(),
    maker: form.maker.trim(),
    barcode: form.barcode.trim(),
    note: form.note.trim(),
    price: Number(form.price) || 0,
    mainImageUrl: form.mainImageUrl || '',
    images: form.images || [],
    // 위치 소속(단지+보관위치). 상품코드가 이 조합으로 채번됨. 수정 시엔 서버가 무시(코드 고정).
    complexId: form.complexId || null,
    storageLocationId: form.storageLocationId || null,
  }
  const pathNames = []
  for (const c of CHAIN) {
    const id = form.sel[c.col]
    if (id) {
      const sel = (data[c.col] || []).find((d) => d.id === id)
      payload[c.idField] = id
      payload[c.nameField] = sel?.name || ''
      pathNames.push(sel?.name || '')
    } else {
      payload[c.idField] = null
      payload[c.nameField] = ''
    }
  }
  payload.pathLabel = pathNames.join(' > ')

  try {
    if (editing.value) {
      await products.update(editing.value.id, payload)
      toast.success('수정되었습니다.')
    } else {
      await products.create(payload)
      toast.success('상품이 등록되었습니다.')
    }
    modal.value = false
    await load()
  } catch (e) {
    toast.error('저장 실패: ' + (e.message || e.code))
  }
}

async function remove(p) {
  const ok = await confirm.value.ask({
    title: '상품 삭제',
    message: `"${p.name}" 상품을 삭제할까요?\n연결된 SKU는 함께 삭제되지 않으니 SKU관리에서 정리하세요.`,
    confirmText: '삭제',
    danger: true,
  })
  if (!ok) return
  try {
    await products.remove(p.id)
    toast.success('삭제되었습니다.')
    await load()
  } catch (e) {
    toast.error('삭제 실패: ' + (e.message || e.code))
  }
}
</script>

<template>
  <div>
    <PageHeader title="상품 넘버링" subtitle="단지+카테고리에 귀속(보관위치는 선택). 상품코드는 {단지}-{카테고리}-{순번}으로 채번(예: TWR-영선-0001). 같은 제품도 단지/카테고리가 다르면 별도 코드.">
      <button class="btn-primary" @click="openCreate">+ 상품 등록</button>
    </PageHeader>

    <div class="no-print mb-3 flex flex-wrap items-center gap-2">
      <AppSelect v-model="filterComplex" class="w-auto">
        <option value="">전체 단지</option>
        <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option>
      </AppSelect>
      <AppSelect v-model="filterZone" class="w-auto" :disabled="!filterComplex">
        <option value="">전체 구역</option>
        <option v-for="z in zoneFilterChoices" :key="z.id" :value="z.id">{{ z.name }}</option>
      </AppSelect>
      <AppSelect v-model="filterLoc" class="w-auto" :disabled="!filterComplex">
        <option value="">전체 보관위치</option>
        <option v-for="l in locFilterOptions" :key="l.id" :value="l.id">{{ l.code }} — {{ l.name || l.locationLabel || '위치' }}</option>
      </AppSelect>
      <AppSelect v-for="(c, i) in CHAIN" :key="c.col" v-model="filterSel[c.col]" class="w-auto">
        <option value="">전체 {{ c.label }}</option>
        <option v-for="o in options(i, filterSel)" :key="o.id" :value="o.id">{{ o.name }}</option>
      </AppSelect>
      <input v-model="search" class="input w-full sm:w-64" placeholder="상품명/제조사/바코드 검색" />
      <AppSelect v-model="pageSize" class="w-auto sm:ml-auto">
        <option v-for="n in sizes" :key="n" :value="n">{{ n }}개씩</option>
      </AppSelect>
    </div>

    <div class="card">
      <div class="overflow-x-auto scrollbar-slim">
      <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
      <div v-else-if="!list.length" class="p-10 text-center text-sm text-slate-400">등록된 상품이 없습니다.</div>
      <table v-else class="w-full min-w-[860px] text-sm">
        <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th class="px-4 py-2.5 font-semibold">상품코드</th>
            <th class="px-4 py-2.5 font-semibold">상품명</th>
            <th class="px-4 py-2.5 font-semibold">단지 · 보관위치</th>
            <th class="px-4 py-2.5 font-semibold">기준정보 경로</th>
            <th class="px-4 py-2.5 font-semibold">제조사</th>
            <th class="px-4 py-2.5 text-right font-semibold">관리</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-for="p in list" :key="p.id" class="hover:bg-slate-50/60">
            <td class="px-4 py-3"><span class="badge bg-brand-50 font-mono text-brand-700">{{ p.code }}</span></td>
            <td class="px-4 py-3">
              <div class="flex items-center gap-3">
                <img :src="resolveProductImage(p)" class="h-10 w-10 shrink-0 rounded-lg border border-slate-100 object-cover" alt="" />
                <div>
                  <p class="font-medium text-slate-800">{{ p.name }}</p>
                  <p v-if="p.barcode" class="text-xs text-slate-400">바코드 {{ p.barcode }}</p>
                </div>
              </div>
            </td>
            <td class="px-4 py-3 text-xs text-slate-500">
              <span v-if="p.complexName || p.storageLocationCode">📍 {{ p.complexName }}<span v-if="p.storageLocationCode" class="font-mono"> · {{ p.storageLocationCode }}</span><span v-if="p.locationLabel" class="text-slate-400"> › {{ p.locationLabel }}</span></span>
              <span v-else>—</span>
            </td>
            <td class="px-4 py-3 text-xs text-slate-500">{{ p.pathLabel || '—' }}</td>
            <td class="px-4 py-3 text-slate-500">{{ p.maker || '—' }}</td>
            <td class="px-4 py-3 text-right">
              <button class="btn-ghost btn-sm mr-1" @click="openEdit(p)">수정</button>
              <button class="btn-ghost btn-sm text-rose-600" @click="remove(p)">삭제</button>
            </td>
          </tr>
        </tbody>
      </table>
      </div>
      <Pager v-if="total" v-model:page="page" :total="total" :total-pages="totalPages" class="border-t border-slate-100" />
    </div>

    <BaseModal v-model="modal" :title="editing ? '상품 수정' : '상품 등록'" size="lg">
      <div class="space-y-3">
        <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div>
            <label class="label">단지 <span class="text-rose-500">*</span></label>
            <input v-if="editing" :value="selectedComplex?.name || form.complexId" class="input bg-slate-50 text-slate-400" readonly />
            <AppSelect v-else v-model="form.complexId" class="w-full" @change="onFormComplexChange">
              <option value="">단지 선택</option>
              <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }} ({{ c.code }})</option>
            </AppSelect>
          </div>
          <div>
            <label class="label">보관위치 <span class="text-slate-300">(선택)</span></label>
            <input v-if="editing" :value="selectedLoc ? selectedLoc.code : (form.storageLocationId || '미지정 (입고 때 지정)')" class="input bg-slate-50 font-mono text-slate-400" readonly />
            <AppSelect v-else v-model="form.storageLocationId" class="w-full" :disabled="!form.complexId">
              <option value="">{{ form.complexId ? '지정 안 함 (입고 때 선택)' : '단지 먼저 선택' }}</option>
              <option v-for="l in formLocs" :key="l.id" :value="l.id">{{ l.code }} — {{ l.name || l.locationLabel || '위치' }}</option>
            </AppSelect>
          </div>
        </div>
        <div>
          <label class="label">상품명 *</label>
          <input v-model="form.name" class="input" placeholder="예: 깨끗한나라 순수 3겹" />
        </div>

        <div>
          <label class="label">표준 단가</label>
          <input v-model.number="form.price" type="number" min="0" class="input" placeholder="예: 5000" />
          <p class="mt-1 text-[11px] text-slate-400">SKU 등록 시 이 단가를 기본값으로 상속합니다. (가격 단일 출처)</p>
        </div>

        <div>
          <label class="label">대표 이미지</label>
          <ImageUploader v-model="form.mainImageUrl" prefix="products/main" />
        </div>

        <div>
          <label class="label">추가 이미지 (다중)</label>
          <div class="flex flex-wrap items-center gap-2">
            <div v-for="url in form.images" :key="url" class="relative h-20 w-20 overflow-hidden rounded-lg border border-slate-200">
              <img :src="url" class="h-full w-full object-cover" alt="" />
              <button type="button" class="absolute right-0.5 top-0.5 rounded-full bg-slate-900/60 p-0.5 text-white" @click="removeImage(url)">
                <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M6 6l12 12M18 6L6 18" stroke-linecap="round"/></svg>
              </button>
            </div>
            <input ref="galleryInput" type="file" accept="image/*" multiple class="hidden" @change="onAddImages" />
            <button type="button" class="flex h-20 w-20 items-center justify-center rounded-lg border border-dashed border-slate-300 text-slate-400 hover:bg-slate-50" :disabled="addingImg" @click="galleryInput?.click()">
              <span v-if="addingImg" class="text-[11px]">업로드…</span>
              <svg v-else class="h-6 w-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 5v14M5 12h14" stroke-linecap="round"/></svg>
            </button>
          </div>
        </div>
        <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div v-for="(c, i) in CHAIN" :key="c.col">
            <label class="label">{{ c.label }} <span v-if="c.required" class="text-rose-500">*</span><span v-else class="text-slate-300"> (선택)</span></label>
            <AppSelect v-model="form.sel[c.col]" class="w-full">
              <option value="">{{ c.required ? '선택하세요' : '선택 안 함' }}</option>
              <option v-for="o in formOptions(i)" :key="o.id" :value="o.id">{{ o.name }} ({{ o.code }})</option>
            </AppSelect>
          </div>
        </div>
        <div>
          <label class="label">상품코드</label>
          <input v-if="editing" :value="form.code" class="input bg-slate-50 font-mono text-slate-400" readonly />
          <p v-else class="rounded-lg border border-dashed border-slate-200 bg-slate-50 px-3 py-2 text-sm" :class="codePreview ? 'text-brand-700 font-mono' : 'text-slate-400'">
            {{ codePreview ? `채번 예정: ${codePreview}  (SKU: ${codePreview}-001)` : '단지와 카테고리를 선택하면 상품코드가 정해집니다' }}
          </p>
        </div>
        <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div>
            <label class="label">제조사</label>
            <input v-model="form.maker" class="input" placeholder="예: 깨끗한나라" />
          </div>
          <div>
            <label class="label">바코드</label>
            <input v-model="form.barcode" class="input font-mono" placeholder="예: 8801234567890" />
          </div>
        </div>
        <div>
          <label class="label">비고</label>
          <textarea v-model="form.note" class="input" rows="2" />
        </div>
      </div>
      <template #footer>
        <button class="btn-ghost" @click="modal = false">취소</button>
        <button class="btn-primary" :disabled="saving" @click="run(save)">{{ editing ? '수정' : '등록' }}</button>
      </template>
    </BaseModal>

    <ConfirmDialog ref="confirm" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { products, complexes, categories, productCodes, productDetails } from '@/services/db'
import { useToast } from '@/composables/useToast'
import { useBusy } from '@/composables/useBusy'
import { usePagination } from '@/composables/usePagination'
import Pager from '@/components/ui/Pager.vue'
import BaseModal from '@/components/ui/BaseModal.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import ImageUploader from '@/components/ui/ImageUploader.vue'
import { uploadImage, deleteImageByUrl } from '@/services/storage'
import { resolveProductImage, NO_IMAGE } from '@/utils/image'

const toast = useToast()
const { busy: saving, run } = useBusy()
const confirm = ref(null)

// 기준정보 체인 (단지·카테고리·제품코드 필수, 제품상세코드만 선택)
const CHAIN = [
  { col: 'complexes', board: complexes, label: '단지', idField: 'complexId', nameField: 'complexName', required: true },
  { col: 'categories', board: categories, label: '카테고리', idField: 'categoryId', nameField: 'categoryName', parentId: 'complexId', required: true },
  { col: 'productCodes', board: productCodes, label: '제품코드', idField: 'productCodeId', nameField: 'productCodeName', parentId: 'categoryId', required: true },
  { col: 'productDetails', board: productDetails, label: '제품상세코드', idField: 'productDetailId', nameField: 'productDetailName', parentId: 'productCodeId' },
]

const loading = ref(true)
const list = ref([])
const data = reactive({}) // 기준정보 목록
const search = ref('')
// 필터 기본값 '' → 셀렉트 "전체 …" 가 기본 선택되도록
const filterSel = reactive(Object.fromEntries(CHAIN.map((c) => [c.col, ''])))

async function load() {
  loading.value = true
  try {
    list.value = await products.list()
    await Promise.all(CHAIN.map(async (c) => (data[c.col] = await c.board.list())))
  } catch (e) {
    toast.error('불러오기 실패: ' + (e.message || e.code))
  } finally {
    loading.value = false
  }
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

const filtered = computed(() =>
  list.value.filter((p) => {
    for (const c of CHAIN) if (filterSel[c.col] && p[c.idField] !== filterSel[c.col]) return false
    if (search.value) {
      const q = search.value.toLowerCase()
      return [p.name, p.maker, p.barcode, p.pathLabel].some((v) => (v || '').toLowerCase().includes(q))
    }
    return true
  })
)
const { paged, page, pageSize, sizes, total, totalPages } = usePagination(filtered)
watch(
  () => CHAIN.map((c) => filterSel[c.col]).join('|'),
  () => {
    CHAIN.forEach((c, i) => {
      if (i === 0) return
      if (filterSel[c.col] && !options(i, filterSel).find((o) => o.id === filterSel[c.col])) filterSel[c.col] = ''
    })
  }
)

/* ---- 생성/수정 ---- */
const modal = ref(false)
const editing = ref(null)
const form = reactive({ code: '', name: '', maker: '', barcode: '', note: '', mainImageUrl: '', images: [], sel: {} })
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
  if (!(data.complexes || []).length) return toast.error('먼저 단지를 1개 이상 등록하세요.')
  editing.value = null
  Object.assign(form, { code: '', name: '', maker: '', barcode: '', note: '', mainImageUrl: '', images: [], sel: {} })
  CHAIN.forEach((c) => (form.sel[c.col] = filterSel[c.col] || ''))
  modal.value = true
}
function openEdit(p) {
  editing.value = p
  Object.assign(form, {
    code: p.code || '', name: p.name, maker: p.maker || '', barcode: p.barcode || '', note: p.note || '',
    mainImageUrl: p.mainImageUrl || '', images: Array.isArray(p.images) ? [...p.images] : [], sel: {},
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
  for (const c of CHAIN) {
    if (c.required && !form.sel[c.col]) return toast.error(`${c.label}(필수)를 선택하세요.`)
  }

  const payload = {
    name: form.name.trim(),
    maker: form.maker.trim(),
    barcode: form.barcode.trim(),
    note: form.note.trim(),
    mainImageUrl: form.mainImageUrl || '',
    images: form.images || [],
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
    <PageHeader title="상품관리" subtitle="실제 재고/판매 대상. 기준정보에 연결합니다. (단지만 필수, 나머지 선택)">
      <button class="btn-primary" @click="openCreate">+ 상품 등록</button>
    </PageHeader>

    <div class="no-print mb-3 flex flex-wrap items-center gap-2">
      <select v-for="(c, i) in CHAIN" :key="c.col" v-model="filterSel[c.col]" class="input w-auto">
        <option value="">전체 {{ c.label }}</option>
        <option v-for="o in options(i, filterSel)" :key="o.id" :value="o.id">{{ o.name }}</option>
      </select>
      <input v-model="search" class="input w-full sm:w-64" placeholder="상품명/제조사/바코드 검색" />
      <select v-model="pageSize" class="input w-auto sm:ml-auto">
        <option v-for="n in sizes" :key="n" :value="n">{{ n }}개씩</option>
      </select>
    </div>

    <div class="card">
      <div class="overflow-x-auto scrollbar-slim">
      <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
      <div v-else-if="!filtered.length" class="p-10 text-center text-sm text-slate-400">등록된 상품이 없습니다.</div>
      <table v-else class="w-full text-sm">
        <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th class="px-4 py-2.5 font-semibold">상품명</th>
            <th class="hidden px-4 py-2.5 font-semibold md:table-cell">기준정보 경로</th>
            <th class="hidden px-4 py-2.5 font-semibold lg:table-cell">제조사</th>
            <th class="px-4 py-2.5 text-right font-semibold">관리</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-for="p in paged" :key="p.id" class="hover:bg-slate-50/60">
            <td class="px-4 py-3">
              <div class="flex items-center gap-3">
                <img :src="resolveProductImage(p)" class="h-10 w-10 shrink-0 rounded-lg border border-slate-100 object-cover" alt="" />
                <div>
                  <p class="font-medium text-slate-800">
                    <span class="badge mr-1 bg-brand-50 font-mono text-brand-700">{{ p.code }}</span>{{ p.name }}
                  </p>
                  <p v-if="p.barcode" class="text-xs text-slate-400">바코드 {{ p.barcode }}</p>
                </div>
              </div>
            </td>
            <td class="hidden px-4 py-3 text-xs text-slate-500 md:table-cell">{{ p.pathLabel || p.complexName }}</td>
            <td class="hidden px-4 py-3 text-slate-500 lg:table-cell">{{ p.maker || '—' }}</td>
            <td class="px-4 py-3 text-right">
              <button class="btn-ghost btn-sm mr-1" @click="openEdit(p)">수정</button>
              <button class="btn-ghost btn-sm text-rose-600" @click="remove(p)">삭제</button>
            </td>
          </tr>
        </tbody>
      </table>
      </div>
      <Pager v-if="filtered.length" v-model:page="page" :total="total" :total-pages="totalPages" class="border-t border-slate-100" />
    </div>

    <BaseModal v-model="modal" :title="editing ? '상품 수정' : '상품 등록'" size="lg">
      <div class="space-y-3">
        <div>
          <label class="label">상품코드</label>
          <input v-if="editing" :value="form.code" class="input bg-slate-50 font-mono text-slate-400" readonly />
          <p v-else class="rounded-lg border border-dashed border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-400">
            저장 시 자동 생성됩니다 (예: P-000001)
          </p>
        </div>
        <div>
          <label class="label">상품명 *</label>
          <input v-model="form.name" class="input" placeholder="예: 깨끗한나라 순수 3겹" />
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
            <select v-model="form.sel[c.col]" class="input">
              <option value="">{{ c.required ? '선택하세요' : '선택 안 함' }}</option>
              <option v-for="o in formOptions(i)" :key="o.id" :value="o.id">{{ o.name }} ({{ o.code }})</option>
            </select>
          </div>
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

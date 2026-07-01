<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { complexes, categories, productCodes, productDetails } from '@/services/db'
import { useToast } from '@/composables/useToast'
import { useBusy } from '@/composables/useBusy'
import { usePagination } from '@/composables/usePagination'
import Pager from '@/components/ui/Pager.vue'
import BaseModal from '@/components/ui/BaseModal.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import PageHeader from '@/components/ui/PageHeader.vue'

const route = useRoute()
const toast = useToast()
const { busy: saving, run } = useBusy()
const confirm = ref(null)

// 기준정보 4단계 메타 (idField/nameField = 다른 컬렉션이 이 단계를 참조할 때 쓰는 비정규화 필드)
const META = {
  complexes: { board: complexes, label: '단지', sub: '최상위 분류 (예: 서울창고, 부산창고)', idField: 'complexId', nameField: 'complexName', parent: null },
  categories: { board: categories, label: '카테고리', sub: '최상위 분류 (예: 가전/디지털, 생활용품)', idField: 'categoryId', nameField: 'categoryName', parent: null },
  productCodes: { board: productCodes, label: '제품코드', sub: '카테고리 하위 분류 (예: 모니터, 화장지)', idField: 'productCodeId', nameField: 'productCodeName', parent: 'categories' },
  productDetails: { board: productDetails, label: '제품상세코드', sub: '제품코드 하위 분류 (예: 휴대용 모니터, 두루마리 휴지)', idField: 'productDetailId', nameField: 'productDetailName', parent: 'productCodes' },
}

const key = computed(() => route.meta.masterKey)
const meta = computed(() => META[key.value])

// 단지만 코드 직접 입력, 나머지는 자동 생성
const PREFIX = { categories: 'CTG', productCodes: 'PC', productDetails: 'PCD' }
const isAuto = computed(() => key.value !== 'complexes')
const samplePrefix = computed(() => PREFIX[key.value] || '')

// 루트→직속부모 순서의 조상 컬렉션 체인
const chain = computed(() => {
  const arr = []
  let p = meta.value.parent
  while (p) {
    arr.unshift(p)
    p = META[p].parent
  }
  return arr // 예: productDetails → ['complexes','categories','productCodes']
})

const loading = ref(true)
const ownList = ref([])
const data = reactive({}) // 컬렉션별 전체 목록 (조상 선택용)
const filterSel = reactive({}) // 상단 필터 선택값
// 필터 기본값을 '' 로 → 셀렉트의 "전체 …" 가 기본 선택되도록
watch(
  chain,
  (cols) => cols.forEach((c) => { if (filterSel[c] === undefined) filterSel[c] = '' }),
  { immediate: true }
)

async function load() {
  loading.value = true
  try {
    ownList.value = await meta.value.board.list()
    await Promise.all(
      chain.value.map(async (c) => {
        data[c] = await META[c].board.list()
      })
    )
  } catch (e) {
    toast.error('목록을 불러오지 못했습니다: ' + (e.message || e.code))
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(key, () => {
  Object.keys(filterSel).forEach((k) => delete filterSel[k])
  chain.value.forEach((c) => (filterSel[c] = '')) // 새 단계 필터도 "전체" 기본값
  load()
})

// 특정 단계의 옵션 (직전 선택에 따라 필터)
function optionsFor(col, sel, idx) {
  const list = data[col] || []
  if (idx === 0) return list
  const prevCol = chain.value[idx - 1]
  const prevId = sel[prevCol]
  if (!prevId) return []
  return list.filter((d) => d[META[prevCol].idField] === prevId)
}

// 상단 필터 적용된 목록
const filtered = computed(() =>
  ownList.value.filter((o) =>
    chain.value.every((c) => !filterSel[c] || o[META[c].idField] === filterSel[c])
  )
)
const { paged, page, pageSize, sizes, total, totalPages } = usePagination(filtered)
// 상위 단계 필터가 바뀌면 하위 필터 초기화
watch(
  () => chain.value.map((c) => filterSel[c]).join('|'),
  () => {
    chain.value.forEach((c, i) => {
      if (i === 0) return
      const prev = filterSel[chain.value[i - 1]]
      const opts = optionsFor(c, filterSel, i)
      if (filterSel[c] && (!prev || !opts.find((o) => o.id === filterSel[c]))) filterSel[c] = ''
    })
  }
)

/* ---- 생성/수정 ---- */
const modal = ref(false)
const editing = ref(null)
const form = reactive({ code: '', name: '', description: '', sel: {} })

const formOptions = (col, idx) => optionsFor(col, form.sel, idx)

function openCreate() {
  // 상위 단계가 비어있으면 막기
  for (const c of chain.value) {
    if (!(data[c] || []).length) {
      toast.error(`먼저 상위 "${META[c].label}"를 1개 이상 등록하세요.`)
      return
    }
  }
  editing.value = null
  form.code = ''
  form.name = ''
  form.description = ''
  form.sel = {}
  // 현재 필터값을 기본 선택으로
  chain.value.forEach((c) => (form.sel[c] = filterSel[c] || ''))
  modal.value = true
}
function openEdit(item) {
  editing.value = item
  form.code = item.code
  form.name = item.name
  form.description = item.description || ''
  form.sel = {}
  chain.value.forEach((c) => (form.sel[c] = item[META[c].idField] || ''))
  modal.value = true
}

// 폼: 상위 선택 변경 시 하위 선택 정리
watch(
  () => chain.value.map((c) => form.sel[c]).join('|'),
  () => {
    chain.value.forEach((c, i) => {
      if (i === 0) return
      const opts = formOptions(c, i)
      if (form.sel[c] && !opts.find((o) => o.id === form.sel[c])) form.sel[c] = ''
    })
  }
)

async function save() {
  if (!form.name.trim()) return toast.error('이름을 입력하세요.')
  if (!isAuto.value && !form.code.trim()) return toast.error('코드를 입력하세요.')
  for (const c of chain.value) {
    if (!form.sel[c]) return toast.error(`상위 "${META[c].label}"를 선택하세요.`)
  }
  // 조상 비정규화 필드 + 경로 라벨 구성 (자동코드형은 code 미포함 → 생성/유지)
  const payload = { name: form.name.trim(), description: form.description.trim() }
  if (!isAuto.value) payload.code = form.code.trim()
  const pathNames = []
  chain.value.forEach((c) => {
    const sel = (data[c] || []).find((d) => d.id === form.sel[c])
    payload[META[c].idField] = form.sel[c]
    payload[META[c].nameField] = sel?.name || ''
    pathNames.push(sel?.name || '')
  })
  // 단지(최상위)는 path_label 컬럼이 없으므로 조상이 있을 때만 포함
  if (chain.value.length) payload.pathLabel = pathNames.join(' > ')

  try {
    if (editing.value) {
      await meta.value.board.update(editing.value.id, payload)
      toast.success('수정되었습니다.')
    } else {
      await meta.value.board.create(payload)
      toast.success(`${meta.value.label}가 생성되었습니다.`)
    }
    modal.value = false
    await load()
  } catch (e) {
    toast.error('저장 실패: ' + (e.message || e.code))
  }
}

async function remove(item) {
  const ok = await confirm.value.ask({
    title: `${meta.value.label} 삭제`,
    message: `"${item.name}"를 삭제할까요?\n하위 항목은 함께 삭제되지 않으니 주의하세요.`,
    confirmText: '삭제',
    danger: true,
  })
  if (!ok) return
  try {
    await meta.value.board.remove(item.id)
    toast.success('삭제되었습니다.')
    await load()
  } catch (e) {
    toast.error('삭제 실패: ' + (e.message || e.code))
  }
}
</script>

<template>
  <div>
    <PageHeader :title="meta.label + '관리'" :subtitle="meta.sub">
      <select v-model="pageSize" class="input w-auto"><option v-for="n in sizes" :key="n" :value="n">{{ n }}개씩</option></select>
      <button class="btn-primary" @click="openCreate">+ {{ meta.label }} 추가</button>
    </PageHeader>

    <!-- 상위 단계 필터 -->
    <div v-if="chain.length" class="no-print mb-3 flex flex-wrap items-center gap-2">
      <select v-for="(c, i) in chain" :key="c" v-model="filterSel[c]" class="input w-auto">
        <option value="">전체 {{ META[c].label }}</option>
        <option v-for="o in optionsFor(c, filterSel, i)" :key="o.id" :value="o.id">{{ o.name }}</option>
      </select>
    </div>

    <div class="card">
      <div class="overflow-x-auto scrollbar-slim">
      <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
      <div v-else-if="!filtered.length" class="p-10 text-center text-sm text-slate-400">등록된 {{ meta.label }}가 없습니다.</div>
      <table v-else class="w-full text-sm">
        <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th v-if="chain.length" class="px-4 py-2.5 font-semibold">상위 경로</th>
            <th class="px-4 py-2.5 font-semibold">코드</th>
            <th class="px-4 py-2.5 font-semibold">{{ meta.label }}명</th>
            <th class="hidden px-4 py-2.5 font-semibold sm:table-cell">설명</th>
            <th class="px-4 py-2.5 text-right font-semibold">관리</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-for="item in paged" :key="item.id" class="hover:bg-slate-50/60">
            <td v-if="chain.length" class="px-4 py-3 text-xs text-slate-400">{{ item.pathLabel || '—' }}</td>
            <td class="px-4 py-3"><span class="badge bg-brand-50 font-mono text-brand-700">{{ item.code }}</span></td>
            <td class="px-4 py-3 font-medium text-slate-800">{{ item.name }}</td>
            <td class="hidden px-4 py-3 text-slate-500 sm:table-cell">{{ item.description || '—' }}</td>
            <td class="px-4 py-3 text-right">
              <button class="btn-ghost btn-sm mr-1" @click="openEdit(item)">수정</button>
              <button class="btn-ghost btn-sm text-rose-600" @click="remove(item)">삭제</button>
            </td>
          </tr>
        </tbody>
      </table>
      </div>
      <Pager v-if="filtered.length" v-model:page="page" :total="total" :total-pages="totalPages" class="border-t border-slate-100" />
    </div>

    <BaseModal v-model="modal" :title="(editing ? meta.label + ' 수정' : meta.label + ' 추가')">
      <div class="space-y-3">
        <div v-for="(c, i) in chain" :key="c">
          <label class="label">상위 {{ META[c].label }} *</label>
          <select v-model="form.sel[c]" class="input">
            <option value="">선택하세요</option>
            <option v-for="o in formOptions(c, i)" :key="o.id" :value="o.id">{{ o.name }} ({{ o.code }})</option>
          </select>
        </div>
        <div v-if="!isAuto">
          <label class="label">{{ meta.label }} 코드 *</label>
          <input v-model="form.code" class="input font-mono" placeholder="예: DANJI-000001" />
        </div>
        <div v-else>
          <label class="label">{{ meta.label }} 코드</label>
          <input v-if="editing" :value="form.code" class="input bg-slate-50 font-mono text-slate-400" readonly />
          <p v-else class="rounded-lg border border-dashed border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-400">
            저장 시 자동 생성됩니다 (예: {{ samplePrefix }}-000001)
          </p>
        </div>
        <div>
          <label class="label">{{ meta.label }}명 *</label>
          <input v-model="form.name" class="input" placeholder="이름" />
        </div>
        <div>
          <label class="label">설명</label>
          <textarea v-model="form.description" class="input" rows="2" />
        </div>
      </div>
      <template #footer>
        <button class="btn-ghost" @click="modal = false">취소</button>
        <button class="btn-primary" :disabled="saving" @click="run(save)">{{ editing ? '수정' : '생성' }}</button>
      </template>
    </BaseModal>

    <ConfirmDialog ref="confirm" />
  </div>
</template>

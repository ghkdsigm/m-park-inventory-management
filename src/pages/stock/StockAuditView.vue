<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { skus, complexes, categories, productCodes, productDetails, applyAuditBatch } from '@/services/db'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import Pager from '@/components/ui/Pager.vue'
import { resolveImage } from '@/utils/image'
import { specText } from '@/utils/sku'

const auth = useAuthStore()
const toast = useToast()
const confirm = ref(null)

const loading = ref(true)
const working = ref(false)
const rows = ref([]) // 현재 페이지
const complexList = ref([])
const categoryList = ref([])
const productCodeList = ref([])
const productDetailList = ref([])
// 페이지를 넘나들며 입력값/시스템값을 누적 보존
const counts = reactive({}) // skuId -> 실사수량
const sysQty = reactive({}) // skuId -> 시스템 재고(스냅샷)
const skuMeta = reactive({}) // skuId -> { code }

const fComplex = ref('')
const fCategory = ref('')
const fProductCode = ref('')
const fProductDetail = ref('')
const search = ref('')
const onlyDiff = ref(false)
const memo = ref('')

// 서버 페이징
const sizes = [10, 30, 50]
const page = ref(1)
const pageSize = ref(30)
const total = ref(0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

async function fetchPage() {
  loading.value = true
  try {
    const r = await skus.page({
      complexId: fComplex.value,
      categoryId: fCategory.value,
      productCodeId: fProductCode.value,
      productDetailId: fProductDetail.value,
      search: search.value.trim(),
      page: page.value,
      pageSize: pageSize.value,
    })
    rows.value = r.rows
    total.value = r.total
    // 시스템값/메타 스냅샷 + 미입력 항목은 시스템값으로 초기화(입력값은 보존)
    r.rows.forEach((s) => {
      sysQty[s.id] = s.qty
      skuMeta[s.id] = { code: s.code }
      if (!(s.id in counts)) counts[s.id] = s.qty
    })
  } catch (e) {
    toast.error('불러오기 실패: ' + (e.message || e.code))
  } finally {
    loading.value = false
  }
}
async function loadMasters() {
  try {
    ;[complexList.value, categoryList.value, productCodeList.value, productDetailList.value] = await Promise.all([
      complexes.list(),
      categories.list(),
      productCodes.list(),
      productDetails.list(),
    ])
  } catch (e) {
    /* 옵션 로드 실패 무시 */
  }
}
function clearCounts() {
  Object.keys(counts).forEach((k) => delete counts[k])
}
onMounted(async () => { await loadMasters(); await fetchPage() })

watch([fComplex, fCategory, fProductCode, fProductDetail], () => { page.value = 1; fetchPage() })
watch(pageSize, () => { page.value = 1; fetchPage() })
watch(page, fetchPage)
let searchTimer = null
watch(search, () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => { page.value = 1; fetchPage() }, 350) })

const categoryOptions = computed(() => (fComplex.value ? categoryList.value.filter((c) => c.complexId === fComplex.value) : categoryList.value))
const pcOptions = computed(() => (fCategory.value ? productCodeList.value.filter((p) => p.categoryId === fCategory.value) : productCodeList.value))
const pdOptions = computed(() => (fProductCode.value ? productDetailList.value.filter((d) => d.productCodeId === fProductCode.value) : productDetailList.value))
watch(fComplex, () => { fCategory.value = ''; fProductCode.value = ''; fProductDetail.value = '' })
watch(fCategory, () => { fProductCode.value = ''; fProductDetail.value = '' })
watch(fProductCode, () => { fProductDetail.value = '' })

function diffOf(s) {
  const c = counts[s.id]
  if (c === '' || c == null) return 0
  return Number(c) - Number(s.qty)
}

// 현재 페이지 표시 행 ('차이만'은 현재 페이지 내에서 적용)
const displayRows = computed(() => (onlyDiff.value ? rows.value.filter((s) => diffOf(s) !== 0) : rows.value))

// 차이 발생 항목은 페이지 누적(counts/sysQty 맵 기준)
const changed = computed(() =>
  Object.keys(counts)
    .filter((id) => {
      const c = counts[id]
      const sys = sysQty[id]
      return c !== '' && c != null && sys != null && Number(c) !== Number(sys)
    })
    .map((id) => ({ id, code: skuMeta[id]?.code || id, counted: Number(counts[id]), diff: Number(counts[id]) - Number(sysQty[id]) }))
)
const diffSum = computed(() => changed.value.reduce((a, x) => a + x.diff, 0))

function attrLine(s) {
  return [specText(s), s.color, s.releaseYear && `출시 ${s.releaseYear}`, s.purpose].filter(Boolean).join(' · ')
}

// 위치 검증 선택
const locSel = ref(new Set())
function toggleLoc(id) {
  locSel.value.has(id) ? locSel.value.delete(id) : locSel.value.add(id)
  locSel.value = new Set(locSel.value)
}
async function verifyLocations() {
  if (!locSel.value.size) return toast.error('검증할 항목을 선택하세요.')
  working.value = true
  try {
    const ids = [...locSel.value]
    for (const id of ids) await skus.verifyLocation(id, auth.actor)
    toast.success(`위치 ${ids.length}건 검증 확정`)
    locSel.value = new Set()
    await fetchPage()
  } catch (e) {
    toast.error('위치 검증 실패: ' + (e.message || e.code))
  } finally {
    working.value = false
  }
}
function resetCounts() {
  clearCounts()
  rows.value.forEach((s) => (counts[s.id] = s.qty))
  toast.info('실사수량을 시스템 재고로 초기화했습니다.')
}

async function confirmAudit() {
  if (!changed.value.length) return toast.error('차이가 있는 항목이 없습니다.')
  for (const x of changed.value) {
    if (x.counted < 0) return toast.error(`실사수량은 0 이상이어야 합니다. (${x.code})`)
  }
  const ok = await confirm.value.ask({
    title: '재고실사 확정',
    message: `차이 발생 ${changed.value.length}건을 실사 수량으로 확정합니다.\n시스템 재고가 실사값으로 보정되고 원장에 '실사'로 기록됩니다.`,
    confirmText: '실사 확정',
  })
  if (!ok) return
  working.value = true
  try {
    const items = changed.value.map((x) => ({ skuId: x.id, counted: x.counted }))
    const r = await applyAuditBatch(items, auth.actor, memo.value)
    toast.success(`실사 확정 완료 · ${r.changed}건 반영`)
    clearCounts()
    await fetchPage()
    memo.value = ''
  } catch (e) {
    toast.error('실사 실패: ' + (e.message || e.code))
  } finally {
    working.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader title="재고실사" subtitle="실물을 카운트해 실사수량을 입력하고, 시스템 재고와의 차이를 일괄 확정합니다.">
      <button class="btn-ghost" :disabled="working" @click="resetCounts">초기화</button>
      <button class="btn-ghost" :disabled="working || !locSel.size" @click="verifyLocations">위치 검증 ({{ locSel.size }})</button>
      <button class="btn-primary" :disabled="working || !changed.length" @click="confirmAudit">
        {{ working ? '처리 중…' : `실사 확정 (${changed.length})` }}
      </button>
    </PageHeader>

    <!-- 요약 -->
    <div class="mb-4 grid grid-cols-3 gap-3">
      <div class="card p-3 text-center"><p class="text-xs text-slate-400">대상 SKU</p><p class="text-xl font-bold text-slate-800">{{ total.toLocaleString() }}</p></div>
      <div class="card p-3 text-center"><p class="text-xs text-slate-400">차이 발생</p><p class="text-xl font-bold text-amber-500">{{ changed.length }}</p></div>
      <div class="card p-3 text-center"><p class="text-xs text-slate-400">차이 합계</p><p class="text-xl font-bold" :class="diffSum < 0 ? 'text-rose-500' : 'text-emerald-600'">{{ diffSum > 0 ? '+' : '' }}{{ diffSum }}</p></div>
    </div>

    <!-- 필터 -->
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <select v-model="fComplex" class="input w-auto"><option value="">전체 단지</option><option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option></select>
      <select v-model="fCategory" class="input w-auto"><option value="">전체 카테고리</option><option v-for="c in categoryOptions" :key="c.id" :value="c.id">{{ c.name }}</option></select>
      <select v-model="fProductCode" class="input w-auto"><option value="">전체 제품코드</option><option v-for="p in pcOptions" :key="p.id" :value="p.id">{{ p.name }}</option></select>
      <select v-model="fProductDetail" class="input w-auto"><option value="">전체 상세코드</option><option v-for="d in pdOptions" :key="d.id" :value="d.id">{{ d.name }}</option></select>
      <input v-model="search" class="input w-full sm:w-64" placeholder="SKU코드/상품명 검색" />
      <label class="flex items-center gap-1.5 text-sm text-slate-500"><input v-model="onlyDiff" type="checkbox" class="rounded border-slate-300" /> 차이만</label>
      <input v-model="memo" class="input w-auto sm:max-w-[200px]" placeholder="실사 메모 (예: 2026-06 정기실사)" />
      <select v-model="pageSize" class="input w-auto sm:ml-auto">
        <option v-for="n in sizes" :key="n" :value="n">{{ n }}개씩</option>
      </select>
    </div>

    <div class="card">
      <div class="overflow-x-auto scrollbar-slim">
      <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
      <div v-else-if="!displayRows.length" class="p-10 text-center text-sm text-slate-400">{{ onlyDiff ? '이 페이지에 차이 항목이 없습니다.' : '대상 SKU가 없습니다.' }}</div>
      <table v-else class="w-full min-w-[820px] text-sm">
        <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th class="px-3 py-2.5 font-semibold">SKU / 상품</th>
            <th class="px-3 py-2.5 font-semibold">위치 (검증)</th>
            <th class="px-3 py-2.5 text-right font-semibold">시스템</th>
            <th class="px-3 py-2.5 text-center font-semibold">실사수량</th>
            <th class="px-3 py-2.5 text-right font-semibold">차이</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-for="s in displayRows" :key="s.id" class="hover:bg-slate-50/60" :class="diffOf(s) !== 0 ? 'bg-amber-50/40' : ''">
            <td class="px-3 py-2">
              <div class="flex items-center gap-2.5">
                <img :src="resolveImage(s)" class="h-9 w-9 shrink-0 rounded border border-slate-100 object-cover" alt="" />
                <div class="min-w-0">
                  <span class="badge bg-brand-50 font-mono text-brand-700">{{ s.code }}</span>
                  <p class="text-slate-700">{{ s.productName }}</p>
                  <p class="truncate text-[11px] text-slate-400">{{ attrLine(s) }}</p>
                </div>
              </div>
            </td>
            <td class="px-3 py-2">
              <label class="flex items-center gap-1.5">
                <input type="checkbox" class="rounded border-slate-300" :checked="locSel.has(s.id)" @change="toggleLoc(s.id)" />
                <span class="min-w-0">
                  <span v-if="s.locationLabel" class="block truncate text-xs text-slate-600">📍 {{ s.locationLabel }}</span>
                  <span v-else class="block text-xs text-slate-300">위치 미지정</span>
                  <span v-if="s.locationVerifiedAt" class="block text-[10px] text-emerald-600">✓ 검증됨</span>
                </span>
              </label>
            </td>
            <td class="px-3 py-2 text-right font-semibold text-slate-500">{{ s.qty }}</td>
            <td class="px-3 py-2 text-center">
              <input v-model.number="counts[s.id]" type="number" min="0" class="input w-24 text-center" />
            </td>
            <td class="px-3 py-2 text-right font-bold" :class="diffOf(s) === 0 ? 'text-slate-300' : diffOf(s) < 0 ? 'text-rose-500' : 'text-emerald-600'">
              {{ diffOf(s) > 0 ? '+' : '' }}{{ diffOf(s) }}
            </td>
          </tr>
        </tbody>
      </table>
      </div>
      <Pager v-if="total && !onlyDiff" v-model:page="page" :total="total" :total-pages="totalPages" class="border-t border-slate-100" />
    </div>

    <ConfirmDialog ref="confirm" />
  </div>
</template>

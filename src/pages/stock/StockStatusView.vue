<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { skus, complexes, categories, productCodes, productDetails, listLocationLogs } from '@/services/db'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import BaseModal from '@/components/ui/BaseModal.vue'
import Pager from '@/components/ui/Pager.vue'
import { usePagination } from '@/composables/usePagination'
import { resolveImage } from '@/utils/image'
import { lifecycleStatus, toJsDate, fmtDateTime } from '@/utils/date'

function lifeBadge(s) {
  if (!s.lifecycleEnabled) return null
  const st = lifecycleStatus(s.nextReplaceAt)
  if (st === 'over') return { t: '교체초과', c: 'bg-rose-50 text-rose-600' }
  if (st === 'soon') return { t: '교체임박', c: 'bg-amber-50 text-amber-700' }
  return null
}

// 보관위치 + 변경 이력 팝업
const locModal = ref(false)
const locSku = ref(null)
const locLogs = ref([])
const locLoading = ref(false)
async function openLocation(s) {
  locSku.value = s
  locLogs.value = []
  locModal.value = true
  locLoading.value = true
  try {
    locLogs.value = await listLocationLogs(s.id, 50)
  } catch (e) {
    /* 무시 */
  } finally {
    locLoading.value = false
  }
}

const toast = useToast()
const loading = ref(true)
const list = ref([])
const complexList = ref([])
const categoryList = ref([])
const productCodeList = ref([])
const productDetailList = ref([])

const search = ref('')
const fComplex = ref('')
const fCategory = ref('')
const fProductCode = ref('')
const fProductDetail = ref('')
const fStatus = ref('')
const sort = ref('recent')
const groupByComplex = ref(false)
const showTotal = ref(false)

const SORTS = [
  { v: 'recent', t: '최신순' },
  { v: 'qtyDesc', t: '재고 많은순' },
  { v: 'qtyAsc', t: '재고 적은순' },
  { v: 'outDesc', t: '사용량(출고)순' },
  { v: 'inDesc', t: '입고량순' },
  { v: 'code', t: 'SKU코드순' },
]
const STATUS = [
  { v: '', t: '전체 상태' },
  { v: 'in_stock', t: '정상' },
  { v: 'low', t: '부족' },
  { v: 'out', t: '품절' },
]

async function load() {
  loading.value = true
  try {
    ;[list.value, complexList.value, categoryList.value, productCodeList.value, productDetailList.value] = await Promise.all([
      skus.list(),
      complexes.list(),
      categories.list(),
      productCodes.list(),
      productDetails.list(),
    ])
  } catch (e) {
    toast.error('불러오기 실패: ' + (e.message || e.code))
  } finally {
    loading.value = false
  }
}
onMounted(load)

const categoryOptions = computed(() => (fComplex.value ? categoryList.value.filter((c) => c.complexId === fComplex.value) : categoryList.value))
const productCodeOptions = computed(() => (fCategory.value ? productCodeList.value.filter((p) => p.categoryId === fCategory.value) : productCodeList.value))
const productDetailOptions = computed(() => (fProductCode.value ? productDetailList.value.filter((d) => d.productCodeId === fProductCode.value) : productDetailList.value))
watch(fComplex, () => { fCategory.value = ''; fProductCode.value = ''; fProductDetail.value = '' })
watch(fCategory, () => { fProductCode.value = ''; fProductDetail.value = '' })
watch(fProductCode, () => { fProductDetail.value = '' })

const filtered = computed(() => {
  let arr = list.value.filter((s) => {
    if (fComplex.value && s.complexId !== fComplex.value) return false
    if (fCategory.value && s.categoryId !== fCategory.value) return false
    if (fProductCode.value && s.productCodeId !== fProductCode.value) return false
    if (fProductDetail.value && s.productDetailId !== fProductDetail.value) return false
    if (fStatus.value && s.status !== fStatus.value) return false
    if (search.value) {
      const q = search.value.toLowerCase()
      return [s.code, s.productName, s.spec, s.pathLabel].some((v) => (v || '').toLowerCase().includes(q))
    }
    return true
  })
  const cmp = {
    recent: (a, b) => (toJsDate(b.createdAt)?.getTime() || 0) - (toJsDate(a.createdAt)?.getTime() || 0),
    qtyDesc: (a, b) => b.qty - a.qty,
    qtyAsc: (a, b) => a.qty - b.qty,
    outDesc: (a, b) => (b.totalOut || 0) - (a.totalOut || 0),
    inDesc: (a, b) => (b.totalIn || 0) - (a.totalIn || 0),
    code: (a, b) => (a.code > b.code ? 1 : -1),
  }
  return [...arr].sort(cmp[sort.value])
})
const { paged, page, pageSize, sizes, total, totalPages } = usePagination(filtered)

const grouped = computed(() => {
  const g = {}
  filtered.value.forEach((s) => (g[s.complexName || '미지정'] ||= []).push(s))
  return g
})

const stats = computed(() => ({
  skuCount: filtered.value.length,
  totalQty: filtered.value.reduce((a, s) => a + (Number(s.qty) || 0), 0),
  low: filtered.value.filter((s) => s.status === 'low').length,
  out: filtered.value.filter((s) => s.status === 'out').length,
}))

const statusMeta = {
  in_stock: { t: '정상', c: 'bg-emerald-50 text-emerald-700' },
  low: { t: '부족', c: 'bg-amber-50 text-amber-700' },
  out: { t: '품절', c: 'bg-rose-50 text-rose-600' },
}

function resetFilters() {
  search.value = ''; fComplex.value = ''; fCategory.value = ''; fProductCode.value = ''; fProductDetail.value = ''; fStatus.value = ''; sort.value = 'recent'
}
</script>

<template>
  <div>
    <PageHeader title="재고현황" subtitle="SKU 기준 실시간 재고. 단지·카테고리·제품코드별 필터와 정렬을 제공합니다.">
      <label class="flex items-center gap-1.5 text-sm text-slate-500">
        <input v-model="groupByComplex" type="checkbox" class="rounded border-slate-300" /> 단지별 묶기
      </label>
    </PageHeader>

    <!-- 요약 -->
    <div class="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
      <div class="card p-3 text-center"><p class="text-xs text-slate-400">SKU 수</p><p class="text-xl font-bold text-slate-800">{{ stats.skuCount }}</p></div>
      <div class="card p-3 text-center"><p class="text-xs text-slate-400">총 재고</p><p class="text-xl font-bold text-brand-600">{{ stats.totalQty.toLocaleString() }}</p></div>
      <div class="card p-3 text-center"><p class="text-xs text-slate-400">부족</p><p class="text-xl font-bold text-amber-500">{{ stats.low }}</p></div>
      <div class="card p-3 text-center"><p class="text-xs text-slate-400">품절</p><p class="text-xl font-bold text-rose-500">{{ stats.out }}</p></div>
    </div>

    <!-- 필터 -->
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <select v-model="fComplex" class="input w-auto"><option value="">전체 단지</option><option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option></select>
      <select v-model="fCategory" class="input w-auto"><option value="">전체 카테고리</option><option v-for="c in categoryOptions" :key="c.id" :value="c.id">{{ c.name }}</option></select>
      <select v-model="fProductCode" class="input w-auto"><option value="">전체 제품코드</option><option v-for="p in productCodeOptions" :key="p.id" :value="p.id">{{ p.name }}</option></select>
      <select v-model="fProductDetail" class="input w-auto"><option value="">전체 상세코드</option><option v-for="d in productDetailOptions" :key="d.id" :value="d.id">{{ d.name }}</option></select>
      <select v-model="fStatus" class="input w-auto"><option v-for="s in STATUS" :key="s.v" :value="s.v">{{ s.t }}</option></select>
      <select v-model="sort" class="input w-auto"><option v-for="s in SORTS" :key="s.v" :value="s.v">{{ s.t }}</option></select>
      <input v-model="search" class="input w-full sm:w-64" placeholder="SKU코드/상품명 검색" />
      <button class="btn-ghost btn-sm" :class="showTotal ? 'bg-brand-50 text-brand-700 ring-brand-300' : ''" @click="showTotal = !showTotal">합계 보기</button>
      <button class="btn-ghost btn-sm" @click="resetFilters">초기화</button>
      <select v-model="pageSize" class="input w-auto sm:ml-auto">
        <option v-for="n in sizes" :key="n" :value="n">{{ n }}개씩</option>
      </select>
    </div>

    <div class="card">
      <div class="overflow-x-auto scrollbar-slim">
      <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
      <div v-else-if="!filtered.length" class="p-10 text-center text-sm text-slate-400">조건에 맞는 재고가 없습니다.</div>
      <table v-else class="w-full text-sm">
        <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th class="px-3 py-2.5 font-semibold">SKU / 상품</th>
            <th class="hidden px-3 py-2.5 font-semibold md:table-cell">경로</th>
            <th class="hidden px-3 py-2.5 font-semibold sm:table-cell">위치</th>
            <th class="px-3 py-2.5 text-right font-semibold">재고</th>
            <th class="hidden px-3 py-2.5 text-right font-semibold sm:table-cell">안전</th>
            <th class="px-3 py-2.5 font-semibold">상태</th>
            <th class="hidden px-3 py-2.5 text-right font-semibold lg:table-cell">입고/출고</th>
          </tr>
        </thead>
        <!-- 단지별 묶기 -->
        <template v-if="groupByComplex">
          <tbody v-for="(rows, cx) in grouped" :key="cx" class="divide-y divide-slate-50">
            <tr class="bg-slate-50/80"><td colspan="7" class="px-3 py-1.5 text-xs font-bold text-slate-500">📦 {{ cx }} <span class="font-normal text-slate-400">({{ rows.length }} SKU)</span></td></tr>
            <tr v-for="s in rows" :key="s.id" class="hover:bg-slate-50/60">
              <td class="px-3 py-2.5"><div class="flex items-center gap-2.5"><img :src="resolveImage(s)" class="h-9 w-9 shrink-0 rounded border border-slate-100 object-cover" alt="" /><div><span class="badge bg-brand-50 font-mono text-brand-700">{{ s.code }}</span><p class="mt-0.5 text-slate-700">{{ s.productName }} <span class="text-xs text-slate-400">{{ s.spec }}</span></p></div></div></td>
              <td class="hidden px-3 py-2.5 text-xs text-slate-400 md:table-cell">{{ s.pathLabel }}</td>
              <td class="hidden px-3 py-2.5 text-xs sm:table-cell"><button class="inline-flex items-center gap-1 rounded px-1.5 py-1 text-left ring-1 ring-inset ring-slate-200 hover:bg-brand-50 hover:ring-brand-300" title="보관위치 이력 보기" @click="openLocation(s)"><span v-if="s.locationLabel" class="text-slate-600">📍 {{ s.locationLabel }}</span><span v-else class="text-slate-300">위치 미지정</span><span v-if="s.locationVerifiedAt" class="text-emerald-600" title="실사 검증됨">✓</span><svg class="h-3 w-3 text-slate-300" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M9 6l6 6-6 6" stroke-linecap="round" stroke-linejoin="round"/></svg></button></td>
              <td class="px-3 py-2.5 text-right font-bold" :class="s.qty <= 0 ? 'text-rose-500' : 'text-slate-800'">{{ s.qty }}</td>
              <td class="hidden px-3 py-2.5 text-right text-slate-400 sm:table-cell">{{ s.safetyStock || '—' }}</td>
              <td class="px-3 py-2.5"><span class="badge" :class="statusMeta[s.status]?.c">{{ statusMeta[s.status]?.t }}</span><span v-if="lifeBadge(s)" class="badge ml-1" :class="lifeBadge(s).c">{{ lifeBadge(s).t }}</span></td>
              <td class="hidden px-3 py-2.5 text-right text-xs text-slate-400 lg:table-cell">+{{ s.totalIn || 0 }} / -{{ s.totalOut || 0 }}</td>
            </tr>
          </tbody>
        </template>
        <!-- 평면 -->
        <tbody v-else class="divide-y divide-slate-50">
          <tr v-for="s in paged" :key="s.id" class="hover:bg-slate-50/60">
            <td class="px-3 py-2.5"><div class="flex items-center gap-2.5"><img :src="resolveImage(s)" class="h-9 w-9 shrink-0 rounded border border-slate-100 object-cover" alt="" /><div><span class="badge bg-brand-50 font-mono text-brand-700">{{ s.code }}</span><p class="mt-0.5 text-slate-700">{{ s.productName }} <span class="text-xs text-slate-400">{{ s.spec }}</span></p></div></div></td>
            <td class="hidden px-3 py-2.5 text-xs text-slate-400 md:table-cell">{{ s.pathLabel }}</td>
              <td class="hidden px-3 py-2.5 text-xs sm:table-cell"><button class="inline-flex items-center gap-1 rounded px-1.5 py-1 text-left ring-1 ring-inset ring-slate-200 hover:bg-brand-50 hover:ring-brand-300" title="보관위치 이력 보기" @click="openLocation(s)"><span v-if="s.locationLabel" class="text-slate-600">📍 {{ s.locationLabel }}</span><span v-else class="text-slate-300">위치 미지정</span><span v-if="s.locationVerifiedAt" class="text-emerald-600" title="실사 검증됨">✓</span><svg class="h-3 w-3 text-slate-300" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M9 6l6 6-6 6" stroke-linecap="round" stroke-linejoin="round"/></svg></button></td>
            <td class="px-3 py-2.5 text-right font-bold" :class="s.qty <= 0 ? 'text-rose-500' : 'text-slate-800'">{{ s.qty }}</td>
            <td class="hidden px-3 py-2.5 text-right text-slate-400 sm:table-cell">{{ s.safetyStock || '—' }}</td>
            <td class="px-3 py-2.5"><span class="badge" :class="statusMeta[s.status]?.c">{{ statusMeta[s.status]?.t }}</span><span v-if="lifeBadge(s)" class="badge ml-1" :class="lifeBadge(s).c">{{ lifeBadge(s).t }}</span></td>
            <td class="hidden px-3 py-2.5 text-right text-xs text-slate-400 lg:table-cell">+{{ s.totalIn || 0 }} / -{{ s.totalOut || 0 }}</td>
          </tr>
        </tbody>
        <tfoot v-if="showTotal" class="border-t-2 border-slate-200 bg-slate-50 text-sm font-bold">
          <tr>
            <td class="px-3 py-3 text-slate-600">합계 · {{ filtered.length }} SKU</td>
            <td class="hidden md:table-cell"></td>
            <td class="hidden sm:table-cell"></td>
            <td class="px-3 py-3 text-right text-brand-700">{{ stats.totalQty.toLocaleString() }}개</td>
            <td class="hidden sm:table-cell"></td>
            <td></td>
            <td class="hidden lg:table-cell"></td>
          </tr>
        </tfoot>
      </table>
      </div>
      <Pager v-if="filtered.length && !groupByComplex" v-model:page="page" :total="total" :total-pages="totalPages" class="border-t border-slate-100" />
    </div>

    <!-- 보관위치 + 변경 이력 -->
    <BaseModal v-model="locModal" title="보관위치 / 변경 이력" size="md">
      <div v-if="locSku">
        <div class="mb-3 rounded-lg bg-slate-50 p-3">
          <p class="text-sm"><span class="font-mono text-brand-700">{{ locSku.code }}</span> <span class="text-slate-700">{{ locSku.productName }}</span></p>
          <p class="mt-1 text-sm font-medium text-slate-800">
            📍 현재: {{ locSku.locationLabel ? (locSku.complexName + ' › ' + locSku.locationLabel) : (locSku.complexName || '위치 미지정') }}
            <span v-if="locSku.storageLocationCode" class="font-mono text-xs text-slate-400">({{ locSku.storageLocationCode }})</span>
          </p>
          <p v-if="locSku.locationVerifiedAt" class="mt-0.5 text-xs text-emerald-600">실사 검증: {{ locSku.locationVerifiedBy }} · {{ fmtDateTime(locSku.locationVerifiedAt) }}</p>
        </div>

        <p class="mb-1 text-xs font-semibold text-slate-500">변경 이력</p>
        <div v-if="locLoading" class="py-6 text-center text-sm text-slate-400">불러오는 중…</div>
        <div v-else-if="!locLogs.length" class="py-6 text-center text-sm text-slate-300">변경 이력이 없습니다.</div>
        <ul v-else class="divide-y divide-slate-50 text-sm">
          <li v-for="l in locLogs" :key="l.id" class="py-2">
            <div class="flex items-center justify-between">
              <span class="font-medium text-slate-700">
                {{ l.fromLabel || '미지정' }} <span class="text-slate-300">→</span> {{ l.toLabel || '미지정' }}
                <span v-if="l.storageLocationCode" class="font-mono text-xs text-slate-400">({{ l.storageLocationCode }})</span>
              </span>
              <span class="text-xs text-slate-400">{{ fmtDateTime(l.at) }}</span>
            </div>
            <p class="text-xs text-slate-400">변경자: {{ l.byName }}</p>
          </li>
        </ul>
      </div>
      <template #footer>
        <button class="btn-primary" @click="locModal = false">닫기</button>
      </template>
    </BaseModal>
  </div>
</template>

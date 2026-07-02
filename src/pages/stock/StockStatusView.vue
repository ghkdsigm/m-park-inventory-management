<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { skus, complexes, categories, productCodes, productDetails, listLocationLogs, listMovements } from '@/services/db'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import BaseModal from '@/components/ui/BaseModal.vue'
import Pager from '@/components/ui/Pager.vue'
import { resolveImage } from '@/utils/image'
import { lifecycleStatus, fmtDateTime } from '@/utils/date'
import { specText } from '@/utils/sku'

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
const usageLogs = ref([]) // 사용처 이력 = 출고 내역
const locLoading = ref(false)
async function openLocation(s) {
  locSku.value = s
  locLogs.value = []
  usageLogs.value = []
  locModal.value = true
  locLoading.value = true
  try {
    const [ll, mv] = await Promise.all([listLocationLogs(s.skuId, 50), listMovements(s.skuId, 100)])
    locLogs.value = ll
    usageLogs.value = (mv || []).filter((m) => m.type === 'out')
  } catch (e) {
    /* 무시 */
  } finally {
    locLoading.value = false
  }
}

const toast = useToast()
const loading = ref(true)

// 마스터(셀렉트 옵션용) — 1회 로드
const complexList = ref([])
const categoryList = ref([])
const productCodeList = ref([])
const productDetailList = ref([])

// 서버 페이지 결과
const rows = ref([])
const groups = ref([])
const total = ref(0)
const totalQty = ref(0)
const totalValue = ref(0)
const lowCount = ref(0)
const outCount = ref(0)

const search = ref('')
const fComplex = ref('')
const fCategory = ref('')
const fProductCode = ref('')
const fProductDetail = ref('')
const fStatus = ref('')
const fAudit = ref('')
const sort = ref('recent')
const groupByComplex = ref(false)
const showTotal = ref(false)

// 페이징(서버측)
const sizes = [10, 30, 50]
const page = ref(1)
const pageSize = ref(10)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

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
const AUDIT_STATUS = [
  { v: '', t: '전체 실사상태' },
  { v: 'unaudited', t: '미확정' },
  { v: 'ok', t: '정상' },
  { v: 'mismatch', t: '오차' },
]
function auditMeta(s) {
  if (!s.lastAuditedAt) return { t: '미확정', c: 'bg-slate-100 text-slate-500' }
  if (s.auditStatus === 'mismatch') return { t: '오차', c: 'bg-rose-50 text-rose-600' }
  return { t: '정상', c: 'bg-emerald-50 text-emerald-700' }
}

function curFilters() {
  return {
    complexId: fComplex.value,
    categoryId: fCategory.value,
    productCodeId: fProductCode.value,
    productDetailId: fProductDetail.value,
    status: fStatus.value,
    auditStatus: fAudit.value,
    search: search.value.trim(),
  }
}

async function fetchPage() {
  loading.value = true
  try {
    if (groupByComplex.value) {
      groups.value = await skus.groupByComplex(curFilters())
    } else {
      const r = await skus.page({ ...curFilters(), sort: sort.value, page: page.value, pageSize: pageSize.value })
      rows.value = r.rows
      total.value = r.total
      totalQty.value = r.totalQty
      totalValue.value = r.totalValue
      lowCount.value = r.lowCount
      outCount.value = r.outCount
    }
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
    /* 셀렉트 옵션 로드 실패는 치명적 아님 */
  }
}

onMounted(async () => {
  await loadMasters()
  await fetchPage()
})

// 셀렉트 옵션(연쇄)
const categoryOptions = computed(() => categoryList.value)
const productCodeOptions = computed(() => (fCategory.value ? productCodeList.value.filter((p) => p.categoryId === fCategory.value) : productCodeList.value))
const productDetailOptions = computed(() => (fProductCode.value ? productDetailList.value.filter((d) => d.productCodeId === fProductCode.value) : productDetailList.value))
watch(fComplex, () => { fCategory.value = ''; fProductCode.value = ''; fProductDetail.value = '' })
watch(fCategory, () => { fProductCode.value = ''; fProductDetail.value = '' })
watch(fProductCode, () => { fProductDetail.value = '' })

// 필터/정렬/묶기 변경 → 1페이지부터 다시 조회
watch([fComplex, fCategory, fProductCode, fProductDetail, fStatus, fAudit, sort, groupByComplex], () => {
  page.value = 1
  fetchPage()
})
// 페이지 크기 변경 → 1페이지부터
watch(pageSize, () => { page.value = 1; fetchPage() })
// 페이지 이동 → 해당 페이지 조회
watch(page, fetchPage)
// 검색 입력 → 디바운스 후 1페이지부터
let searchTimer = null
watch(search, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => { page.value = 1; fetchPage() }, 350)
})

const stats = computed(() =>
  groupByComplex.value
    ? groups.value.reduce(
        (a, g) => ({
          skuCount: a.skuCount + Number(g.skuCount || 0),
          totalQty: a.totalQty + Number(g.totalQty || 0),
          low: a.low + Number(g.lowCount || 0),
          out: a.out + Number(g.outCount || 0),
        }),
        { skuCount: 0, totalQty: 0, low: 0, out: 0 }
      )
    : { skuCount: total.value, totalQty: totalQty.value, low: lowCount.value, out: outCount.value }
)

const statusMeta = {
  in_stock: { t: '정상', c: 'bg-emerald-50 text-emerald-700' },
  low: { t: '부족', c: 'bg-amber-50 text-amber-700' },
  out: { t: '품절', c: 'bg-rose-50 text-rose-600' },
}

function resetFilters() {
  search.value = ''; fComplex.value = ''; fCategory.value = ''; fProductCode.value = ''; fProductDetail.value = ''; fStatus.value = ''; fAudit.value = ''; sort.value = 'recent'
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
      <div class="card p-3 text-center"><p class="text-xs text-slate-400">SKU 수</p><p class="text-xl font-bold text-slate-800">{{ stats.skuCount.toLocaleString() }}</p></div>
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
      <select v-model="fAudit" class="input w-auto"><option v-for="a in AUDIT_STATUS" :key="a.v" :value="a.v">{{ a.t }}</option></select>
      <select v-if="!groupByComplex" v-model="sort" class="input w-auto"><option v-for="s in SORTS" :key="s.v" :value="s.v">{{ s.t }}</option></select>
      <input v-model="search" class="input w-full sm:w-64" placeholder="SKU코드/상품명 검색" />
      <button v-if="!groupByComplex" class="btn-ghost btn-sm" :class="showTotal ? 'bg-brand-50 text-brand-700 ring-brand-300' : ''" @click="showTotal = !showTotal">합계 보기</button>
      <button class="btn-ghost btn-sm" @click="resetFilters">초기화</button>
      <select v-if="!groupByComplex" v-model="pageSize" class="input w-auto sm:ml-auto">
        <option v-for="n in sizes" :key="n" :value="n">{{ n }}개씩</option>
      </select>
    </div>

    <!-- 단지별 묶기: 요약 -->
    <div v-if="groupByComplex" class="card">
      <div class="overflow-x-auto scrollbar-slim">
        <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
        <div v-else-if="!groups.length" class="p-10 text-center text-sm text-slate-400">조건에 맞는 재고가 없습니다.</div>
        <table v-else class="w-full text-sm">
          <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
            <tr>
              <th class="px-4 py-2.5 font-semibold">단지</th>
              <th class="px-4 py-2.5 text-right font-semibold">SKU 수</th>
              <th class="px-4 py-2.5 text-right font-semibold">총 재고</th>
              <th class="px-4 py-2.5 text-right font-semibold">부족</th>
              <th class="px-4 py-2.5 text-right font-semibold">품절</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-50">
            <tr v-for="g in groups" :key="g.complexName" class="hover:bg-slate-50/60">
              <td class="px-4 py-2.5 font-medium text-slate-700">📦 {{ g.complexName }}</td>
              <td class="px-4 py-2.5 text-right text-slate-600">{{ Number(g.skuCount).toLocaleString() }}</td>
              <td class="px-4 py-2.5 text-right font-bold text-brand-700">{{ Number(g.totalQty).toLocaleString() }}</td>
              <td class="px-4 py-2.5 text-right" :class="Number(g.lowCount) ? 'text-amber-600' : 'text-slate-300'">{{ g.lowCount }}</td>
              <td class="px-4 py-2.5 text-right" :class="Number(g.outCount) ? 'text-rose-500' : 'text-slate-300'">{{ g.outCount }}</td>
            </tr>
          </tbody>
          <tfoot class="border-t-2 border-slate-200 bg-slate-50 text-sm font-bold">
            <tr>
              <td class="px-4 py-3 text-slate-600">합계</td>
              <td class="px-4 py-3 text-right text-slate-700">{{ stats.skuCount.toLocaleString() }}</td>
              <td class="px-4 py-3 text-right text-brand-700">{{ stats.totalQty.toLocaleString() }}</td>
              <td class="px-4 py-3 text-right text-amber-600">{{ stats.low }}</td>
              <td class="px-4 py-3 text-right text-rose-500">{{ stats.out }}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </div>

    <!-- 평면 목록 -->
    <div v-else class="card">
      <div class="overflow-x-auto scrollbar-slim">
        <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
        <div v-else-if="!rows.length" class="p-10 text-center text-sm text-slate-400">조건에 맞는 재고가 없습니다.</div>
        <table v-else class="w-full text-sm">
          <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
            <tr>
              <th class="px-3 py-2.5 font-semibold">SKU / 상품</th>
              <th class="hidden px-3 py-2.5 font-semibold md:table-cell">경로</th>
              <th class="hidden px-3 py-2.5 font-semibold sm:table-cell">보관위치</th>
              <th class="px-3 py-2.5 text-right font-semibold">재고</th>
              <th class="hidden px-3 py-2.5 text-right font-semibold md:table-cell">단가</th>
              <th class="hidden px-3 py-2.5 text-right font-semibold md:table-cell">총가격</th>
              <th class="hidden px-3 py-2.5 text-right font-semibold sm:table-cell">안전</th>
              <th class="px-3 py-2.5 font-semibold">재고상태</th>
              <th class="px-3 py-2.5 font-semibold">실사상태</th>
              <th class="hidden px-3 py-2.5 text-right font-semibold lg:table-cell">입고/출고</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-50">
            <tr v-for="s in rows" :key="s.stockId" class="hover:bg-slate-50/60">
              <td class="px-3 py-2.5"><div class="flex items-center gap-2.5"><img :src="resolveImage(s)" class="h-9 w-9 shrink-0 rounded border border-slate-100 object-cover" alt="" /><div><span class="badge bg-brand-50 font-mono text-brand-700">{{ s.code }}</span><p class="mt-0.5 text-slate-700">{{ s.productName }} <span class="text-xs text-slate-400">{{ specText(s) }}</span></p></div></div></td>
              <td class="hidden px-3 py-2.5 text-xs text-slate-400 md:table-cell">{{ s.pathLabel }}</td>
              <td class="hidden px-3 py-2.5 text-xs sm:table-cell"><button class="inline-flex items-center gap-1 rounded px-1.5 py-1 text-left ring-1 ring-inset ring-slate-200 hover:bg-brand-50 hover:ring-brand-300" title="보관위치 이력 보기" @click="openLocation(s)"><span v-if="s.locationLabel" class="text-slate-600">📍 {{ s.locationLabel }}</span><span v-else class="text-slate-300">위치 미지정</span><span v-if="s.locationVerifiedAt" class="text-emerald-600" title="실사 검증됨">✓</span><svg class="h-3 w-3 text-slate-300" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M9 6l6 6-6 6" stroke-linecap="round" stroke-linejoin="round"/></svg></button></td>
              <td class="px-3 py-2.5 text-right font-bold" :class="s.qty <= 0 ? 'text-rose-500' : 'text-slate-800'">{{ s.qty }}</td>
              <td class="hidden px-3 py-2.5 text-right text-slate-500 md:table-cell">{{ Number(s.price || 0).toLocaleString() }}원</td>
              <td class="hidden px-3 py-2.5 text-right font-medium text-slate-700 md:table-cell">{{ (Number(s.price || 0) * Number(s.qty || 0)).toLocaleString() }}원</td>
              <td class="hidden px-3 py-2.5 text-right text-slate-400 sm:table-cell">{{ s.safetyStock || '—' }}</td>
              <td class="px-3 py-2.5"><span class="badge" :class="statusMeta[s.status]?.c">{{ statusMeta[s.status]?.t }}</span><span v-if="lifeBadge(s)" class="badge ml-1" :class="lifeBadge(s).c">{{ lifeBadge(s).t }}</span></td>
              <td class="px-3 py-2.5">
                <button class="inline-flex flex-col items-start gap-0.5 rounded px-1.5 py-1 text-left hover:bg-brand-50" title="실사 상세 보기" @click="openLocation(s)">
                  <span class="badge" :class="auditMeta(s).c">{{ auditMeta(s).t }}</span>
                  <span v-if="s.lastAuditDiff" class="text-[10px]" :class="s.lastAuditDiff < 0 ? 'text-rose-500' : 'text-emerald-600'">{{ s.lastAuditDiff > 0 ? '+' : '' }}{{ s.lastAuditDiff }}</span>
                </button>
              </td>
              <td class="hidden px-3 py-2.5 text-right text-xs text-slate-400 lg:table-cell">+{{ s.totalIn || 0 }} / -{{ s.totalOut || 0 }}</td>
            </tr>
          </tbody>
          <tfoot v-if="showTotal" class="border-t-2 border-slate-200 bg-slate-50 text-sm font-bold">
            <tr>
              <td class="px-3 py-3 text-slate-600">합계 · {{ stats.skuCount.toLocaleString() }} SKU</td>
              <td class="hidden md:table-cell"></td>
              <td class="hidden sm:table-cell"></td>
              <td class="px-3 py-3 text-right text-brand-700">{{ stats.totalQty.toLocaleString() }}개</td>
              <td class="hidden md:table-cell"></td>
              <td class="hidden px-3 py-3 text-right text-brand-700 md:table-cell">{{ Number(totalValue).toLocaleString() }}원</td>
              <td class="hidden sm:table-cell"></td>
              <td></td>
              <td></td>
              <td class="hidden lg:table-cell"></td>
            </tr>
          </tfoot>
        </table>
      </div>
      <Pager v-if="total" v-model:page="page" :total="total" :total-pages="totalPages" class="border-t border-slate-100" />
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
          <p v-if="locSku.locationVerifiedAt" class="mt-0.5 text-xs text-emerald-600">위치 검증: {{ locSku.locationVerifiedBy }} · {{ fmtDateTime(locSku.locationVerifiedAt) }}</p>
        </div>

        <!-- 실사 상태 -->
        <div class="mb-3 rounded-lg border border-slate-200 p-3 text-sm">
          <div class="mb-1 flex items-center gap-2">
            <span class="text-xs font-semibold text-slate-500">실사상태</span>
            <span class="badge" :class="auditMeta(locSku).c">{{ auditMeta(locSku).t }}</span>
          </div>
          <template v-if="locSku.lastAuditedAt">
            <p class="text-slate-600">시스템 {{ (locSku.lastAuditCounted ?? 0) - (locSku.lastAuditDiff ?? 0) }} <span class="text-slate-300">→</span> 실사 <b class="text-slate-800">{{ locSku.lastAuditCounted }}</b>
              <span class="ml-1 font-bold" :class="(locSku.lastAuditDiff || 0) < 0 ? 'text-rose-500' : (locSku.lastAuditDiff || 0) > 0 ? 'text-emerald-600' : 'text-slate-400'">(차이 {{ (locSku.lastAuditDiff || 0) > 0 ? '+' : '' }}{{ locSku.lastAuditDiff || 0 }})</span>
            </p>
            <p class="mt-0.5 text-xs text-slate-400">실사자: {{ locSku.lastAuditedBy || '-' }} · {{ fmtDateTime(locSku.lastAuditedAt) }}</p>
            <p v-if="locSku.auditStatus === 'ok' && locSku.auditResolvedAt" class="mt-0.5 text-xs text-emerald-600">✓ 정상처리: {{ locSku.auditResolvedBy }}<span v-if="locSku.auditResolveReason"> · {{ locSku.auditResolveReason }}</span></p>
            <p class="mt-1 text-[11px] text-slate-400">오차 정상처리는 <b>재고실사</b> 화면에서 할 수 있습니다.</p>
          </template>
          <p v-else class="text-xs text-slate-400">아직 실사하지 않은 재고입니다.</p>
        </div>

        <p class="mb-1 text-xs font-semibold text-slate-500">위치 변경 이력</p>
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

        <p class="mb-1 mt-4 text-xs font-semibold text-slate-500">사용처 이력 <span class="font-normal text-slate-400">(출고)</span></p>
        <div v-if="locLoading" class="py-6 text-center text-sm text-slate-400">불러오는 중…</div>
        <div v-else-if="!usageLogs.length" class="py-6 text-center text-sm text-slate-300">사용처 이력이 없습니다.</div>
        <ul v-else class="divide-y divide-slate-50 text-sm">
          <li v-for="m in usageLogs" :key="m.id" class="py-2">
            <div class="flex items-center justify-between">
              <span class="font-medium text-slate-700">
                📦 {{ m.usagePlace || '사용처 미지정' }}
                <span class="ml-1 font-bold text-rose-500">-{{ m.qty }}개</span>
                <span v-if="m.voided" class="badge ml-1 bg-slate-100 text-[10px] text-slate-400 line-through">취소됨</span>
              </span>
              <span class="text-xs text-slate-400">{{ fmtDateTime(m.at) }}</span>
            </div>
            <p class="text-xs text-slate-400">
              <span v-if="m.reason">{{ m.reason }} · </span>
              <span v-if="m.requestDept">{{ m.requestDept }} · </span>
              <span v-if="m.requester">요청 {{ m.requester }} · </span>
              담당 {{ m.handler || m.byName }}
            </p>
          </li>
        </ul>
      </div>
      <template #footer>
        <button class="btn-primary" @click="locModal = false">닫기</button>
      </template>
    </BaseModal>
  </div>
</template>

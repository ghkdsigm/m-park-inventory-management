<script setup>
import { ref, onMounted, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { skus, products, complexes, recentMovements, getDailyStats, getDailyStatsRange, movementsByDate, auditTopUsers, topProductsBySku, topChangedSkus } from '@/services/db'
import { useAuthStore } from '@/stores/auth'
import { lifecycleStatus, daysUntil, fmtDate, fmtDateTime } from '@/utils/date'
import PageHeader from '@/components/ui/PageHeader.vue'
import BaseModal from '@/components/ui/BaseModal.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import { resolveImage } from '@/utils/image'
import { specText } from '@/utils/sku'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(true)
const stat = ref({ complexes: 0, products: 0, skus: 0, totalQty: 0, low: 0, out: 0 })
const lowList = ref([])
const moves = ref([])
const lifeStat = ref({ soon: 0, over: 0 })
const lifeList = ref([])
const topUsers = ref([])
const topProds = ref([])
const topSkus = ref([])
const byComplex = ref([])
const daily = ref([])
const stockStat = ref({ total: 0, normal: 0, low: 0, out: 0 })

/* ===== 단지 탭 필터 ===== */
const complexList = ref([])
const selectedComplex = ref('') // '' = 전체
const summaryLowList = ref([]) // 전체 기준 부족/품절(대시보드 요약)
const complexTabs = computed(() => [{ id: '', name: '전체' }, ...complexList.value.map((c) => ({ id: c.id, name: c.name }))])

/* ===== 입출고 달력 조회 ===== */
const _now = new Date()
const calYear = ref(_now.getFullYear())
const calMonth = ref(_now.getMonth()) // 0-11
const pad2 = (n) => String(n).padStart(2, '0')
const ymd = (y, m, d) => `${y}-${pad2(m + 1)}-${pad2(d)}` // m: 0-based
const todayStr = ymd(_now.getFullYear(), _now.getMonth(), _now.getDate())
const selectedDate = ref(todayStr)
const monthStats = ref({}) // 'YYYY-MM-DD' -> stats
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']
const yearOptions = computed(() => {
  const y = _now.getFullYear()
  return [y - 4, y - 3, y - 2, y - 1, y, y + 1]
})
const calendarCells = computed(() => {
  const startDow = new Date(calYear.value, calMonth.value, 1).getDay()
  const days = new Date(calYear.value, calMonth.value + 1, 0).getDate()
  const cells = []
  for (let i = 0; i < startDow; i++) cells.push(null)
  for (let d = 1; d <= days; d++) cells.push(d)
  return cells
})
async function loadMonth() {
  const last = new Date(calYear.value, calMonth.value + 1, 0).getDate()
  try {
    const rows = await getDailyStatsRange(ymd(calYear.value, calMonth.value, 1), ymd(calYear.value, calMonth.value, last))
    const map = {}
    rows.forEach((r) => { map[r.date] = r })
    monthStats.value = map
  } catch (e) { monthStats.value = {} }
}
function shiftMonth(delta) {
  let m = calMonth.value + delta, y = calYear.value
  if (m < 0) { m = 11; y-- } else if (m > 11) { m = 0; y++ }
  calMonth.value = m; calYear.value = y
  loadMonth()
}
function onYm() { loadMonth() }
function pickDay(d) { if (d) selectedDate.value = ymd(calYear.value, calMonth.value, d) }
function cellStats(d) { return d ? monthStats.value[ymd(calYear.value, calMonth.value, d)] : null }
function dayActive(d) { const s = cellStats(d); return s && ((s.inCount || 0) + (s.outCount || 0)) > 0 }
function isSelected(d) { return d && ymd(calYear.value, calMonth.value, d) === selectedDate.value }
function isToday(d) { return d && ymd(calYear.value, calMonth.value, d) === todayStr }
// 선택일 집계는 그날 실제 movements 에서 직접 계산 (daily-stats 배포 여부와 무관하게 정확)
const selectedStats = computed(() => {
  const rows = dayMoves.value.filter((m) => !m.voided)
  const ins = rows.filter((m) => m.type === 'in')
  const outs = rows.filter((m) => m.type === 'out')
  const sum = (arr) => arr.reduce((a, m) => a + (m.qty || 0), 0)
  return { inCount: ins.length, outCount: outs.length, inQty: sum(ins), outQty: sum(outs) }
})
const selectedLabel = computed(() => {
  const [y, m, d] = selectedDate.value.split('-').map(Number)
  const w = WEEKDAYS[new Date(y, m - 1, d).getDay()]
  return `${y}년 ${m}월 ${d}일 (${w})`
})

// 선택일 입고/출고 건별 목록
const dayMoves = ref([])
const dayMovesLoading = ref(false)
async function loadDayMoves() {
  dayMovesLoading.value = true
  try {
    const rows = await movementsByDate(selectedDate.value, 300)
    const cid = selectedComplex.value
    // 백엔드 date 필터 미지원 대비 선택일로 한 번 더 거르고, 단지 탭이면 그 단지만
    dayMoves.value = rows.filter((m) => (m.type === 'in' || m.type === 'out') && fmtDate(m.at) === selectedDate.value && (!cid || m.complexId === cid))
  } catch (e) { dayMoves.value = [] } finally { dayMovesLoading.value = false }
}
watch(selectedDate, loadDayMoves)

const typeLabel = { in: '입고', out: '출고', adjust: '조정', audit: '실사' }
const typeColor = { in: 'bg-emerald-500', out: 'bg-sky-500', adjust: 'bg-amber-500', audit: 'bg-violet-500' }

// 선택 단지에 따라 재고집계/부족·품절/최근이동/선택일 목록 갱신
async function loadComplexData() {
  const cid = selectedComplex.value
  try {
    const pg = await skus.page({ complexId: cid, pageSize: 1 })
    stockStat.value = { total: pg.total, normal: Math.max(0, pg.total - pg.lowCount - pg.outCount), low: pg.lowCount, out: pg.outCount }
    stat.value = { ...stat.value, totalQty: pg.totalQty, low: pg.lowCount, out: pg.outCount }
    if (cid) {
      const r = await skus.page({ complexId: cid, sort: 'qtyAsc', pageSize: 30 })
      lowList.value = r.rows.filter((s) => s.status === 'low' || s.status === 'out').slice(0, 6)
    } else {
      lowList.value = summaryLowList.value
    }
    const mv = await recentMovements(cid ? 60 : 8)
    moves.value = (cid ? mv.filter((m) => m.complexId === cid) : mv).slice(0, 8)
  } catch (e) { /* 무시 */ }
  await loadDayMoves()
}
watch(selectedComplex, loadComplexData)

onMounted(async () => {
  try {
    const [sum, cx] = await Promise.all([skus.dashboardSummary(), complexes.list()])
    complexList.value = cx || []
    stat.value = {
      complexes: sum.complexCount,
      products: sum.productCount,
      skus: sum.skuCount,
      totalQty: sum.totalQty,
      low: sum.lowCount,
      out: sum.outCount,
    }
    summaryLowList.value = sum.lowList
    lifeStat.value = { soon: sum.lifeSoon, over: sum.lifeOver }
    lifeList.value = sum.lifeList.map((s) => ({ ...s, _st: lifecycleStatus(s.nextReplaceAt), _d: daysUntil(s.nextReplaceAt) }))

    // 차트 데이터 (단지별 / 일별 추이) — 전체 기준
    try {
      const [gc, ds] = await Promise.all([skus.groupByComplex({}), getDailyStats(7)])
      byComplex.value = gc
      daily.value = ds || []
    } catch (e) { /* 차트 데이터 실패는 치명적 아님 */ }

    await loadMonth()
    await loadComplexData()

    // 관리자 통계 (admin 전용)
    if (auth.isAdmin) {
      const [tu, tp, ts2] = await Promise.all([auditTopUsers(20), topProductsBySku(20), topChangedSkus(10)])
      topUsers.value = tu
      topProds.value = tp
      topSkus.value = ts2
    }
  } finally {
    loading.value = false
  }
})

const cards = computed(() => [
  { label: '단지', value: stat.value.complexes, icon: '🏢', to: auth.isAdmin ? { name: 'complexes' } : null },
  { label: '상품', value: stat.value.products, icon: '📦', to: auth.isAdmin ? { name: 'products' } : null },
  { label: 'SKU', value: stat.value.skus, icon: '🔖', to: auth.isAdmin ? { name: 'skus' } : null },
  { label: '총 재고', value: stat.value.totalQty.toLocaleString(), icon: '📊', to: { name: 'status' } },
  { label: '재고부족', value: stat.value.low, icon: '⚠️', to: { name: 'status' }, warn: stat.value.low > 0 },
  { label: '품절', value: stat.value.out, icon: '⛔', to: { name: 'status' }, warn: stat.value.out > 0 },
])

const COLORS = ['#4f46e5', '#0ea5e9', '#10b981', '#f59e0b', '#f43f5e', '#8b5cf6']
const statusDonut = computed(() => {
  const s = stockStat.value
  const total = s.normal + s.low + s.out || 1
  const raw = [
    { label: '정상', value: s.normal, color: '#10b981' },
    { label: '부족', value: s.low, color: '#f59e0b' },
    { label: '품절', value: s.out, color: '#f43f5e' },
  ]
  let cum = 0
  return raw.map((x) => {
    const pct = (x.value / total) * 100
    const seg = { ...x, pct, dashoffset: -cum }
    cum += pct
    return seg
  })
})
const complexMax = computed(() => Math.max(1, ...byComplex.value.map((c) => c.totalQty)))
const dailyChart = computed(() => {
  const rows = daily.value.slice().sort((a, b) => (a.date > b.date ? 1 : -1))
  const max = Math.max(1, ...rows.flatMap((r) => [r.inQty || 0, r.outQty || 0]))
  return rows.map((r) => ({
    date: (r.date || '').slice(5),
    inQty: r.inQty || 0, outQty: r.outQty || 0,
    inH: ((r.inQty || 0) / max) * 100, outH: ((r.outQty || 0) / max) * 100,
  }))
})

const fmtTime = fmtDateTime

/* ===== 상세 팝업 (SKU / 상품 겸용) ===== */
const detailModal = ref(false)
const detailKind = ref('sku') // 'sku' | 'product'
const detail = ref(null)
const detailLoading = ref(false)
async function openSkuDetail(skuId) {
  if (!skuId) return
  detailKind.value = 'sku'; detailModal.value = true; detailLoading.value = true; detail.value = null
  try { detail.value = await skus.get(skuId) } catch (e) { detail.value = null } finally { detailLoading.value = false }
}
async function openProductDetail(productId) {
  if (!productId) return
  detailKind.value = 'product'; detailModal.value = true; detailLoading.value = true; detail.value = null
  try { detail.value = await products.get(productId) } catch (e) { detail.value = null } finally { detailLoading.value = false }
}
</script>

<template>
  <div>
    <PageHeader :title="`안녕하세요, ${auth.displayName}님`" subtitle="엠파크 WMS 재고 현황 요약" />

    <div v-if="loading" class="card p-10 text-center text-sm text-slate-400">불러오는 중…</div>
    <template v-else>
      <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <button
          v-for="c in cards"
          :key="c.label"
          class="card p-4 text-left transition hover:shadow-md"
          :class="c.to ? 'cursor-pointer' : 'cursor-default'"
          @click="c.to && router.push(c.to)"
        >
          <div class="mb-1 text-xl">{{ c.icon }}</div>
          <p class="text-2xl font-extrabold" :class="c.warn ? 'text-rose-500' : 'text-slate-800'">{{ c.value }}</p>
          <p class="text-xs text-slate-400">{{ c.label }}</p>
        </button>
      </div>

      <!-- 단지 탭 (전체 + 단지별) -->
      <div class="mt-6 flex flex-wrap items-center gap-2 border-b border-slate-100 pb-2">
        <button
          v-for="t in complexTabs"
          :key="t.id"
          class="rounded-full px-4 py-1.5 text-sm font-medium transition"
          :class="selectedComplex === t.id ? 'bg-brand-600 text-white shadow-sm' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'"
          @click="selectedComplex = t.id"
        >{{ t.name }}</button>
      </div>

      <!-- 인포그래픽 차트 -->
      <div class="mt-4 grid gap-4 lg:grid-cols-3">
        <!-- 재고 상태 도넛 -->
        <div class="card p-5">
          <h3 class="mb-3 text-sm font-bold text-slate-700">재고 상태 분포</h3>
          <div class="flex items-center gap-4">
            <div class="relative h-28 w-28 shrink-0">
              <svg viewBox="0 0 36 36" class="h-28 w-28 -rotate-90">
                <circle cx="18" cy="18" r="15.9155" fill="none" stroke="#f1f5f9" stroke-width="4" />
                <circle
                  v-for="seg in statusDonut" :key="seg.label" cx="18" cy="18" r="15.9155" fill="none"
                  :stroke="seg.color" stroke-width="4"
                  :stroke-dasharray="`${seg.pct} ${100 - seg.pct}`" :stroke-dashoffset="seg.dashoffset"
                />
              </svg>
              <div class="absolute inset-0 flex flex-col items-center justify-center">
                <span class="text-xl font-extrabold text-slate-800">{{ stockStat.total }}</span>
                <span class="text-[10px] text-slate-400">재고행</span>
              </div>
            </div>
            <ul class="flex-1 space-y-1.5 text-sm">
              <li v-for="seg in statusDonut" :key="seg.label" class="flex items-center justify-between">
                <span class="flex items-center gap-2"><span class="h-2.5 w-2.5 rounded-full" :style="{ background: seg.color }" /> {{ seg.label }}</span>
                <span class="font-semibold text-slate-700">{{ seg.value }} <span class="text-xs font-normal text-slate-400">({{ Math.round(seg.pct) }}%)</span></span>
              </li>
            </ul>
          </div>
        </div>

        <!-- 단지별 재고량 -->
        <div class="card p-5">
          <h3 class="mb-3 text-sm font-bold text-slate-700">단지별 재고량</h3>
          <div v-if="!byComplex.length" class="py-6 text-center text-sm text-slate-300">데이터 없음</div>
          <ul v-else class="space-y-2.5">
            <li v-for="(c, i) in byComplex" :key="c.complexName">
              <div class="mb-1 flex items-center justify-between text-xs">
                <span class="text-slate-600">{{ c.complexName }} <span class="text-slate-300">· {{ c.skuCount }}행</span></span>
                <span class="font-semibold text-slate-700">{{ c.totalQty.toLocaleString() }}개</span>
              </div>
              <div class="h-2.5 w-full overflow-hidden rounded-full bg-slate-100">
                <div class="h-full rounded-full" :style="{ width: (c.totalQty / complexMax * 100) + '%', background: COLORS[i % COLORS.length] }" />
              </div>
            </li>
          </ul>
        </div>

        <!-- 최근 입출고 추이 -->
        <div class="card p-5">
          <h3 class="mb-1 text-sm font-bold text-slate-700">최근 1주일 입출고 추이 <span class="text-xs font-normal text-slate-400">(수량)</span></h3>
          <div class="mb-2 flex gap-3 text-[11px] text-slate-400">
            <span class="flex items-center gap-1"><span class="h-2 w-2 rounded-sm bg-emerald-500" />입고</span>
            <span class="flex items-center gap-1"><span class="h-2 w-2 rounded-sm bg-sky-500" />출고</span>
          </div>
          <div v-if="!dailyChart.length" class="py-6 text-center text-sm text-slate-300">최근 입출고 기록이 없습니다.</div>
          <div v-else class="flex items-end gap-2">
            <div v-for="d in dailyChart" :key="d.date" class="flex flex-1 flex-col items-center gap-1">
              <div class="flex flex-col items-center text-[9px] font-semibold leading-tight">
                <span v-if="d.inQty" class="text-emerald-600">+{{ d.inQty.toLocaleString() }}</span>
                <span v-if="d.outQty" class="text-sky-600">-{{ d.outQty.toLocaleString() }}</span>
                <span v-if="!d.inQty && !d.outQty" class="text-slate-300">0</span>
              </div>
              <div class="flex h-24 w-full items-end justify-center gap-0.5">
                <div class="w-1/2 rounded-t bg-emerald-500" :style="{ height: Math.max(2, d.inH) + '%' }" :title="'입고 ' + d.inQty" />
                <div class="w-1/2 rounded-t bg-sky-500" :style="{ height: Math.max(2, d.outH) + '%' }" :title="'출고 ' + d.outQty" />
              </div>
              <span class="text-[9px] text-slate-400">{{ d.date }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 입출고 조회 (달력 + 선택일 집계) -->
      <div class="mt-4 card p-4">
        <h3 class="mb-3 text-sm font-bold text-slate-700">입출고 조회</h3>
        <div class="grid gap-4 lg:h-[26rem] lg:grid-cols-2">
          <!-- 좌: 달력 -->
          <div class="flex flex-col">
            <div class="mb-2 flex items-center justify-between">
              <button class="rounded-md px-2 py-1 text-slate-500 hover:bg-slate-100" @click="shiftMonth(-1)">‹</button>
              <div class="flex items-center gap-1">
                <AppSelect v-model="calYear" class="w-auto py-1 text-sm" @change="onYm">
                  <option v-for="y in yearOptions" :key="y" :value="y">{{ y }}년</option>
                </AppSelect>
                <AppSelect v-model="calMonth" class="w-auto py-1 text-sm" @change="onYm">
                  <option v-for="m in 12" :key="m" :value="m - 1">{{ m }}월</option>
                </AppSelect>
              </div>
              <button class="rounded-md px-2 py-1 text-slate-500 hover:bg-slate-100" @click="shiftMonth(1)">›</button>
            </div>
            <div class="grid grid-cols-7 gap-1 text-center text-[11px] text-slate-400">
              <div v-for="(w, i) in WEEKDAYS" :key="w" :class="i === 0 ? 'text-rose-400' : i === 6 ? 'text-sky-400' : ''">{{ w }}</div>
            </div>
            <div class="mt-1 grid flex-1 auto-rows-fr grid-cols-7 gap-1">
              <template v-for="(d, i) in calendarCells" :key="i">
                <div v-if="!d" />
                <button v-else
                  class="relative flex h-full min-h-[2.25rem] flex-col items-center justify-center rounded-lg text-sm hover:bg-slate-100"
                  :class="isSelected(d) ? 'bg-brand-600 text-white hover:bg-brand-600' : isToday(d) ? 'ring-1 ring-brand-300 text-slate-700' : 'text-slate-700'"
                  @click="pickDay(d)"
                >
                  {{ d }}
                  <span v-if="dayActive(d)" class="absolute bottom-1 h-1 w-1 rounded-full" :class="isSelected(d) ? 'bg-white' : 'bg-brand-500'" />
                </button>
              </template>
            </div>
          </div>

          <!-- 우: 선택일 입출고 (2줄) -->
          <div class="flex min-h-0 flex-col">
            <p class="mb-2 text-sm font-semibold text-slate-700">{{ selectedLabel }} 입출고</p>
            <div class="space-y-2">
              <div class="flex items-center gap-3 rounded-lg bg-emerald-50 px-4 py-3">
                <span class="badge bg-emerald-500 text-white">입고</span>
                <div class="flex flex-1 items-baseline justify-around">
                  <span class="text-sm text-emerald-700">건수 <b class="text-lg">{{ (selectedStats?.inCount || 0).toLocaleString() }}</b></span>
                  <span class="text-sm text-emerald-700">수량 <b class="text-lg">+{{ (selectedStats?.inQty || 0).toLocaleString() }}</b></span>
                </div>
              </div>
              <div class="flex items-center gap-3 rounded-lg bg-sky-50 px-4 py-3">
                <span class="badge bg-sky-500 text-white">출고</span>
                <div class="flex flex-1 items-baseline justify-around">
                  <span class="text-sm text-sky-700">건수 <b class="text-lg">{{ (selectedStats?.outCount || 0).toLocaleString() }}</b></span>
                  <span class="text-sm text-sky-700">수량 <b class="text-lg">-{{ (selectedStats?.outQty || 0).toLocaleString() }}</b></span>
                </div>
              </div>
            </div>

            <!-- 건별 목록 (상품명 · 담당자) -->
            <div class="mt-3 flex min-h-0 flex-1 flex-col">
              <div class="mb-1 flex items-center justify-between text-[11px] font-semibold text-slate-400">
                <span>입출고 내역 ({{ dayMoves.length }})</span>
              </div>
              <div v-if="dayMovesLoading" class="py-6 text-center text-xs text-slate-300">불러오는 중…</div>
              <div v-else-if="!dayMoves.length" class="py-6 text-center text-xs text-slate-300">이 날짜의 입출고 기록이 없습니다.</div>
              <div v-else class="min-h-0 flex-1 overflow-y-auto scrollbar-slim">
                <table class="w-full text-sm">
                  <thead class="sticky top-0 bg-white text-left text-[11px] text-slate-400">
                    <tr>
                      <th class="py-1 pr-2 font-semibold">유형</th>
                      <th class="py-1 pr-2 font-semibold">상품명</th>
                      <th class="py-1 pr-2 font-semibold">담당자</th>
                      <th class="py-1 pr-2 text-right font-semibold">수량</th>
                      <th class="py-1 text-right font-semibold">시간</th>
                    </tr>
                  </thead>
                  <tbody class="divide-y divide-slate-50">
                    <tr v-for="m in dayMoves" :key="m.id" class="cursor-pointer hover:bg-slate-50" :class="m.voided ? 'text-slate-300 line-through' : ''" @click="openSkuDetail(m.skuId)">
                      <td class="py-1.5 pr-2"><span class="badge text-[10px] text-white" :class="m.type === 'in' ? 'bg-emerald-500' : 'bg-sky-500'">{{ typeLabel[m.type] }}</span></td>
                      <td class="py-1.5 pr-2"><span class="font-mono text-[11px] text-slate-400">{{ m.skuCode }}</span> <span class="text-slate-700">{{ m.productName }}</span></td>
                      <td class="py-1.5 pr-2 text-slate-600">{{ m.handler || m.byName || '-' }}</td>
                      <td class="py-1.5 pr-2 text-right font-semibold" :class="m.type === 'in' ? 'text-emerald-600' : 'text-sky-600'">{{ m.type === 'in' ? '+' : '-' }}{{ m.qty }}</td>
                      <td class="py-1.5 text-right text-xs text-slate-400">{{ fmtTime(m.at).split(' ')[1] }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 교체 임박·초과 (연한관리) -->
      <div class="mt-4 card p-5">
        <div class="mb-3 flex items-center justify-between">
          <h3 class="text-sm font-bold text-slate-700">교체 임박 · 초과 <span class="ml-1 text-xs font-normal text-slate-400">(연한관리)</span></h3>
          <button class="text-xs text-brand-600" @click="router.push({ name: 'lifecycle' })">전체보기</button>
        </div>
        <div class="mb-3 grid grid-cols-2 gap-2 text-center sm:max-w-xs">
          <div class="rounded-lg bg-amber-50 py-2"><p class="text-xs text-amber-600">임박(D-30)</p><p class="text-lg font-bold text-amber-700">{{ lifeStat.soon }}</p></div>
          <div class="rounded-lg bg-rose-50 py-2"><p class="text-xs text-rose-600">초과</p><p class="text-lg font-bold text-rose-700">{{ lifeStat.over }}</p></div>
        </div>
        <div v-if="!lifeList.length" class="py-4 text-center text-sm text-slate-300">교체 임박/초과 항목이 없습니다 👍</div>
        <ul v-else class="divide-y divide-slate-50 text-sm">
          <li v-for="s in lifeList" :key="s.id" class="flex items-center justify-between py-2">
            <div class="min-w-0">
              <p class="truncate font-medium text-slate-700"><span class="font-mono text-xs text-brand-600">{{ s.code }}</span> {{ s.productName }}</p>
              <p class="truncate text-xs text-slate-400">다음 교체 {{ fmtDate(s.nextReplaceAt) || '-' }}</p>
            </div>
            <span class="badge shrink-0" :class="s._st === 'over' ? 'bg-rose-50 text-rose-600' : 'bg-amber-50 text-amber-600'">{{ s._st === 'over' ? -s._d + '일 초과' : 'D-' + s._d }}</span>
          </li>
        </ul>
      </div>

      <div class="mt-4 grid gap-4 lg:grid-cols-2">
        <!-- 재고 부족/품절 -->
        <div class="card p-5">
          <div class="mb-3 flex items-center justify-between">
            <h3 class="text-sm font-bold text-slate-700">재고 부족 · 품절</h3>
            <button class="text-xs text-brand-600" @click="router.push({ name: 'status' })">전체보기</button>
          </div>
          <div v-if="!lowList.length" class="py-6 text-center text-sm text-slate-300">부족/품절 SKU가 없습니다 👍</div>
          <ul v-else class="divide-y divide-slate-50 text-sm">
            <li v-for="s in lowList" :key="s.stockId" class="flex cursor-pointer items-center justify-between rounded-md px-1 py-2 hover:bg-slate-50" title="상품 상세" @click="openSkuDetail(s.skuId)">
              <div class="min-w-0">
                <p class="truncate font-medium text-slate-700"><span class="font-mono text-xs text-brand-600">{{ s.code }}</span> {{ s.productName }}</p>
                <p class="truncate text-xs text-slate-400">{{ s.pathLabel }}</p>
              </div>
              <span class="badge shrink-0" :class="s.qty <= 0 ? 'bg-rose-50 text-rose-600' : 'bg-amber-50 text-amber-600'">{{ s.qty }}개</span>
            </li>
          </ul>
        </div>

        <!-- 최근 입출고 -->
        <div class="card p-5">
          <h3 class="mb-3 text-sm font-bold text-slate-700">최근 재고 보관 위치 이동</h3>
          <div v-if="!moves.length" class="py-6 text-center text-sm text-slate-300">아직 이력이 없습니다.</div>
          <ul v-else class="divide-y divide-slate-50 text-sm">
            <li v-for="m in moves" :key="m.id" class="flex cursor-pointer items-center justify-between rounded-md px-1 py-2 hover:bg-slate-50" title="상품 상세" @click="openSkuDetail(m.skuId)">
              <div class="flex min-w-0 items-center gap-2">
                <span class="badge text-[10px] text-white" :class="typeColor[m.type]">{{ typeLabel[m.type] }}</span>
                <span class="truncate"><span class="font-mono text-xs text-slate-500">{{ m.skuCode }}</span> <span class="text-slate-600">{{ m.productName }}</span></span>
              </div>
              <span class="shrink-0 text-xs text-slate-400">{{ m.before }}→{{ m.after }} · {{ fmtTime(m.at) }}</span>
            </li>
          </ul>
        </div>
      </div>

      <!-- 관리자 통계 (admin 전용) -->
      <div v-if="auth.isAdmin" class="mt-4 grid gap-4 lg:grid-cols-3">
        <div class="card p-5">
          <h3 class="mb-3 text-sm font-bold text-slate-700">등록 많은 관리자 TOP20</h3>
          <div v-if="!topUsers.length" class="py-6 text-center text-sm text-slate-300">데이터 없음</div>
          <ol v-else class="space-y-1.5 text-sm">
            <li v-for="(u, i) in topUsers" :key="i" class="flex items-center justify-between">
              <span class="flex items-center gap-2"><span class="w-5 text-right font-bold text-brand-500">{{ i + 1 }}</span> <span class="text-slate-700">{{ u.name }}</span></span>
              <span class="badge bg-brand-50 text-brand-700">{{ u.cnt }}건</span>
            </li>
          </ol>
        </div>
        <div class="card p-5">
          <h3 class="mb-3 text-sm font-bold text-slate-700">SKU 많은 상품 TOP20</h3>
          <div v-if="!topProds.length" class="py-6 text-center text-sm text-slate-300">데이터 없음</div>
          <ol v-else class="space-y-1.5 text-sm">
            <li v-for="(p, i) in topProds" :key="i">
              <button class="flex w-full items-center justify-between gap-2 rounded-md px-1 py-1 text-left hover:bg-slate-50" title="상품 상세" @click="openProductDetail(p.productId)">
                <span class="flex min-w-0 items-center gap-2"><span class="w-5 shrink-0 text-right font-bold text-brand-500">{{ i + 1 }}</span> <span class="truncate text-slate-700">{{ p.productName }}</span></span>
                <span class="badge shrink-0 bg-slate-100 text-slate-600">{{ p.skuCount }} SKU</span>
              </button>
            </li>
          </ol>
        </div>
        <div class="card p-5">
          <h3 class="mb-3 text-sm font-bold text-slate-700">변경 많은 SKU TOP10</h3>
          <div v-if="!topSkus.length" class="py-6 text-center text-sm text-slate-300">데이터 없음</div>
          <ol v-else class="space-y-1 text-sm">
            <li v-for="(s, i) in topSkus" :key="i">
              <button class="flex w-full items-center justify-between gap-2 rounded-md px-1 py-1 text-left hover:bg-slate-50" title="상세 보기" @click="openSkuDetail(s.rowId)">
                <span class="flex min-w-0 items-center gap-2">
                  <span class="w-5 shrink-0 text-right font-bold text-brand-500">{{ i + 1 }}</span>
                  <span class="min-w-0">
                    <span class="block truncate text-slate-700">{{ s.productName || '(상품명 없음)' }}</span>
                    <span class="block truncate font-mono text-[10px] text-slate-400">{{ s.label }}</span>
                  </span>
                </span>
                <span class="badge shrink-0 bg-amber-50 text-amber-700">{{ s.cnt }}회</span>
              </button>
            </li>
          </ol>
        </div>
      </div>

      <div class="mt-4 card p-5">
        <h3 class="mb-2 text-sm font-bold text-slate-700">사용 순서</h3>
        <ol class="grid gap-2 text-sm text-slate-600 sm:grid-cols-2 lg:grid-cols-3">
          <li class="flex gap-2"><span class="font-bold text-brand-600">1.</span> 기준정보·위치관리: 카테고리·제품코드·상세코드 + 단지·보관위치 등록</li>
          <li class="flex gap-2"><span class="font-bold text-brand-600">2.</span> 상품관리: 상품 등록 (표준단가 입력)</li>
          <li class="flex gap-2"><span class="font-bold text-brand-600">3.</span> SKU관리: 변형(규격) 등록 (QR 자동발급) → 라벨 출력·부착</li>
          <li class="flex gap-2"><span class="font-bold text-brand-600">4.</span> 입고관리: SKU를 보관위치에 입고 (위치 필수)</li>
          <li class="flex gap-2"><span class="font-bold text-brand-600">5.</span> 현장: QR 스캔 → 입고/출고 처리</li>
          <li class="flex gap-2"><span class="font-bold text-brand-600">6.</span> 재고실사/조정으로 정확도 유지</li>
        </ol>
      </div>
    </template>

    <!-- 상세 (SKU / 상품) -->
    <BaseModal v-model="detailModal" :title="detailKind === 'product' ? '상품 상세' : 'SKU 상세'" size="md">
      <div v-if="detailLoading" class="py-8 text-center text-sm text-slate-400">불러오는 중…</div>
      <div v-else-if="!detail" class="py-8 text-center text-sm text-slate-400">정보를 불러올 수 없습니다.</div>
      <!-- SKU -->
      <div v-else-if="detailKind === 'sku'" class="space-y-3">
        <div class="flex gap-4">
          <img :src="resolveImage(detail)" class="h-24 w-24 shrink-0 rounded-lg border border-slate-100 bg-slate-50 object-contain p-1" alt="" />
          <div class="min-w-0">
            <span class="badge bg-brand-50 font-mono text-brand-700">{{ detail.code }}</span>
            <p class="mt-1 font-bold text-slate-800">{{ detail.productName }}<span v-if="specText(detail)" class="text-slate-400"> · {{ specText(detail) }}</span></p>
            <p class="mt-0.5 text-xs text-slate-400">{{ detail.pathLabel }}</p>
          </div>
        </div>
        <dl class="grid grid-cols-2 gap-x-4 gap-y-2 text-sm sm:grid-cols-3">
          <div><dt class="text-xs text-slate-400">색상</dt><dd class="text-slate-700">{{ detail.color || '—' }}</dd></div>
          <div><dt class="text-xs text-slate-400">출시년도</dt><dd class="text-slate-700">{{ detail.releaseYear || '—' }}</dd></div>
          <div><dt class="text-xs text-slate-400">생산년도</dt><dd class="text-slate-700">{{ detail.productionYear || '—' }}</dd></div>
          <div><dt class="text-xs text-slate-400">구매목적</dt><dd class="text-slate-700">{{ detail.purpose || '—' }}</dd></div>
          <div><dt class="text-xs text-slate-400">표준단가</dt><dd class="font-semibold text-slate-800">{{ Number(detail.price || 0).toLocaleString() }}원</dd></div>
          <div><dt class="text-xs text-slate-400">안전재고</dt><dd class="text-slate-700">{{ detail.safetyStock ?? 0 }}</dd></div>
        </dl>
      </div>
      <!-- 상품 -->
      <div v-else class="space-y-3">
        <div class="flex gap-4">
          <img :src="detail.mainImageUrl || '/no-image.svg'" class="h-24 w-24 shrink-0 rounded-lg border border-slate-100 bg-slate-50 object-contain p-1" alt="" />
          <div class="min-w-0">
            <span class="badge bg-brand-50 font-mono text-brand-700">{{ detail.code }}</span>
            <p class="mt-1 font-bold text-slate-800">{{ detail.name }}</p>
            <p class="mt-0.5 text-xs text-slate-400">{{ detail.pathLabel }}</p>
          </div>
        </div>
        <dl class="grid grid-cols-2 gap-x-4 gap-y-2 text-sm sm:grid-cols-3">
          <div><dt class="text-xs text-slate-400">표준단가</dt><dd class="font-semibold text-slate-800">{{ Number(detail.price || 0).toLocaleString() }}원</dd></div>
        </dl>
      </div>
      <template #footer><button class="btn-primary" @click="detailModal = false">닫기</button></template>
    </BaseModal>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { skus, recentMovements, getTodayStats, getDailyStats, auditTopUsers, topProductsBySku, topChangedSkus } from '@/services/db'
import { useAuthStore } from '@/stores/auth'
import { lifecycleStatus, daysUntil, fmtDate, fmtDateTime } from '@/utils/date'
import PageHeader from '@/components/ui/PageHeader.vue'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(true)
const stat = ref({ complexes: 0, products: 0, skus: 0, totalQty: 0, low: 0, out: 0 })
const lowList = ref([])
const moves = ref([])
const today = ref(null)
const lifeStat = ref({ soon: 0, over: 0 })
const lifeList = ref([])
const topUsers = ref([])
const topProds = ref([])
const topSkus = ref([])
const byComplex = ref([])
const daily = ref([])
const stockStat = ref({ total: 0, normal: 0, low: 0, out: 0 })

const typeLabel = { in: '입고', out: '출고', adjust: '조정', audit: '실사' }
const typeColor = { in: 'bg-emerald-500', out: 'bg-sky-500', adjust: 'bg-amber-500', audit: 'bg-violet-500' }

onMounted(async () => {
  try {
    const [sum, mv, ts] = await Promise.all([
      skus.dashboardSummary(),
      recentMovements(8),
      getTodayStats(),
    ])
    today.value = ts
    stat.value = {
      complexes: sum.complexCount,
      products: sum.productCount,
      skus: sum.skuCount,
      totalQty: sum.totalQty,
      low: sum.lowCount,
      out: sum.outCount,
    }
    lowList.value = sum.lowList
    moves.value = mv
    lifeStat.value = { soon: sum.lifeSoon, over: sum.lifeOver }
    lifeList.value = sum.lifeList.map((s) => ({ ...s, _st: lifecycleStatus(s.nextReplaceAt), _d: daysUntil(s.nextReplaceAt) }))

    // 차트 데이터 (재고상태 / 단지별 / 일별 추이)
    try {
      const [pg, gc, ds] = await Promise.all([skus.page({ pageSize: 1 }), skus.groupByComplex({}), getDailyStats(7)])
      stockStat.value = { total: pg.total, normal: Math.max(0, pg.total - pg.lowCount - pg.outCount), low: pg.lowCount, out: pg.outCount }
      byComplex.value = gc
      daily.value = ds || []
    } catch (e) { /* 차트 데이터 실패는 치명적 아님 */ }

    // 관리자 통계 (admin 전용)
    if (auth.isAdmin) {
      const [tu, tp, ts2] = await Promise.all([auditTopUsers(10), topProductsBySku(10), topChangedSkus(10)])
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
          <h3 class="mb-1 text-sm font-bold text-slate-700">최근 입출고 추이 <span class="text-xs font-normal text-slate-400">(수량)</span></h3>
          <div class="mb-2 flex gap-3 text-[11px] text-slate-400">
            <span class="flex items-center gap-1"><span class="h-2 w-2 rounded-sm bg-emerald-500" />입고</span>
            <span class="flex items-center gap-1"><span class="h-2 w-2 rounded-sm bg-sky-500" />출고</span>
          </div>
          <div v-if="!dailyChart.length" class="py-6 text-center text-sm text-slate-300">최근 입출고 기록이 없습니다.</div>
          <div v-else class="flex h-32 items-end gap-2">
            <div v-for="d in dailyChart" :key="d.date" class="flex flex-1 flex-col items-center gap-1">
              <div class="flex h-24 w-full items-end justify-center gap-0.5">
                <div class="w-1/2 rounded-t bg-emerald-500" :style="{ height: Math.max(2, d.inH) + '%' }" :title="'입고 ' + d.inQty" />
                <div class="w-1/2 rounded-t bg-sky-500" :style="{ height: Math.max(2, d.outH) + '%' }" :title="'출고 ' + d.outQty" />
              </div>
              <span class="text-[9px] text-slate-400">{{ d.date }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 오늘 입출고 (집계 문서 기반, 로그 전체 스캔 없음) -->
      <div class="mt-4 card p-4">
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-bold text-slate-700">오늘 입출고</h3>
          <span class="text-xs text-slate-400">{{ new Date().toLocaleDateString('ko-KR') }}</span>
        </div>
        <div class="mt-2 grid grid-cols-4 gap-2 text-center">
          <div class="rounded-lg bg-emerald-50 py-2"><p class="text-xs text-emerald-600">입고건</p><p class="text-lg font-bold text-emerald-700">{{ today?.inCount || 0 }}</p></div>
          <div class="rounded-lg bg-sky-50 py-2"><p class="text-xs text-sky-600">출고건</p><p class="text-lg font-bold text-sky-700">{{ today?.outCount || 0 }}</p></div>
          <div class="rounded-lg bg-slate-50 py-2"><p class="text-xs text-slate-500">입고수량</p><p class="text-lg font-bold text-slate-700">+{{ today?.inQty || 0 }}</p></div>
          <div class="rounded-lg bg-slate-50 py-2"><p class="text-xs text-slate-500">출고수량</p><p class="text-lg font-bold text-slate-700">-{{ today?.outQty || 0 }}</p></div>
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
            <li v-for="s in lowList" :key="s.id" class="flex items-center justify-between py-2">
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
          <h3 class="mb-3 text-sm font-bold text-slate-700">최근 재고 이동</h3>
          <div v-if="!moves.length" class="py-6 text-center text-sm text-slate-300">아직 이력이 없습니다.</div>
          <ul v-else class="divide-y divide-slate-50 text-sm">
            <li v-for="m in moves" :key="m.id" class="flex items-center justify-between py-2">
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
          <h3 class="mb-3 text-sm font-bold text-slate-700">등록 많은 관리자 TOP10</h3>
          <div v-if="!topUsers.length" class="py-6 text-center text-sm text-slate-300">데이터 없음</div>
          <ol v-else class="space-y-1.5 text-sm">
            <li v-for="(u, i) in topUsers" :key="i" class="flex items-center justify-between">
              <span class="flex items-center gap-2"><span class="w-5 text-right font-bold text-brand-500">{{ i + 1 }}</span> <span class="text-slate-700">{{ u.name }}</span></span>
              <span class="badge bg-brand-50 text-brand-700">{{ u.cnt }}건</span>
            </li>
          </ol>
        </div>
        <div class="card p-5">
          <h3 class="mb-3 text-sm font-bold text-slate-700">SKU 많은 상품 TOP10</h3>
          <div v-if="!topProds.length" class="py-6 text-center text-sm text-slate-300">데이터 없음</div>
          <ol v-else class="space-y-1.5 text-sm">
            <li v-for="(p, i) in topProds" :key="i" class="flex items-center justify-between">
              <span class="flex min-w-0 items-center gap-2"><span class="w-5 shrink-0 text-right font-bold text-brand-500">{{ i + 1 }}</span> <span class="truncate text-slate-700">{{ p.productName }}</span></span>
              <span class="badge shrink-0 bg-slate-100 text-slate-600">{{ p.skuCount }} SKU</span>
            </li>
          </ol>
        </div>
        <div class="card p-5">
          <h3 class="mb-3 text-sm font-bold text-slate-700">변경 많은 SKU TOP10</h3>
          <div v-if="!topSkus.length" class="py-6 text-center text-sm text-slate-300">데이터 없음</div>
          <ol v-else class="space-y-1.5 text-sm">
            <li v-for="(s, i) in topSkus" :key="i" class="flex items-center justify-between">
              <span class="flex min-w-0 items-center gap-2"><span class="w-5 shrink-0 text-right font-bold text-brand-500">{{ i + 1 }}</span> <span class="truncate font-mono text-xs text-slate-700">{{ s.label }}</span></span>
              <span class="badge shrink-0 bg-amber-50 text-amber-700">{{ s.cnt }}회</span>
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
  </div>
</template>

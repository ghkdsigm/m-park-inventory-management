<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { complexes, products, skus, recentMovements, getTodayStats } from '@/services/db'
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

const typeLabel = { in: '입고', out: '출고', adjust: '조정', audit: '실사' }
const typeColor = { in: 'bg-emerald-500', out: 'bg-sky-500', adjust: 'bg-amber-500', audit: 'bg-violet-500' }

onMounted(async () => {
  try {
    const [cx, ps, sk, mv, ts] = await Promise.all([
      complexes.list(),
      products.list(),
      skus.list(),
      recentMovements(8),
      getTodayStats(),
    ])
    today.value = ts
    stat.value = {
      complexes: cx.length,
      products: ps.length,
      skus: sk.length,
      totalQty: sk.reduce((a, s) => a + (Number(s.qty) || 0), 0),
      low: sk.filter((s) => s.status === 'low').length,
      out: sk.filter((s) => s.status === 'out').length,
    }
    lowList.value = sk.filter((s) => s.status === 'low' || s.status === 'out').slice(0, 6)
    moves.value = mv
    const life = sk
      .filter((s) => s.lifecycleEnabled)
      .map((s) => ({ ...s, _st: lifecycleStatus(s.nextReplaceAt), _d: daysUntil(s.nextReplaceAt) }))
    lifeStat.value = {
      soon: life.filter((s) => s._st === 'soon').length,
      over: life.filter((s) => s._st === 'over').length,
    }
    lifeList.value = life
      .filter((s) => s._st === 'soon' || s._st === 'over')
      .sort((a, b) => (a._d ?? 1e9) - (b._d ?? 1e9))
      .slice(0, 6)
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

      <div class="mt-4 card p-5">
        <h3 class="mb-2 text-sm font-bold text-slate-700">사용 순서</h3>
        <ol class="grid gap-2 text-sm text-slate-600 sm:grid-cols-2 lg:grid-cols-3">
          <li class="flex gap-2"><span class="font-bold text-brand-600">1.</span> 기준정보관리: 단지→카테고리→제품코드→제품상세코드 등록</li>
          <li class="flex gap-2"><span class="font-bold text-brand-600">2.</span> 상품관리: 상품 등록 (단지 필수)</li>
          <li class="flex gap-2"><span class="font-bold text-brand-600">3.</span> SKU관리: SKU 생성 (QR 자동발급) → 라벨 출력·부착</li>
          <li class="flex gap-2"><span class="font-bold text-brand-600">4.</span> 입고관리: 초기/추가 재고 입고</li>
          <li class="flex gap-2"><span class="font-bold text-brand-600">5.</span> 현장: QR 스캔 → 입고/출고 처리</li>
          <li class="flex gap-2"><span class="font-bold text-brand-600">6.</span> 재고실사/조정으로 정확도 유지</li>
        </ol>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { skus, products, applyStock, listMovements, replaceLifecycle } from '@/services/db'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import { resolveImage } from '@/utils/image'
import { lifecycleStatus, daysUntil, fmtDate, fmtDateTime } from '@/utils/date'
import { specText } from '@/utils/sku'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const toast = useToast()
const confirm = ref(null)

const loading = ref(true)
const sku = ref(null)
const product = ref(null)
const movements = ref([])
const working = ref(false)

const imageUrl = computed(() => resolveImage(sku.value, product.value))

const ioQty = ref(1)
const setQty = ref(0)

async function load() {
  loading.value = true
  try {
    sku.value = await skus.getByCode(decodeURIComponent(route.params.code))
    if (sku.value) {
      setQty.value = sku.value.qty
      // 상품 대표이미지 폴백을 위해 상품도 조회 (최신값 우선)
      product.value = sku.value.productId ? await products.get(sku.value.productId) : null
      movements.value = await listMovements(sku.value.id, 8)
    }
  } catch (e) {
    toast.error('조회 실패: ' + (e.message || e.code))
  } finally {
    loading.value = false
  }
}
onMounted(load)

const attrLine = computed(() => {
  const s = sku.value
  if (!s) return ''
  const a = []
  if (specText(s)) a.push(specText(s))
  if (s.color) a.push(s.color)
  if (s.releaseYear) a.push(`출시 ${s.releaseYear}`)
  if (s.productionYear) a.push(`생산 ${s.productionYear}`)
  if (s.purpose) a.push(s.purpose)
  return a.join(' · ')
})

const statusMeta = {
  in_stock: { t: '정상', c: 'bg-emerald-500' },
  low: { t: '재고부족', c: 'bg-amber-500' },
  out: { t: '품절', c: 'bg-rose-500' },
}
const typeLabel = { in: '입고', out: '출고', adjust: '조정', audit: '실사' }

// 연한(주기 교체)
const lifeStatus = computed(() => (sku.value?.lifecycleEnabled ? lifecycleStatus(sku.value.nextReplaceAt) : 'none'))
const lifeDays = computed(() => daysUntil(sku.value?.nextReplaceAt))
const lifeMeta = {
  ok: { t: '정상', c: 'bg-emerald-500' },
  soon: { t: '교체 임박', c: 'bg-amber-500' },
  over: { t: '교체 초과', c: 'bg-rose-500' },
  none: { t: '', c: '' },
}
const lifeText = computed(() => {
  const d = lifeDays.value
  if (d === null) return ''
  if (d < 0) return `${-d}일 초과`
  if (d === 0) return '오늘'
  return `D-${d}`
})

async function doReplace() {
  const ok = await confirm.value.ask({
    title: '교체 완료',
    message: `SKU ${sku.value.code}\n교체 처리하고 다음 예정일을 갱신할까요?`,
    confirmText: '교체 완료',
  })
  if (!ok) return
  working.value = true
  try {
    const r = await replaceLifecycle(sku.value.id, auth.actor, sku.value.replaceReason || '')
    toast.success(`교체 완료 · 다음 예정 ${fmtDate(r.nextReplaceAt) || '-'}`)
    await load()
  } catch (e) {
    toast.error(e.message || '처리 실패')
  } finally {
    working.value = false
  }
}

async function run(type, value, label) {
  const ok = await confirm.value.ask({
    title: `${label} 처리`,
    message: `SKU ${sku.value.code}\n${label} 진행할까요?`,
    confirmText: label,
  })
  if (!ok) return
  working.value = true
  try {
    const r = await applyStock(sku.value.id, type, value, auth.actor, '모바일 QR')
    toast.success(`${label} 완료 · 재고 ${r.before}→${r.after}개`)
    await load()
  } catch (e) {
    toast.error(e.message || '처리 실패')
  } finally {
    working.value = false
  }
}

const fmtTime = fmtDateTime
</script>

<template>
  <div class="mx-auto flex min-h-full max-w-md flex-col bg-slate-50">
    <header class="sticky top-0 z-10 flex items-center justify-between bg-brand-600 px-4 py-3 text-white">
      <button class="flex items-center gap-1 text-sm" @click="router.push('/')">
        <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 18l-6-6 6-6" stroke-linecap="round" stroke-linejoin="round"/></svg>홈
      </button>
      <span class="text-sm font-semibold">엠파크 WMS</span>
      <span class="text-xs text-white/70">{{ auth.displayName }}</span>
    </header>

    <div v-if="loading" class="flex flex-1 items-center justify-center text-sm text-slate-400">불러오는 중…</div>

    <div v-else-if="!sku" class="flex flex-1 flex-col items-center justify-center gap-2 p-8 text-center">
      <p class="text-slate-500">SKU를 찾을 수 없습니다.</p>
      <p class="font-mono text-xs text-slate-400">{{ route.params.code }}</p>
      <button class="btn-primary mt-2" @click="router.push('/')">홈으로</button>
    </div>

    <div v-else class="flex-1 space-y-4 p-4">
      <p class="text-xs text-slate-400">{{ sku.pathLabel }}</p>
      <div class="flex flex-wrap items-center gap-2">
        <span class="badge bg-brand-50 text-brand-700">📍 {{ sku.locationLabel ? sku.complexName + ' › ' + sku.locationLabel : (sku.complexName || '위치 미지정') }}</span>
        <span v-if="sku.storageLocationCode" class="font-mono text-[11px] text-slate-400">{{ sku.storageLocationCode }}</span>
      </div>

      <div class="card overflow-hidden">
        <img :src="imageUrl" class="h-44 w-full bg-slate-50 object-cover" alt="상품 이미지" />
        <div class="flex items-center justify-between px-5 pt-4">
          <div>
            <p class="font-mono text-lg font-bold text-slate-800">{{ sku.code }}</p>
            <p class="text-sm text-slate-600">{{ sku.productName }}</p>
            <p v-if="attrLine" class="mt-0.5 text-xs text-slate-400">{{ attrLine }}</p>
          </div>
          <span class="badge text-white" :class="statusMeta[sku.status]?.c || 'bg-slate-400'">{{ statusMeta[sku.status]?.t }}</span>
        </div>
        <div class="my-4 text-center">
          <p class="text-xs font-medium text-slate-400">현재 재고</p>
          <p class="text-5xl font-extrabold" :class="sku.qty > 0 ? 'text-brand-600' : 'text-rose-500'">{{ sku.qty }}<span class="ml-1 text-lg text-slate-400">개</span></p>
        </div>
        <div class="grid grid-cols-3 gap-px bg-slate-100 text-center text-sm">
          <div class="bg-white py-3"><p class="text-xs text-slate-400">단가</p><p class="font-semibold text-slate-700">{{ Number(sku.price).toLocaleString() }}원</p></div>
          <div class="bg-white py-3"><p class="text-xs text-slate-400">안전재고</p><p class="font-semibold text-slate-700">{{ sku.safetyStock || 0 }}</p></div>
          <div class="bg-white py-3"><p class="text-xs text-slate-400">누적출고</p><p class="font-semibold text-slate-700">{{ sku.totalOut || 0 }}</p></div>
        </div>
      </div>

      <!-- 입고 / 출고 -->
      <div class="card p-4">
        <p class="mb-2 text-sm font-semibold text-slate-700">입고 / 출고</p>
        <div class="flex items-center gap-2">
          <button class="btn-ghost btn-sm" @click="ioQty = Math.max(1, ioQty - 1)">－</button>
          <input v-model.number="ioQty" type="number" min="1" class="input w-20 text-center" />
          <button class="btn-ghost btn-sm" @click="ioQty++">＋</button>
          <span class="text-sm text-slate-400">개</span>
        </div>
        <div class="mt-3 grid grid-cols-2 gap-2">
          <button class="btn py-3 text-white bg-emerald-600 hover:bg-emerald-700" :disabled="working" @click="run('in', ioQty, '입고')">입고 +{{ ioQty }}</button>
          <button class="btn py-3 text-white bg-sky-600 hover:bg-sky-700" :disabled="working || sku.qty < ioQty" @click="run('out', ioQty, '출고')">출고 -{{ ioQty }}</button>
        </div>
      </div>

      <!-- 조정 / 실사 (관리자) -->
      <div v-if="auth.isAdmin" class="card p-4">
        <p class="mb-2 text-sm font-semibold text-slate-700">재고조정 / 실사 <span class="badge bg-slate-100 text-[10px] text-slate-400">관리자</span></p>
        <div class="flex items-center gap-2">
          <span class="text-sm text-slate-400">목표 수량</span>
          <input v-model.number="setQty" type="number" min="0" class="input w-24 text-center" />
        </div>
        <div class="mt-3 grid grid-cols-2 gap-2">
          <button class="btn py-3 text-white bg-amber-500 hover:bg-amber-600" :disabled="working" @click="run('adjust', setQty, '재고조정')">조정 = {{ setQty }}</button>
          <button class="btn py-3 text-white bg-violet-600 hover:bg-violet-700" :disabled="working" @click="run('audit', setQty, '재고실사')">실사 = {{ setQty }}</button>
        </div>
      </div>

      <!-- 연한(주기 교체) -->
      <div v-if="sku.lifecycleEnabled" class="card p-4">
        <div class="flex items-center justify-between">
          <p class="text-sm font-semibold text-slate-700">연한(주기 교체)</p>
          <span class="badge text-white" :class="lifeMeta[lifeStatus]?.c">{{ lifeMeta[lifeStatus]?.t }}<span v-if="lifeText"> · {{ lifeText }}</span></span>
        </div>
        <div class="mt-2 grid grid-cols-2 gap-px overflow-hidden rounded-lg bg-slate-100 text-center text-sm">
          <div class="bg-white py-2"><p class="text-xs text-slate-400">다음 교체예정</p><p class="font-semibold text-slate-700">{{ fmtDate(sku.nextReplaceAt) || '—' }}</p></div>
          <div class="bg-white py-2"><p class="text-xs text-slate-400">최근 교체일</p><p class="font-semibold text-slate-700">{{ fmtDate(sku.lastReplacedAt) || '—' }}</p></div>
        </div>
        <p v-if="sku.replaceReason" class="mt-2 text-xs text-slate-500">사유: {{ sku.replaceReason }}</p>
        <button class="btn mt-3 w-full bg-violet-600 py-3 text-white hover:bg-violet-700" :disabled="working" @click="doReplace">교체 완료 처리</button>
      </div>

      <!-- 이력 -->
      <div v-if="movements.length" class="card p-4">
        <p class="mb-2 text-xs font-semibold text-slate-500">최근 처리 이력</p>
        <ul class="divide-y divide-slate-50 text-sm">
          <li v-for="m in movements" :key="m.id" class="flex items-center justify-between py-2">
            <span class="flex items-center gap-2">
              <span class="badge bg-slate-100 text-[10px]">{{ typeLabel[m.type] }}</span>
              <span class="text-slate-600">{{ m.byName }}</span>
            </span>
            <span class="text-xs text-slate-400">{{ m.before }}→{{ m.after }}개 · {{ fmtTime(m.at) }}</span>
          </li>
        </ul>
      </div>
    </div>

    <ConfirmDialog ref="confirm" />
  </div>
</template>

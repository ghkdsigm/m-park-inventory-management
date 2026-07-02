<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { skus, products, storageLocations, zones, subZones, inboundStock, outboundStock, adjustStock, listMovements, replaceLifecycle, voidMovement } from '@/services/db'
import { setAutoLogin, getAutoLogin } from '@/supabase'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import BaseModal from '@/components/ui/BaseModal.vue'
import { resolveImage } from '@/utils/image'
import { lifecycleStatus, daysUntil, fmtDate, fmtDateTime } from '@/utils/date'
import { specText } from '@/utils/sku'

const route = useRoute()
const auth = useAuthStore()
const toast = useToast()
const confirm = ref(null)

const loading = ref(false)
const sku = ref(null)          // 변형 SKU
const product = ref(null)
const stockRows = ref([])      // 위치별 재고행
const totalQty = ref(0)
const selectedStockId = ref('')
const movements = ref([])
const working = ref(false)
const imgOpen = ref(false)

const EMAIL_KEY = 'mpark.savedEmail'
const loginEmail = ref('')
const loginPw = ref('')
const loginLoading = ref(false)
const rememberId = ref(true)
const autoLogin = ref(true)
try {
  const e = localStorage.getItem(EMAIL_KEY)
  if (e) { loginEmail.value = e; rememberId.value = true } else { rememberId.value = false }
  autoLogin.value = getAutoLogin()
} catch (e) { /* ignore */ }

const imageUrl = computed(() => resolveImage(sku.value, product.value))
const selectedStock = computed(() => stockRows.value.find((r) => r.stockId === selectedStockId.value) || null)

const IN_REASONS = ['구매입고', '반품입고', '생산입고', '재고보충', '기타']
const OUT_REASONS = ['판매/사용', '폐기', '반품출고', '샘플/전시', '기타']
const ADJUST_REASONS = ['파손', '분실', '도난', '오입력 정정', '유통기한 경과', '기타']

const mode = ref('') // '' | 'in' | 'out'
const inQty = ref(1)
const inReason = ref('')
const inMemo = ref('')
const outQty = ref(1)
const outReason = ref('')
const outMemo = ref('')
// 출고 상세
const requestDept = ref('')
const requester = ref('')
const handler = ref('')
const USAGE_TYPES = ['usage', 'common']
const zoneList = ref([])
const subZoneList = ref([])
const outComplex = ref('')
const outZone = ref('')
const outSub = ref('')
const showUsage = computed(() => !['폐기', '반품출고'].includes(outReason.value))
const outComplexChoices = computed(() => {
  const m = new Map()
  zoneList.value.forEach((z) => { if (USAGE_TYPES.includes(z.type) && z.complexId) m.set(z.complexId, z.complexName) })
  return [...m].map(([id, name]) => ({ id, name }))
})
const outZoneChoices = computed(() => zoneList.value.filter((z) => z.complexId === outComplex.value && USAGE_TYPES.includes(z.type)))
const outSubChoices = computed(() => subZoneList.value.filter((s) => s.zoneId === outZone.value && USAGE_TYPES.includes(s.type)))
const outLocLabel = computed(() => {
  if (!outComplex.value || !outZone.value) return ''
  const c = outComplexChoices.value.find((x) => x.id === outComplex.value)
  const z = zoneList.value.find((x) => x.id === outZone.value)
  const s = subZoneList.value.find((x) => x.id === outSub.value)
  return [c?.name, z?.name, s?.name].filter(Boolean).join(' › ')
})
function onOutComplex() { outZone.value = ''; outSub.value = '' }
function onOutZone() { outSub.value = '' }
function onOutReason() { if (!showUsage.value) { outComplex.value = ''; outZone.value = ''; outSub.value = '' } }
const setQty = ref(0)
const adjReason = ref('')
const adjMemo = ref('')

function pickMode(m) {
  mode.value = m
  if (m === 'in') { inQty.value = 1; inReason.value = ''; inMemo.value = '' }
  else if (m === 'out') {
    outQty.value = 1; outReason.value = ''; outMemo.value = ''
    requestDept.value = ''; requester.value = ''; handler.value = auth.user?.name || ''
    outComplex.value = ''; outZone.value = ''; outSub.value = ''
  }
}

/* 입고 보관위치 선택 (단지 → 구역 → 상세구역 → 보관위치) */
const storageLocs = ref([])
const inComplex = ref('')
const inZone = ref('')
const inSub = ref('')
const inLoc = ref('')
const complexChoices = computed(() => {
  const m = new Map()
  storageLocs.value.forEach((l) => l.complexId && m.set(l.complexId, l.complexName))
  return [...m].map(([id, name]) => ({ id, name }))
})
const locForComplex = computed(() => storageLocs.value.filter((l) => l.complexId === inComplex.value))
const zoneChoices = computed(() => {
  const m = new Map()
  locForComplex.value.forEach((l) => l.zoneId && m.set(l.zoneId, l.zoneName))
  return [...m].map(([id, name]) => ({ id, name }))
})
const subChoices = computed(() => {
  const m = new Map()
  locForComplex.value.forEach((l) => { if (l.zoneId === inZone.value && l.subZoneId) m.set(l.subZoneId, l.subZoneName) })
  return [...m].map(([id, name]) => ({ id, name }))
})
const locOptions = computed(() => locForComplex.value.filter((l) => (!inZone.value || l.zoneId === inZone.value) && (!inSub.value || l.subZoneId === inSub.value)))
function onInComplex() { inZone.value = ''; inSub.value = ''; inLoc.value = '' }
function onInZone() { inSub.value = ''; inLoc.value = '' }

function krError(e) {
  const m = (e?.message || '').toLowerCase()
  if (m.includes('invalid login')) return '이메일 또는 비밀번호가 올바르지 않습니다.'
  return e?.message || '로그인에 실패했습니다.'
}
async function doLogin() {
  const em = (loginEmail.value || '').trim().replace(/\s/g, '')
  const pw = loginPw.value || ''
  if (!em || !pw) return toast.error('이메일과 비밀번호를 입력하세요.')
  setAutoLogin(autoLogin.value)
  try {
    if (rememberId.value) localStorage.setItem(EMAIL_KEY, em); else localStorage.removeItem(EMAIL_KEY)
  } catch (e) { /* ignore */ }
  loginLoading.value = true
  try { await auth.login(em, pw); loginPw.value = ''; await load() }
  catch (e) { toast.error(krError(e)) } finally { loginLoading.value = false }
}
async function doLogout() {
  await auth.logout(); sku.value = null; product.value = null; stockRows.value = []; movements.value = []
}

async function loadStock() {
  const r = await skus.page({ skuId: sku.value.id, pageSize: 100, sort: 'qtyDesc' })
  stockRows.value = r.rows
  totalQty.value = r.totalQty
  if (stockRows.value.length === 1) selectedStockId.value = stockRows.value[0].stockId
  else if (!stockRows.value.find((x) => x.stockId === selectedStockId.value)) selectedStockId.value = ''
}
async function load() {
  if (!auth.isLoggedIn) return
  loading.value = true
  try {
    sku.value = await skus.getByCode(decodeURIComponent(route.params.code))
    if (sku.value) {
      product.value = sku.value.productId ? await products.get(sku.value.productId) : null
      await loadStock()
      movements.value = await listMovements(sku.value.id, 20)
      if (auth.canStock) {
        ;[storageLocs.value, zoneList.value, subZoneList.value] = await Promise.all([storageLocations.list(), zones.listAll(), subZones.listAll()])
      }
    }
  } catch (e) { toast.error('조회 실패: ' + (e.message || e.code)) } finally { loading.value = false }
}
onMounted(async () => { if (!auth.ready) await auth.init(); await load() })

const blockGesture = (e) => e.preventDefault()
const blockMultiTouch = (e) => { if (e.touches && e.touches.length > 1) e.preventDefault() }
onMounted(() => {
  document.addEventListener('gesturestart', blockGesture, { passive: false })
  document.addEventListener('gesturechange', blockGesture, { passive: false })
  document.addEventListener('gestureend', blockGesture, { passive: false })
  document.addEventListener('touchmove', blockMultiTouch, { passive: false })
})
onUnmounted(() => {
  document.removeEventListener('gesturestart', blockGesture)
  document.removeEventListener('gesturechange', blockGesture)
  document.removeEventListener('gestureend', blockGesture)
  document.removeEventListener('touchmove', blockMultiTouch)
})

const attrLine = computed(() => {
  const s = sku.value
  if (!s) return ''
  return [specText(s), s.color, s.releaseYear && `출시 ${s.releaseYear}`, s.productionYear && `생산 ${s.productionYear}`, s.purpose].filter(Boolean).join(' · ')
})
const typeLabel = { in: '입고', out: '출고', adjust: '조정', audit: '실사', void: '취소' }

/* 처리 취소 */
const VOID_REASONS = ['수량 오기입', '방향 오선택(입출고 바뀜)', '중복 처리', '기타']
const voidTarget = ref(null)
const voidReason = ref('')
const voidMemo = ref('')
const voiding = ref(false)
const helpOpen = ref(false)
function isToday(at) { if (!at) return false; const d = new Date(at); const n = new Date(); return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate() }
function canVoid(m) { return m.byUserId === auth.user?.id && m.type !== 'void' && !m.voided && isToday(m.at) }
function openVoid(m) { voidTarget.value = m; voidReason.value = ''; voidMemo.value = '' }
async function confirmVoid() {
  if (!voidTarget.value) return
  if (!voidReason.value) return toast.error('취소 사유를 선택하세요.')
  const reasonText = voidReason.value === '기타' ? (voidMemo.value || '').trim() : voidReason.value
  if (voidReason.value === '기타' && !reasonText) return toast.error('취소 사유를 입력하세요.')
  voiding.value = true
  try { await voidMovement(voidTarget.value.id, reasonText); toast.success('처리를 취소했습니다.'); voidTarget.value = null; await load() }
  catch (e) { toast.error(e.message || '취소 실패') } finally { voiding.value = false }
}

/* 연한 (선택 재고행 기준) */
const lifeStatus = computed(() => (sku.value?.lifecycleEnabled && selectedStock.value ? lifecycleStatus(selectedStock.value.nextReplaceAt) : 'none'))
const lifeDays = computed(() => daysUntil(selectedStock.value?.nextReplaceAt))
const lifeMeta = { ok: { t: '정상', c: 'bg-emerald-500' }, soon: { t: '교체 임박', c: 'bg-amber-500' }, over: { t: '교체 초과', c: 'bg-rose-500' }, none: { t: '', c: '' } }
const lifeText = computed(() => { const d = lifeDays.value; if (d === null) return ''; if (d < 0) return `${-d}일 초과`; if (d === 0) return '오늘'; return `D-${d}` })
async function doReplace() {
  if (!selectedStock.value) return toast.error('재고(위치)를 먼저 선택하세요.')
  const ok = await confirm.value.ask({ title: '교체 완료', message: `${sku.value.code} @${selectedStock.value.complexName}\n교체 처리하고 다음 예정일을 갱신할까요?`, confirmText: '교체 완료' })
  if (!ok) return
  working.value = true
  try { const r = await replaceLifecycle(selectedStock.value.stockId, auth.actor, sku.value.replaceReason || ''); toast.success(`교체 완료 · 다음 예정 ${fmtDate(r.nextReplaceAt) || '-'}`); await load() }
  catch (e) { toast.error(e.message || '처리 실패') } finally { working.value = false }
}

/* 입고 */
async function doInbound() {
  if (!inLoc.value) return toast.error('입고할 보관위치를 선택하세요.')
  if (!inReason.value) return toast.error('사유를 선택하세요.')
  const v = Number(inQty.value)
  if (!Number.isFinite(v) || v <= 0) return toast.error('수량은 1 이상이어야 합니다.')
  const loc = storageLocs.value.find((l) => l.id === inLoc.value)
  const locLabel = loc ? [loc.code, [loc.zoneName, loc.subZoneName, loc.name].filter(Boolean).join(' › ')].filter(Boolean).join(' · ') : ''
  const ok = await confirm.value.ask({ title: '입고', message: `${sku.value.code} · ${v}개\n위치: ${locLabel}\n입고하시겠습니까?`, confirmText: '입고' })
  if (!ok) return
  working.value = true
  try {
    const r = await inboundStock(sku.value.id, inLoc.value, v, inReason.value === '기타' ? inMemo.value : '', inReason.value)
    toast.success(`입고 완료 · 재고 ${r.before}→${r.after}개`)
    mode.value = ''; await load()
  } catch (e) { toast.error(e.message || '처리 실패') } finally { working.value = false }
}
/* 출고 */
async function doOutbound() {
  if (!selectedStock.value) return toast.error('출고할 재고(위치)를 선택하세요.')
  if (!outReason.value) return toast.error('사유를 선택하세요.')
  const v = Number(outQty.value)
  if (!Number.isFinite(v) || v <= 0) return toast.error('수량은 1 이상이어야 합니다.')
  if (v > selectedStock.value.qty) return toast.error(`재고 부족: 현재 ${selectedStock.value.qty}개`)
  const ok = await confirm.value.ask({
    title: '출고',
    message: `${sku.value.code} · ${v}개\n위치: ${selectedStock.value.complexName}${selectedStock.value.locationLabel ? ' › ' + selectedStock.value.locationLabel : ''}\n재고 ${selectedStock.value.qty}→${selectedStock.value.qty - v}개\n출고하시겠습니까?`,
    confirmText: '출고',
  })
  if (!ok) return
  working.value = true
  try {
    const r = await outboundStock(selectedStock.value.stockId, v, outReason.value === '기타' ? outMemo.value : '', outReason.value, {
      usagePlace: outLocLabel.value, requestDept: requestDept.value.trim(),
      requester: requester.value.trim(), handler: handler.value.trim(),
    })
    toast.success(`출고 완료 · 재고 ${r.before}→${r.after}개`)
    mode.value = ''; await load()
  } catch (e) { toast.error(e.message || '처리 실패') } finally { working.value = false }
}
/* 조정/실사 */
async function doAdjust(type, label) {
  if (!selectedStock.value) return toast.error('재고(위치)를 선택하세요.')
  if (!adjReason.value) return toast.error('사유를 선택하세요.')
  const v = Number(setQty.value)
  if (!Number.isFinite(v) || v < 0) return toast.error('수량을 올바르게 입력하세요.')
  const ok = await confirm.value.ask({ title: `${label} 처리`, message: `${sku.value.code} @${selectedStock.value.complexName}\n${label} = ${v}개 진행할까요?`, confirmText: label })
  if (!ok) return
  working.value = true
  try {
    const r = await adjustStock(selectedStock.value.stockId, type, v, adjReason.value === '기타' ? adjMemo.value : '', adjReason.value)
    toast.success(`${label} 완료 · 재고 ${r.before}→${r.after}개`); adjMemo.value = ''; await load()
  } catch (e) { toast.error(e.message || '처리 실패') } finally { working.value = false }
}

const fmtTime = fmtDateTime
</script>

<template>
  <div class="mx-auto flex min-h-full max-w-md flex-col touch-manipulation bg-slate-50">
    <header class="sticky top-0 z-10 flex items-center justify-between bg-brand-600 px-4 py-3 text-white">
      <span class="text-sm font-semibold">엠파크 입·출고</span>
      <div class="flex items-center gap-2">
        <span v-if="auth.isLoggedIn" class="text-xs text-white/70">{{ auth.displayName }}</span>
        <button v-if="auth.isLoggedIn" class="text-xs text-white/80 hover:underline" @click="doLogout">로그아웃</button>
      </div>
    </header>

    <div v-if="!auth.isLoggedIn" class="flex flex-1 flex-col items-center justify-center p-6">
      <div class="w-full max-w-sm">
        <div class="mb-5 text-center">
          <div class="mx-auto mb-3 flex h-14 w-14 items-center justify-center rounded-2xl bg-brand-600 text-2xl font-bold text-white">M</div>
          <h1 class="text-lg font-bold text-slate-800">입·출고 로그인</h1>
          <p class="mt-1 text-xs text-slate-400">QR 스캔: <span class="font-mono">{{ route.params.code }}</span></p>
        </div>
        <form class="card space-y-3 p-5" @submit.prevent="doLogin">
          <div><label class="label">이메일</label><input v-model="loginEmail" type="email" class="input" autocomplete="username" /></div>
          <div><label class="label">비밀번호</label><input v-model="loginPw" type="password" class="input" autocomplete="current-password" /></div>
          <div class="flex items-center justify-between pt-0.5 text-sm text-slate-600">
            <label class="flex cursor-pointer items-center gap-1.5"><input v-model="rememberId" type="checkbox" class="h-4 w-4 rounded border-slate-300" /> 아이디저장</label>
            <label class="flex cursor-pointer items-center gap-1.5"><input v-model="autoLogin" type="checkbox" class="h-4 w-4 rounded border-slate-300" /> 자동로그인</label>
          </div>
          <button class="btn-primary mt-1 w-full py-3" :disabled="loginLoading">{{ loginLoading ? '로그인 중…' : '로그인' }}</button>
        </form>
      </div>
    </div>

    <div v-else-if="loading" class="flex flex-1 items-center justify-center text-sm text-slate-400">불러오는 중…</div>
    <div v-else-if="!sku" class="flex flex-1 flex-col items-center justify-center gap-2 p-8 text-center">
      <p class="text-slate-500">SKU를 찾을 수 없습니다.</p>
      <p class="font-mono text-xs text-slate-400">{{ route.params.code }}</p>
      <button class="btn-primary mt-2" @click="load">다시 시도</button>
    </div>

    <div v-else class="flex-1 space-y-4 p-4">
      <p class="text-xs text-slate-400">{{ sku.pathLabel }}</p>

      <div class="card overflow-hidden">
        <img :src="imageUrl" class="h-44 w-full cursor-zoom-in bg-slate-50 object-cover" alt="상품 이미지" @click="imgOpen = true" />
        <div class="px-5 pt-4">
          <p class="font-mono text-lg font-bold text-slate-800">{{ sku.code }}</p>
          <p class="text-sm text-slate-600">{{ sku.productName }}</p>
          <p v-if="attrLine" class="mt-0.5 text-xs text-slate-400">{{ attrLine }}</p>
        </div>
        <div class="my-4 text-center">
          <p class="text-xs font-medium text-slate-400">전체 재고 (전 위치 합)</p>
          <p class="text-5xl font-extrabold" :class="totalQty > 0 ? 'text-brand-600' : 'text-rose-500'">{{ totalQty }}<span class="ml-1 text-lg text-slate-400">개</span></p>
        </div>
      </div>

      <!-- 위치별 재고행 (선택) -->
      <div class="card p-3">
        <p class="mb-2 px-1 text-xs font-semibold text-slate-500">위치별 재고 <span class="text-slate-400">— 출고/조정/교체는 위치를 선택하세요</span></p>
        <div v-if="!stockRows.length" class="p-4 text-center text-sm text-slate-400">이 SKU의 재고가 아직 없습니다. 입고로 등록하세요.</div>
        <button v-for="st in stockRows" :key="st.stockId" class="flex w-full items-center justify-between rounded-lg px-3 py-2.5 text-left"
          :class="selectedStockId === st.stockId ? 'bg-brand-50 ring-1 ring-brand-200' : 'hover:bg-slate-50'" @click="selectedStockId = st.stockId">
          <span class="min-w-0">
            <span class="block text-sm font-medium text-slate-700">📍 {{ st.complexName }}<span v-if="st.locationLabel" class="text-slate-500"> › {{ st.locationLabel }}</span></span>
            <span v-if="st.storageLocationCode" class="font-mono text-[11px] text-slate-400">{{ st.storageLocationCode }}</span>
          </span>
          <span class="shrink-0 text-lg font-bold" :class="st.qty <= 0 ? 'text-rose-500' : 'text-slate-700'">{{ st.qty }}<span class="text-xs text-slate-400">개</span></span>
        </button>
      </div>

      <!-- 입/출고 (권한자) -->
      <template v-if="auth.canStock">
        <div v-if="!mode" class="grid grid-cols-2 gap-3">
          <button class="flex flex-col items-center justify-center gap-2 rounded-xl bg-emerald-600 py-9 text-white hover:bg-emerald-700" @click="pickMode('in')">
            <svg class="h-10 w-10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v10m0 0l4-4m-4 4l-4-4M4 17v2a2 2 0 002 2h12a2 2 0 002-2v-2"/></svg>
            <span class="text-lg font-bold">입고</span>
          </button>
          <button class="flex flex-col items-center justify-center gap-2 rounded-xl bg-sky-600 py-9 text-white hover:bg-sky-700" @click="pickMode('out')">
            <svg class="h-10 w-10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 13V3m0 0l4 4m-4-4l-4 4M4 17v2a2 2 0 002 2h12a2 2 0 002-2v-2"/></svg>
            <span class="text-lg font-bold">출고</span>
          </button>
        </div>

        <!-- 입고: 위치 필수 -->
        <div v-else-if="mode === 'in'" class="card p-4">
          <div class="mb-3 flex items-center justify-between"><p class="text-sm font-semibold text-emerald-700">입고 (위치 필수)</p><button class="btn-ghost btn-sm" @click="mode = ''">↺ 재선택</button></div>
          <select v-model="inComplex" class="input mb-2" @change="onInComplex">
            <option value="">단지 선택</option>
            <option v-for="c in complexChoices" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
          <div class="grid grid-cols-2 gap-2">
            <select v-model="inZone" class="input" :disabled="!inComplex" @change="onInZone"><option value="">구역 전체</option><option v-for="z in zoneChoices" :key="z.id" :value="z.id">{{ z.name }}</option></select>
            <select v-model="inSub" class="input" :disabled="!inZone"><option value="">상세구역 전체</option><option v-for="s in subChoices" :key="s.id" :value="s.id">{{ s.name }}</option></select>
          </div>
          <select v-model="inLoc" class="input mt-2"><option value="">보관위치 선택 *</option><option v-for="l in locOptions" :key="l.id" :value="l.id">{{ l.code }} · {{ [l.zoneName, l.subZoneName, l.name].filter(Boolean).join(' › ') || '단지 전체' }}</option></select>
          <div class="mt-3 flex items-stretch gap-2">
            <button class="btn-ghost h-14 w-20 shrink-0 text-3xl" @click="inQty = Math.max(1, inQty - 1)">－</button>
            <input v-model.number="inQty" type="number" min="1" class="input h-14 flex-1 text-center text-2xl font-bold" />
            <button class="btn-ghost h-14 w-20 shrink-0 text-3xl" @click="inQty++">＋</button>
          </div>
          <select v-model="inReason" class="input mt-3"><option value="">사유 / 구분 선택 *</option><option v-for="r in IN_REASONS" :key="r" :value="r">{{ r }}</option></select>
          <input v-if="inReason === '기타'" v-model="inMemo" class="input mt-2" placeholder="상세 사유" />
          <button class="btn mt-3 w-full bg-emerald-600 py-3 text-white hover:bg-emerald-700" :disabled="working" @click="doInbound">입고 +{{ inQty }}</button>
        </div>

        <!-- 출고: 선택 재고행 -->
        <div v-else-if="mode === 'out'" class="card p-4">
          <div class="mb-3 flex items-center justify-between"><p class="text-sm font-semibold text-sky-700">출고</p><button class="btn-ghost btn-sm" @click="mode = ''">↺ 재선택</button></div>
          <p v-if="!selectedStock" class="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-700">위 "위치별 재고"에서 출고할 위치를 먼저 선택하세요.</p>
          <template v-else>
            <p class="mb-2 text-xs text-slate-500">출고 위치: <b>{{ selectedStock.complexName }}<span v-if="selectedStock.locationLabel"> › {{ selectedStock.locationLabel }}</span></b> (현재 {{ selectedStock.qty }}개)</p>
            <div class="flex items-stretch gap-2">
              <button class="btn-ghost h-14 w-20 shrink-0 text-3xl" @click="outQty = Math.max(1, outQty - 1)">－</button>
              <input v-model.number="outQty" type="number" min="1" class="input h-14 flex-1 text-center text-2xl font-bold" />
              <button class="btn-ghost h-14 w-20 shrink-0 text-3xl" @click="outQty++">＋</button>
            </div>
            <select v-model="outReason" class="input mt-3" @change="onOutReason"><option value="">사유 / 구분 선택 *</option><option v-for="r in OUT_REASONS" :key="r" :value="r">{{ r }}</option></select>
            <input v-if="outReason === '기타'" v-model="outMemo" class="input mt-2" placeholder="상세 사유" />

            <!-- 출고 상세 -->
            <div class="mt-3 space-y-2 rounded-lg border border-slate-200 p-3">
              <p class="text-xs font-semibold text-slate-500">출고 상세</p>
              <div v-if="showUsage">
                <label class="label">사용처 <span class="font-normal text-slate-400">(사용처/공용)</span></label>
                <select v-model="outComplex" class="input" @change="onOutComplex"><option value="">단지 선택</option><option v-for="c in outComplexChoices" :key="c.id" :value="c.id">{{ c.name }}</option></select>
                <div class="mt-2 grid grid-cols-2 gap-2">
                  <select v-model="outZone" class="input" :disabled="!outComplex" @change="onOutZone"><option value="">구역 선택</option><option v-for="z in outZoneChoices" :key="z.id" :value="z.id">{{ z.name }}</option></select>
                  <select v-model="outSub" class="input" :disabled="!outZone"><option value="">상세구역(선택)</option><option v-for="sz in outSubChoices" :key="sz.id" :value="sz.id">{{ sz.name }}</option></select>
                </div>
                <p v-if="outComplex && !outZoneChoices.length" class="mt-1 text-[11px] text-amber-600">사용처/공용 구역이 없습니다. 위치코드관리에서 지정하세요.</p>
              </div>
              <input v-model="requestDept" class="input" placeholder="요청부서 (예: 시설관리팀)" />
              <div class="grid grid-cols-2 gap-2">
                <input v-model="requester" class="input" placeholder="요청자" />
                <input v-model="handler" class="input" placeholder="담당자" />
              </div>
            </div>

            <button class="btn mt-3 w-full bg-sky-600 py-3 text-white hover:bg-sky-700" :disabled="working || selectedStock.qty < outQty" @click="doOutbound">출고 -{{ outQty }}</button>
          </template>
        </div>
      </template>
      <div v-else class="card p-4 text-center text-sm text-slate-400">입·출고 권한이 없습니다. 관리자에게 문의하세요.</div>

      <!-- 조정/실사 (관리자) -->
      <div v-if="auth.isAdmin && selectedStock" class="card p-4">
        <p class="mb-2 text-sm font-semibold text-slate-700">재고조정 / 실사 <span class="badge bg-slate-100 text-[10px] text-slate-400">관리자</span></p>
        <p class="mb-2 text-xs text-slate-500">대상: <b>{{ selectedStock.complexName }}<span v-if="selectedStock.locationLabel"> › {{ selectedStock.locationLabel }}</span></b> (현재 {{ selectedStock.qty }}개)</p>
        <div class="flex items-center gap-2"><span class="text-sm text-slate-400">목표 수량</span><input v-model.number="setQty" type="number" min="0" class="input w-24 text-center" /></div>
        <select v-model="adjReason" class="input mt-3"><option value="">사유 / 구분 선택 *</option><option v-for="r in ADJUST_REASONS" :key="r" :value="r">{{ r }}</option></select>
        <input v-if="adjReason === '기타'" v-model="adjMemo" class="input mt-2" placeholder="상세 사유" />
        <div class="mt-3 grid grid-cols-2 gap-2">
          <button class="btn bg-amber-500 py-3 text-white hover:bg-amber-600" :disabled="working" @click="doAdjust('adjust', '재고조정')">조정 = {{ setQty }}</button>
          <button class="btn bg-violet-600 py-3 text-white hover:bg-violet-700" :disabled="working" @click="doAdjust('audit', '재고실사')">실사 = {{ setQty }}</button>
        </div>
      </div>

      <!-- 연한 -->
      <div v-if="sku.lifecycleEnabled && selectedStock" class="card p-4">
        <div class="flex items-center justify-between">
          <p class="text-sm font-semibold text-slate-700">연한(주기 교체) · {{ selectedStock.complexName }}</p>
          <span class="badge text-white" :class="lifeMeta[lifeStatus]?.c">{{ lifeMeta[lifeStatus]?.t }}<span v-if="lifeText"> · {{ lifeText }}</span></span>
        </div>
        <div class="mt-2 grid grid-cols-2 gap-px overflow-hidden rounded-lg bg-slate-100 text-center text-sm">
          <div class="bg-white py-2"><p class="text-xs text-slate-400">다음 교체예정</p><p class="font-semibold text-slate-700">{{ fmtDate(selectedStock.nextReplaceAt) || '—' }}</p></div>
          <div class="bg-white py-2"><p class="text-xs text-slate-400">최근 교체일</p><p class="font-semibold text-slate-700">{{ fmtDate(selectedStock.lastReplacedAt) || '—' }}</p></div>
        </div>
        <button class="btn mt-3 w-full bg-violet-600 py-3 text-white hover:bg-violet-700" :disabled="working" @click="doReplace">교체 완료 처리</button>
      </div>

      <!-- 이력 -->
      <div v-if="movements.length" class="card p-4">
        <div class="mb-2 flex items-center justify-between">
          <p class="text-xs font-semibold text-slate-500">최근 처리 이력</p>
          <button class="flex h-6 w-6 items-center justify-center rounded-full bg-slate-100 text-xs font-bold text-slate-500" @click="helpOpen = true">?</button>
        </div>
        <ul class="max-h-72 divide-y divide-slate-50 overflow-y-auto scrollbar-slim text-sm">
          <li v-for="m in movements" :key="m.id" class="flex items-center justify-between gap-2 py-2">
            <span class="flex min-w-0 flex-wrap items-center gap-1.5">
              <span class="badge text-[10px]" :class="m.type === 'void' ? 'bg-rose-50 text-rose-600' : 'bg-slate-100'">{{ typeLabel[m.type] }}</span>
              <span v-if="m.voided" class="badge bg-slate-100 text-[10px] text-slate-400 line-through">취소됨</span>
              <span class="text-slate-600">{{ m.byName }}</span>
              <span v-if="m.complexName" class="text-[11px] text-slate-400">· {{ m.complexName }}</span>
              <span v-if="m.reason" class="text-xs text-slate-400">· {{ m.reason }}</span>
            </span>
            <span class="flex shrink-0 items-center gap-2">
              <span class="text-xs text-slate-400">{{ m.before }}→{{ m.after }}개 · {{ fmtTime(m.at) }}</span>
              <button v-if="canVoid(m)" class="rounded-md border border-rose-200 px-2 py-0.5 text-[11px] font-medium text-rose-600 hover:bg-rose-50" @click="openVoid(m)">취소</button>
            </span>
          </li>
        </ul>
      </div>
    </div>

    <BaseModal :model-value="!!voidTarget" size="sm" title="처리 취소" @update:model-value="voidTarget = null">
      <div v-if="voidTarget" class="space-y-3">
        <div class="rounded-lg bg-slate-50 p-3 text-sm">
          <p><span class="badge bg-slate-100 text-[10px]">{{ typeLabel[voidTarget.type] }}</span> <b>{{ voidTarget.qty }}개</b> · {{ voidTarget.skuCode }}</p>
          <p class="mt-1 text-xs text-slate-500">{{ voidTarget.before }}→{{ voidTarget.after }}개 · {{ fmtTime(voidTarget.at) }}</p>
        </div>
        <select v-model="voidReason" class="input"><option value="">취소 사유 선택 *</option><option v-for="r in VOID_REASONS" :key="r" :value="r">{{ r }}</option></select>
        <input v-if="voidReason === '기타'" v-model="voidMemo" class="input" placeholder="상세 사유" />
      </div>
      <template #footer>
        <button class="btn-ghost" :disabled="voiding" @click="voidTarget = null">닫기</button>
        <button class="btn bg-rose-600 text-white hover:bg-rose-700" :disabled="voiding" @click="confirmVoid">{{ voiding ? '취소 중…' : '취소 확정' }}</button>
      </template>
    </BaseModal>

    <BaseModal v-model="helpOpen" size="sm" title="처리 취소 안내">
      <div class="space-y-3 text-sm text-slate-600">
        <p>현장에서 <b>본인이 등록한 당일 처리</b>만 직접 취소할 수 있습니다.</p>
        <p class="rounded-lg bg-amber-50 p-3 text-amber-700">그 외에는 <b>관리자에게 재고 정정을 요청</b>하세요.</p>
      </div>
      <template #footer><button class="btn-primary" @click="helpOpen = false">확인</button></template>
    </BaseModal>

    <Teleport to="body">
      <div v-if="imgOpen" class="fixed inset-0 z-[60] flex items-center justify-center bg-black/85 p-3" @click="imgOpen = false">
        <img :src="imageUrl" class="max-h-[94vh] max-w-[96vw] rounded-lg object-contain" alt="상품 이미지" />
      </div>
    </Teleport>

    <ConfirmDialog ref="confirm" />
  </div>
</template>

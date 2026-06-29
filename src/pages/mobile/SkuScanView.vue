<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { skus, products, storageLocations, applyStock, listMovements, replaceLifecycle, voidMovement } from '@/services/db'
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
const sku = ref(null)
const product = ref(null)
const movements = ref([])
const working = ref(false)
const imgOpen = ref(false) // 상품 사진 전체보기

// QR 전용 미니 로그인 (백오피스 로그인과 분리, 회원가입 없음)
const EMAIL_KEY = 'mpark.savedEmail'
const loginEmail = ref('')
const loginPw = ref('')
const loginLoading = ref(false)
const rememberId = ref(true)   // 아이디저장
const autoLogin = ref(true)    // 자동로그인
// 저장된 아이디/옵션 복원
try {
  const e = localStorage.getItem(EMAIL_KEY)
  if (e) { loginEmail.value = e; rememberId.value = true } else { rememberId.value = false }
  autoLogin.value = getAutoLogin()
} catch (e) { /* ignore */ }

const imageUrl = computed(() => resolveImage(sku.value, product.value))

// 사유/구분 (백오피스 StockOpView 와 동일) — 필수, '기타' 일 때만 메모 입력
const IN_REASONS = ['구매입고', '반품입고', '이동입고', '재고보충', '생산입고', '기타']
const OUT_REASONS = ['판매/사용', '폐기', '반품출고', '이동출고', '샘플/전시', '기타']
const ADJUST_REASONS = ['위치 지정', '위치 지정 변경', '파손', '분실', '도난', '오입력 정정', '유통기한 경과', '입고 오류', '기타']

// 입/출고 모드: '' = 선택화면, 'in' = 입고, 'out' = 출고
const mode = ref('')
// 입고
const inQty = ref(1)
const inReason = ref('')
const inMemo = ref('')
// 출고
const outQty = ref(1)
const outReason = ref('')
const outMemo = ref('')
// 조정/실사 (관리자)
const setQty = ref(0)
const adjReason = ref('')
const adjMemo = ref('')

// 입고/출고 진입 — 누를 때마다 수량·사유 초기화
function pickMode(m) {
  mode.value = m
  if (m === 'in') { inQty.value = 1; inReason.value = ''; inMemo.value = '' }
  else if (m === 'out') { outQty.value = 1; outReason.value = ''; outMemo.value = '' }
}

/* ---------- 보관위치 지정 (관리자) — 단지(SKU 고정) › 구역 › 상세구역 › 보관위치 ---------- */
const storageLocs = ref([])
const locZone = ref('')
const locSub = ref('')
const locId = ref('')
const savingLoc = ref(false)

const zoneChoices = computed(() => {
  const m = new Map()
  storageLocs.value.forEach((l) => l.zoneId && m.set(l.zoneId, l.zoneName))
  return [...m].map(([id, name]) => ({ id, name }))
})
const subChoices = computed(() => {
  const m = new Map()
  storageLocs.value.forEach((l) => { if (l.zoneId === locZone.value && l.subZoneId) m.set(l.subZoneId, l.subZoneName) })
  return [...m].map(([id, name]) => ({ id, name }))
})
const locOptions = computed(() =>
  storageLocs.value.filter((l) => (!locZone.value || l.zoneId === locZone.value) && (!locSub.value || l.subZoneId === locSub.value))
)
function onZoneChange() {
  locSub.value = ''
  if (locId.value && !locOptions.value.find((l) => l.id === locId.value)) locId.value = ''
}
function onSubChange() {
  if (locId.value && !locOptions.value.find((l) => l.id === locId.value)) locId.value = ''
}
async function saveLocation(clear = false) {
  if (!sku.value) return
  savingLoc.value = true
  try {
    let loc = { storageLocationId: '', storageLocationCode: '', zoneId: '', zoneName: '', subZoneId: '', subZoneName: '', locationLabel: '' }
    if (!clear) {
      const sl = storageLocs.value.find((x) => x.id === locId.value)
      if (!sl) { savingLoc.value = false; return toast.error('보관위치를 선택하세요.') }
      loc = {
        storageLocationId: sl.id,
        storageLocationCode: sl.code,
        zoneId: sl.zoneId || '',
        zoneName: sl.zoneName || '',
        subZoneId: sl.subZoneId || '',
        subZoneName: sl.subZoneName || '',
        locationLabel: sl.locationLabel || sl.name || '',
      }
    } else {
      locId.value = ''
      locZone.value = ''
      locSub.value = ''
    }
    await skus.setLocation(sku.value.id, loc)
    sku.value = { ...sku.value, ...loc }
    toast.success(clear ? '위치가 삭제되었습니다.' : '위치가 저장되었습니다.')
  } catch (e) {
    toast.error('위치 저장 실패: ' + (e.message || e.code))
  } finally {
    savingLoc.value = false
  }
}

function krError(e) {
  const m = (e?.message || '').toLowerCase()
  if (m.includes('invalid login')) return '이메일 또는 비밀번호가 올바르지 않습니다.'
  if (m.includes('not confirmed') || m.includes('confirm')) return '이메일 인증이 필요합니다.'
  if (m.includes('rate limit') || m.includes('too many')) return '잠시 후 다시 시도해주세요.'
  return e?.message || '로그인에 실패했습니다.'
}

async function doLogin() {
  const em = (loginEmail.value || '').trim().replace(/\s/g, '')
  const pw = loginPw.value || ''
  if (!em || !pw) {
    toast.error('이메일과 비밀번호를 입력하세요.')
    return
  }
  // 옵션 적용 (로그인 직전: 세션 토큰이 알맞은 저장소에 기록되도록)
  setAutoLogin(autoLogin.value)
  try {
    if (rememberId.value) localStorage.setItem(EMAIL_KEY, em)
    else localStorage.removeItem(EMAIL_KEY)
  } catch (e) { /* ignore */ }
  loginLoading.value = true
  try {
    await auth.login(em, pw)
    loginPw.value = ''
    await load()
  } catch (e) {
    toast.error(krError(e))
  } finally {
    loginLoading.value = false
  }
}

async function doLogout() {
  await auth.logout()
  sku.value = null
  product.value = null
  movements.value = []
}

async function load() {
  if (!auth.isLoggedIn) return
  loading.value = true
  try {
    sku.value = await skus.getByCode(decodeURIComponent(route.params.code))
    if (sku.value) {
      setQty.value = sku.value.qty
      // 상품 대표이미지 폴백을 위해 상품도 조회 (최신값 우선)
      product.value = sku.value.productId ? await products.get(sku.value.productId) : null
      movements.value = await listMovements(sku.value.id, 20)
      // 보관위치 지정(관리자) — 현재 위치 사전 세팅 + 단지 내 보관위치 목록
      if (auth.isAdmin) {
        locZone.value = sku.value.zoneId || ''
        locSub.value = sku.value.subZoneId || ''
        locId.value = sku.value.storageLocationId || ''
        storageLocs.value = sku.value.complexId ? await storageLocations.listByComplex(sku.value.complexId) : []
      }
    }
  } catch (e) {
    toast.error('조회 실패: ' + (e.message || e.code))
  } finally {
    loading.value = false
  }
}
// 라우트가 public 이라 가드가 세션을 복원하지 않으므로 여기서 직접 init 후 로드
onMounted(async () => {
  if (!auth.ready) await auth.init()
  await load()
})

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
const typeLabel = { in: '입고', out: '출고', adjust: '조정', audit: '실사', void: '취소' }

/* ---------- 처리 취소(역분개) ---------- */
const VOID_REASONS = ['수량 오기입', '방향 오선택(입출고 바뀜)', '중복 처리', '기타']
const voidTarget = ref(null)
const voidReason = ref('')
const voidMemo = ref('')
const voiding = ref(false)
const helpOpen = ref(false)

function isToday(at) {
  if (!at) return false
  const d = new Date(at)
  const now = new Date()
  return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
}
// 본인 등록 + 당일 + 미취소 + 취소전표 아님 → 현장 취소 가능
function canVoid(m) {
  return m.byUserId === auth.user?.id && m.type !== 'void' && !m.voided && isToday(m.at)
}
function openVoid(m) {
  voidTarget.value = m
  voidReason.value = ''
  voidMemo.value = ''
}
async function confirmVoid() {
  if (!voidTarget.value) return
  if (!voidReason.value) {
    toast.error('취소 사유를 선택하세요.')
    return
  }
  const reasonText = voidReason.value === '기타' ? (voidMemo.value || '').trim() : voidReason.value
  if (voidReason.value === '기타' && !reasonText) {
    toast.error('취소 사유를 입력하세요.')
    return
  }
  voiding.value = true
  try {
    await voidMovement(voidTarget.value.id, reasonText)
    toast.success('처리를 취소했습니다.')
    voidTarget.value = null
    await load()
  } catch (e) {
    toast.error(e.message || '취소 실패')
  } finally {
    voiding.value = false
  }
}

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

/**
 * 입고/출고/조정/실사 공통 처리. 사유 필수, '기타' 일 때만 메모 사용 (백오피스와 동일).
 */
async function run(type, value, label, reason, memoText) {
  if (!reason) {
    toast.error('사유를 선택하세요.')
    return
  }
  const v = Number(value)
  if (!Number.isFinite(v) || v < 0) {
    toast.error('수량을 올바르게 입력하세요.')
    return
  }
  if ((type === 'in' || type === 'out') && v <= 0) {
    toast.error('수량은 1 이상이어야 합니다.')
    return
  }
  const ok = await confirm.value.ask({
    title: `${label} 처리`,
    message: `SKU ${sku.value.code}\n사유: ${reason}\n${label} 진행할까요?`,
    confirmText: label,
  })
  if (!ok) return
  working.value = true
  try {
    const memoVal = reason === '기타' ? (memoText || '') : ''
    const r = await applyStock(sku.value.id, type, v, auth.actor, memoVal, reason)
    toast.success(`${label} 완료 · 재고 ${r.before}→${r.after}개`)
    await load()
    // 성공 후 수량/메모만 초기화 (사유는 연속 작업 편의를 위해 유지)
    if (type === 'in') { inQty.value = 1; inMemo.value = '' }
    else if (type === 'out') { outQty.value = 1; outMemo.value = '' }
    else { adjMemo.value = '' }
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
      <span class="text-sm font-semibold">엠파크 입·출고</span>
      <div class="flex items-center gap-2">
        <span v-if="auth.isLoggedIn" class="text-xs text-white/70">{{ auth.displayName }}</span>
        <button v-if="auth.isLoggedIn" class="text-xs text-white/80 underline-offset-2 hover:underline" @click="doLogout">로그아웃</button>
      </div>
    </header>

    <!-- 미로그인: QR 전용 미니 로그인 -->
    <div v-if="!auth.isLoggedIn" class="flex flex-1 flex-col items-center justify-center p-6">
      <div class="w-full max-w-sm">
        <div class="mb-5 text-center">
          <div class="mx-auto mb-3 flex h-14 w-14 items-center justify-center rounded-2xl bg-brand-600 text-2xl font-bold text-white">M</div>
          <h1 class="text-lg font-bold text-slate-800">입·출고 로그인</h1>
          <p class="mt-1 text-xs text-slate-400">QR 스캔: <span class="font-mono">{{ route.params.code }}</span></p>
        </div>
        <form class="card space-y-3 p-5" @submit.prevent="doLogin">
          <div>
            <label class="label">이메일</label>
            <input v-model="loginEmail" type="email" class="input" placeholder="you@m-park.co.kr" autocomplete="username" />
          </div>
          <div>
            <label class="label">비밀번호</label>
            <input v-model="loginPw" type="password" class="input" placeholder="••••••••" autocomplete="current-password" />
          </div>
          <div class="flex items-center justify-between pt-0.5 text-sm text-slate-600">
            <label class="flex cursor-pointer items-center gap-1.5">
              <input v-model="rememberId" type="checkbox" class="h-4 w-4 rounded border-slate-300 text-brand-600" />
              아이디저장
            </label>
            <label class="flex cursor-pointer items-center gap-1.5">
              <input v-model="autoLogin" type="checkbox" class="h-4 w-4 rounded border-slate-300 text-brand-600" />
              자동로그인
            </label>
          </div>
          <button class="btn-primary mt-1 w-full py-3" :disabled="loginLoading">{{ loginLoading ? '로그인 중…' : '로그인' }}</button>
          <p class="text-center text-[11px] leading-relaxed text-slate-400">입·출고 권한이 있는 계정으로 로그인하세요.<br />계정/권한은 관리자에게 문의하세요.</p>
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
      <div class="flex flex-wrap items-center gap-2">
        <span class="badge bg-brand-50 text-brand-700">📍 {{ sku.locationLabel ? sku.complexName + ' › ' + sku.locationLabel : (sku.complexName || '위치 미지정') }}</span>
        <span v-if="sku.storageLocationCode" class="font-mono text-[11px] text-slate-400">{{ sku.storageLocationCode }}</span>
      </div>

      <div class="card overflow-hidden">
        <img :src="imageUrl" class="h-44 w-full cursor-zoom-in bg-slate-50 object-cover" alt="상품 이미지" title="사진 크게 보기" @click="imgOpen = true" />
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

      <!-- 입/출고 (권한자만) -->
      <template v-if="auth.canStock">
        <!-- 작업 선택 -->
        <div v-if="!mode" class="grid grid-cols-2 gap-3">
          <button class="flex flex-col items-center justify-center gap-2 rounded-xl py-9 text-white shadow-sm bg-emerald-600 hover:bg-emerald-700" @click="pickMode('in')">
            <svg class="h-10 w-10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v10m0 0l4-4m-4 4l-4-4M4 17v2a2 2 0 002 2h12a2 2 0 002-2v-2"/></svg>
            <span class="text-lg font-bold">입고</span>
          </button>
          <button class="flex flex-col items-center justify-center gap-2 rounded-xl py-9 text-white shadow-sm bg-sky-600 hover:bg-sky-700" @click="pickMode('out')">
            <svg class="h-10 w-10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 13V3m0 0l4 4m-4-4l-4 4M4 17v2a2 2 0 002 2h12a2 2 0 002-2v-2"/></svg>
            <span class="text-lg font-bold">출고</span>
          </button>
        </div>

        <!-- 입고 -->
        <div v-else-if="mode === 'in'" class="card p-4">
          <div class="mb-3 flex items-center justify-between">
            <p class="text-sm font-semibold text-emerald-700">입고</p>
            <button class="btn-ghost btn-sm" @click="mode = ''">↺ 입/출고 재선택</button>
          </div>
          <div class="flex items-stretch gap-2">
            <button class="btn-ghost h-14 w-20 shrink-0 text-3xl font-light leading-none" @click="inQty = Math.max(1, inQty - 1)">－</button>
            <input v-model.number="inQty" type="number" min="1" class="input h-14 flex-1 text-center text-2xl font-bold" />
            <button class="btn-ghost h-14 w-20 shrink-0 text-3xl font-light leading-none" @click="inQty++">＋</button>
          </div>
          <select v-model="inReason" class="input mt-3">
            <option value="">사유 / 구분 선택 *</option>
            <option v-for="r in IN_REASONS" :key="r" :value="r">{{ r }}</option>
          </select>
          <input v-if="inReason === '기타'" v-model="inMemo" class="input mt-2" placeholder="거래처명 또는 상세 사유" />
          <button class="btn mt-3 w-full py-3 text-white bg-emerald-600 hover:bg-emerald-700" :disabled="working" @click="run('in', inQty, '입고', inReason, inMemo)">입고 +{{ inQty }}</button>
        </div>

        <!-- 출고 -->
        <div v-else-if="mode === 'out'" class="card p-4">
          <div class="mb-3 flex items-center justify-between">
            <p class="text-sm font-semibold text-sky-700">출고</p>
            <button class="btn-ghost btn-sm" @click="mode = ''">↺ 입/출고 재선택</button>
          </div>
          <div class="flex items-stretch gap-2">
            <button class="btn-ghost h-14 w-20 shrink-0 text-3xl font-light leading-none" @click="outQty = Math.max(1, outQty - 1)">－</button>
            <input v-model.number="outQty" type="number" min="1" class="input h-14 flex-1 text-center text-2xl font-bold" />
            <button class="btn-ghost h-14 w-20 shrink-0 text-3xl font-light leading-none" @click="outQty++">＋</button>
          </div>
          <select v-model="outReason" class="input mt-3">
            <option value="">사유 / 구분 선택 *</option>
            <option v-for="r in OUT_REASONS" :key="r" :value="r">{{ r }}</option>
          </select>
          <input v-if="outReason === '기타'" v-model="outMemo" class="input mt-2" placeholder="거래처명 또는 상세 사유" />
          <button class="btn mt-3 w-full py-3 text-white bg-sky-600 hover:bg-sky-700" :disabled="working || sku.qty < outQty" @click="run('out', outQty, '출고', outReason, outMemo)">출고 -{{ outQty }}</button>
        </div>
      </template>
      <div v-else class="card p-4 text-center text-sm text-slate-400">
        입·출고 권한이 없습니다. 관리자에게 문의하세요.
      </div>

      <!-- 조정 / 실사 (관리자) -->
      <div v-if="auth.isAdmin" class="card p-4">
        <p class="mb-2 text-sm font-semibold text-slate-700">재고조정 / 실사 <span class="badge bg-slate-100 text-[10px] text-slate-400">관리자</span></p>
        <div class="flex items-center gap-2">
          <span class="text-sm text-slate-400">목표 수량</span>
          <input v-model.number="setQty" type="number" min="0" class="input w-24 text-center" />
        </div>
        <select v-model="adjReason" class="input mt-3">
          <option value="">사유 / 구분 선택 *</option>
          <option v-for="r in ADJUST_REASONS" :key="r" :value="r">{{ r }}</option>
        </select>
        <input v-if="adjReason === '기타'" v-model="adjMemo" class="input mt-2" placeholder="상세 사유" />
        <div class="mt-3 grid grid-cols-2 gap-2">
          <button class="btn py-3 text-white bg-amber-500 hover:bg-amber-600" :disabled="working" @click="run('adjust', setQty, '재고조정', adjReason, adjMemo)">조정 = {{ setQty }}</button>
          <button class="btn py-3 text-white bg-violet-600 hover:bg-violet-700" :disabled="working" @click="run('audit', setQty, '재고실사', adjReason, adjMemo)">실사 = {{ setQty }}</button>
        </div>
      </div>

      <!-- 보관위치 지정 (관리자) -->
      <div v-if="auth.isAdmin" class="card p-4">
        <p class="mb-2 text-sm font-semibold text-slate-700">보관위치 지정 <span class="badge bg-slate-100 text-[10px] text-slate-400">관리자</span></p>
        <div class="mb-2 rounded bg-slate-50 px-2 py-1 text-[11px] text-slate-500">단지: <b>{{ sku.complexName }}</b> <span class="text-slate-400">(SKU 기준 고정)</span></div>
        <div class="grid grid-cols-2 gap-2">
          <select v-model="locZone" class="input" @change="onZoneChange">
            <option value="">구역 전체</option>
            <option v-for="z in zoneChoices" :key="z.id" :value="z.id">{{ z.name }}</option>
          </select>
          <select v-model="locSub" class="input" :disabled="!locZone" @change="onSubChange">
            <option value="">상세구역 전체</option>
            <option v-for="s in subChoices" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
        </div>
        <select v-model="locId" class="input mt-2">
          <option value="">보관위치 선택</option>
          <option v-for="l in locOptions" :key="l.id" :value="l.id">
            {{ l.code }} · {{ [l.zoneName, l.subZoneName, l.name].filter(Boolean).join(' › ') || '단지 전체' }}
          </option>
        </select>
        <p v-if="!storageLocs.length" class="mt-1 text-[11px] text-amber-600">이 단지에 등록된 보관위치가 없습니다. 보관위치관리에서 먼저 등록하세요.</p>
        <div class="mt-2 flex gap-2">
          <button class="btn-ghost btn-sm flex-1" :disabled="savingLoc" @click="saveLocation(false)">위치 저장</button>
          <button class="btn-ghost btn-sm text-rose-600" :disabled="savingLoc || !sku.storageLocationId" @click="saveLocation(true)">위치 삭제</button>
        </div>
        <p v-if="sku.locationLabel || sku.storageLocationCode" class="mt-1.5 text-[11px] text-slate-400">
          현재 위치: {{ sku.complexName }}<span v-if="sku.locationLabel"> › {{ sku.locationLabel }}</span>
          <span v-if="sku.storageLocationCode" class="font-mono"> ({{ sku.storageLocationCode }})</span>
        </p>
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
        <div class="mb-2 flex items-center justify-between">
          <p class="text-xs font-semibold text-slate-500">최근 처리 이력</p>
          <button
            class="flex h-6 w-6 items-center justify-center rounded-full bg-slate-100 text-xs font-bold text-slate-500 hover:bg-slate-200"
            title="취소 안내"
            @click="helpOpen = true"
          >?</button>
        </div>
        <ul class="max-h-72 divide-y divide-slate-50 overflow-y-auto scrollbar-slim text-sm">
          <li v-for="m in movements" :key="m.id" class="flex items-center justify-between gap-2 py-2">
            <span class="flex min-w-0 flex-wrap items-center gap-1.5">
              <span class="badge text-[10px]" :class="m.type === 'void' ? 'bg-rose-50 text-rose-600' : 'bg-slate-100'">{{ typeLabel[m.type] }}</span>
              <span v-if="m.voided" class="badge bg-slate-100 text-[10px] text-slate-400 line-through">취소됨</span>
              <span class="text-slate-600">{{ m.byName }}</span>
              <span v-if="m.reason" class="text-xs text-slate-400">· {{ m.reason }}</span>
            </span>
            <span class="flex shrink-0 items-center gap-2">
              <span class="text-xs text-slate-400">{{ m.before }}→{{ m.after }}개 · {{ fmtTime(m.at) }}</span>
              <button
                v-if="canVoid(m)"
                class="rounded-md border border-rose-200 px-2 py-0.5 text-[11px] font-medium text-rose-600 hover:bg-rose-50"
                @click="openVoid(m)"
              >취소</button>
            </span>
          </li>
        </ul>
      </div>
    </div>

    <!-- 처리 취소 모달 -->
    <BaseModal :model-value="!!voidTarget" size="sm" title="처리 취소" @update:model-value="voidTarget = null">
      <div v-if="voidTarget" class="space-y-3">
        <div class="rounded-lg bg-slate-50 p-3 text-sm">
          <p><span class="badge bg-slate-100 text-[10px]">{{ typeLabel[voidTarget.type] }}</span> <b>{{ voidTarget.qty }}개</b> · {{ voidTarget.skuCode }}</p>
          <p class="mt-1 text-xs text-slate-500">{{ voidTarget.before }}→{{ voidTarget.after }}개 · {{ fmtTime(voidTarget.at) }}</p>
          <p class="mt-1 text-xs text-slate-400">취소하면 이 처리를 되돌리는 역분개가 기록되며, 원래 이력은 보존됩니다.</p>
        </div>
        <div>
          <label class="label">취소 사유 *</label>
          <select v-model="voidReason" class="input">
            <option value="">사유 선택</option>
            <option v-for="r in VOID_REASONS" :key="r" :value="r">{{ r }}</option>
          </select>
        </div>
        <input v-if="voidReason === '기타'" v-model="voidMemo" class="input" placeholder="상세 사유를 입력하세요" />
      </div>
      <template #footer>
        <button class="btn-ghost" :disabled="voiding" @click="voidTarget = null">닫기</button>
        <button class="btn bg-rose-600 text-white hover:bg-rose-700" :disabled="voiding" @click="confirmVoid">{{ voiding ? '취소 중…' : '취소 확정' }}</button>
      </template>
    </BaseModal>

    <!-- 취소 안내 팝업 -->
    <BaseModal v-model="helpOpen" size="sm" title="처리 취소 안내">
      <div class="space-y-3 text-sm text-slate-600">
        <p>현장에서 <b>본인이 등록한 당일 처리</b>만 직접 취소할 수 있습니다.</p>
        <p>다음의 경우에는 취소 버튼이 보이지 않거나 취소가 거부됩니다:</p>
        <ul class="list-disc space-y-1 pl-5 text-slate-500">
          <li>처리한 날짜가 <b>지난</b> 경우 (당일 한정)</li>
          <li>다른 담당자가 등록한 처리</li>
          <li>그 사이 <b>재고가 변동</b>되어 되돌리면 수량이 맞지 않는 경우</li>
        </ul>
        <p class="rounded-lg bg-amber-50 p-3 text-amber-700">
          이때는 <b>관리자에게 재고 정정을 요청</b>하세요. 관리자는 재고조정/실사로 바로잡을 수 있습니다.
        </p>
      </div>
      <template #footer>
        <button class="btn-primary" @click="helpOpen = false">확인</button>
      </template>
    </BaseModal>

    <!-- 상품 사진 전체보기 -->
    <Teleport to="body">
      <div v-if="imgOpen" class="fixed inset-0 z-[60] flex items-center justify-center bg-black/85 p-3" @click="imgOpen = false">
        <img :src="imageUrl" class="max-h-[94vh] max-w-[96vw] rounded-lg object-contain" alt="상품 이미지" />
        <button class="absolute right-3 top-3 rounded-full bg-white/20 p-2 text-white hover:bg-white/30" @click.stop="imgOpen = false">
          <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 6l12 12M18 6L6 18" stroke-linecap="round" /></svg>
        </button>
      </div>
    </Teleport>

    <ConfirmDialog ref="confirm" />
  </div>
</template>

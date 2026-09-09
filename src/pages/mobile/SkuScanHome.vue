<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { setAutoLogin, getAutoLogin } from '@/supabase'
import { findSimilarProducts, skus, complexes, categories, products } from '@/services/db'
import { resolveImage, NO_IMAGE } from '@/utils/image'
import jsQR from 'jsqr'

// keep-alive 대상 이름(뒤로 갈 때 상품검색 드릴다운 상태 보존)
defineOptions({ name: 'SkuScanHome' })

const router = useRouter()
const auth = useAuthStore()
const toast = useToast()

/* ===================== 로그인 ===================== */
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
  try { await auth.login(em, pw); loginPw.value = '' }
  catch (e) { toast.error(krError(e)) } finally { loginLoading.value = false }
}
async function doLogout() { await auth.logout(); resetPhoto() }

onMounted(async () => { if (!auth.ready) await auth.init() })

/* ===================== 제품 찾아보기 (사진 → 유사제품) ===================== */
const fileInput = ref(null)
const searching = ref(false)
const capturedImage = ref(null)
const matches = ref(null) // null=검색 전, []=결과 없음
const selectedCode = ref('')

function pickPhoto() { fileInput.value?.click() }
function onPhoto(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file) return
  const img = new Image()
  img.onload = async () => {
    const max = 1024
    let w = img.width, h = img.height
    if (w > max || h > max) { const r = Math.min(max / w, max / h); w = Math.round(w * r); h = Math.round(h * r) }
    const c = document.createElement('canvas'); c.width = w; c.height = h
    c.getContext('2d').drawImage(img, 0, 0, w, h)
    const dataUrl = c.toDataURL('image/jpeg', 0.85)
    URL.revokeObjectURL(img.src)
    capturedImage.value = dataUrl
    await runSearch(dataUrl)
  }
  img.onerror = () => toast.error('이미지를 불러오지 못했습니다.')
  img.src = URL.createObjectURL(file)
}
async function runSearch(dataUrl) {
  searching.value = true; matches.value = null; selectedCode.value = ''
  try {
    const res = await findSimilarProducts(dataUrl)
    matches.value = Array.isArray(res) ? res : []
  } catch (e) {
    toast.error(e.message || '유사 제품 검색에 실패했습니다.')
    matches.value = []
  } finally { searching.value = false }
}
function resetPhoto() { capturedImage.value = null; matches.value = null; selectedCode.value = ''; searching.value = false }
function goTo(code) { closeQr(); router.push({ name: 'scan', params: { code } }) }
function thumb(obj) { return resolveImage(obj) || NO_IMAGE }
function locSummary(m) {
  const locs = m.locations || []
  if (!locs.length) return ''
  const first = locs[0]
  const extra = locs.length > 1 ? ` 외 ${locs.length - 1}곳` : ''
  return `${first.label} · ${first.qty}개${extra}`
}

/* ===================== SKU 전체 리스트 (검색 폴백) ===================== */
const showList = ref(false)
const listSearch = ref('')
const listRows = ref([])
const listTotal = ref(0)
const listPage = ref(1)
const listLoading = ref(false)
const LIST_SIZE = 30
let searchTimer = null

async function loadList(reset = true) {
  if (reset) { listPage.value = 1; listRows.value = [] }
  listLoading.value = true
  try {
    const r = await skus.managePage({ search: listSearch.value || null, page: listPage.value, pageSize: LIST_SIZE })
    listRows.value = reset ? r.rows : [...listRows.value, ...r.rows]
    listTotal.value = r.total
  } catch (e) {
    toast.error(e.message || 'SKU 목록 조회에 실패했습니다.')
  } finally { listLoading.value = false }
}
function openList() { showList.value = true; loadList(true) }
function closeList() { showList.value = false }
function onListSearch() { clearTimeout(searchTimer); searchTimer = setTimeout(() => loadList(true), 300) }
function loadMore() { listPage.value++; loadList(false) }

/* ===================== QR 코드로 찾기 (라이브 스캔) ===================== */
const qrOpen = ref(false)
const videoEl = ref(null)
let stream = null, rafId = null, qrCanvas = null

async function openQr() {
  qrOpen.value = true
  await nextTick()
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
    if (!videoEl.value) return closeQr()
    videoEl.value.srcObject = stream
    videoEl.value.setAttribute('playsinline', 'true')
    await videoEl.value.play()
    qrCanvas = document.createElement('canvas')
    rafId = requestAnimationFrame(scanLoop)
  } catch (e) {
    toast.error('카메라를 열 수 없습니다. 브라우저 카메라 권한을 확인해주세요.')
    closeQr()
  }
}
function scanLoop() {
  const v = videoEl.value
  if (!qrOpen.value || !v) return
  if (v.readyState === v.HAVE_ENOUGH_DATA && v.videoWidth) {
    const w = v.videoWidth, h = v.videoHeight
    qrCanvas.width = w; qrCanvas.height = h
    const ctx = qrCanvas.getContext('2d', { willReadFrequently: true })
    ctx.drawImage(v, 0, 0, w, h)
    const img = ctx.getImageData(0, 0, w, h)
    const code = jsQR(img.data, w, h, { inversionAttempts: 'dontInvert' })
    if (code && code.data) { onQrDetected(code.data); return }
  }
  rafId = requestAnimationFrame(scanLoop)
}
function parseCode(text) {
  try {
    let t = String(text).trim()
    const idx = t.indexOf('/s/')
    if (idx >= 0) t = t.substring(idx + 3)
    t = t.split('?')[0].split('#')[0]
    t = decodeURIComponent(t)
    return t || null
  } catch (e) { return null }
}
function onQrDetected(text) {
  const code = parseCode(text)
  if (code) goTo(code)
  else { toast.error('QR에서 제품 코드를 읽지 못했습니다.'); }
}
function closeQr() {
  qrOpen.value = false
  if (rafId) { cancelAnimationFrame(rafId); rafId = null }
  if (stream) { stream.getTracks().forEach((t) => t.stop()); stream = null }
}
onUnmounted(closeQr)

/* ===================== 상품 검색 (단지 > 카테고리 > 상품 > SKU) ===================== */
const showSearch = ref(false)
const psComplexId = ref('')
const psCategoryId = ref('')
const psProduct = ref(null)
const psQuery = ref('')
const psComplexes = ref([])
const psCategories = ref([])
const psProducts = ref([])
const psSkus = ref([])
const psSearchResults = ref([])
const psLoading = ref(false)
let psTimer = null

const psComplexName = computed(() => psComplexes.value.find((c) => c.id === psComplexId.value)?.name || '')
const psCategoryName = computed(() => psCategories.value.find((c) => c.id === psCategoryId.value)?.name || '')
// 현재 검색 범위(단지 › 카테고리 › 상품)
const psScopeLabel = computed(() => [psComplexName.value, psCategoryId.value ? psCategoryName.value : '', psProduct.value ? psProduct.value.name : ''].filter(Boolean).join(' › '))

async function openSearch() {
  showSearch.value = true
  psComplexId.value = ''; psCategoryId.value = ''; psProduct.value = null; psQuery.value = ''
  psProducts.value = []; psSkus.value = []
  try {
    ;[psComplexes.value, psCategories.value] = await Promise.all([complexes.list(), categories.list()])
  } catch (e) { toast.error(e.message || '불러오기에 실패했습니다.') }
}
function closeSearch() { showSearch.value = false }
function psBack() {
  if (psQuery.value.trim()) { psQuery.value = ''; psSearchResults.value = []; return }
  if (psProduct.value) { psProduct.value = null; psSkus.value = []; return }
  if (psCategoryId.value) { psCategoryId.value = ''; psProducts.value = []; return }
  if (psComplexId.value) { psComplexId.value = ''; return }
  closeSearch()
}
function psPickComplex(id) {
  psComplexId.value = id
  psCategoryId.value = ''; psProduct.value = null; psProducts.value = []; psSkus.value = []; psQuery.value = ''; psSearchResults.value = []
}
async function psPickCategory(id) {
  psCategoryId.value = id; psProduct.value = null; psSkus.value = []
  psLoading.value = true
  try {
    const r = await products.managePage({ complexId: psComplexId.value, categoryId: id, page: 1, pageSize: 200 })
    psProducts.value = r.rows
  } catch (e) { toast.error(e.message || '상품 조회에 실패했습니다.') } finally { psLoading.value = false }
}
async function psPickProduct(p) {
  psProduct.value = p
  psLoading.value = true
  try {
    const r = await skus.managePage({ productId: p.id, page: 1, pageSize: 200 })
    psSkus.value = r.rows
  } catch (e) { toast.error(e.message || 'SKU 조회에 실패했습니다.') } finally { psLoading.value = false }
}
function psOnQuery() { clearTimeout(psTimer); psTimer = setTimeout(psRunQuery, 300) }
async function psRunQuery() {
  const q = psQuery.value.trim()
  if (!q) { psSearchResults.value = []; return }
  psLoading.value = true
  try {
    // 현재 드릴다운 범위 안에서 검색: 상품 > 카테고리 > 단지 순으로 좁힘
    const filters = { complexId: psComplexId.value, search: q, page: 1, pageSize: 100 }
    if (psProduct.value) filters.productId = psProduct.value.id
    else if (psCategoryId.value) filters.categoryId = psCategoryId.value
    const r = await skus.managePage(filters)
    psSearchResults.value = r.rows
  } catch (e) { toast.error(e.message || '검색에 실패했습니다.') } finally { psLoading.value = false }
}

const view = computed(() => {
  if (!auth.isLoggedIn) return 'login'
  if (showSearch.value) return 'psearch'
  if (showList.value) return 'list'
  return capturedImage.value ? 'photo' : 'home'
})
</script>

<template>
  <div class="mx-auto flex min-h-full max-w-md flex-col bg-slate-50">
    <header class="sticky top-0 z-10 flex items-center justify-between bg-brand-600 px-4 py-3 text-white">
      <span class="text-sm font-semibold">엠파크 제품 찾기</span>
      <div class="flex items-center gap-2">
        <span v-if="auth.isLoggedIn" class="text-xs text-white/70">{{ auth.displayName }}</span>
        <button v-if="auth.isLoggedIn" class="text-xs text-white/80 hover:underline" @click="doLogout">로그아웃</button>
      </div>
    </header>

    <!-- 로그인 -->
    <div v-if="view === 'login'" class="flex flex-1 flex-col items-center justify-center p-6">
      <div class="w-full max-w-sm">
        <div class="mb-5 text-center">
          <div class="mx-auto mb-3 flex h-14 w-14 items-center justify-center rounded-2xl bg-brand-600 text-2xl font-bold text-white">M</div>
          <h1 class="text-lg font-bold text-slate-800">모바일 입·출고</h1>
          <p class="mt-1 text-xs text-slate-400">로그인 후 제품을 찾을 수 있어요</p>
        </div>
        <form class="card space-y-3 p-5" @submit.prevent="doLogin">
          <div><label class="label">아이디</label><input v-model="loginEmail" class="input" autocomplete="username" /></div>
          <div><label class="label">비밀번호</label><input v-model="loginPw" type="password" class="input" autocomplete="current-password" /></div>
          <div class="flex items-center gap-4 pt-0.5 text-sm text-slate-600">
            <label class="flex cursor-pointer items-center gap-1.5"><input v-model="rememberId" type="checkbox" class="h-4 w-4 rounded border-slate-300" /> 아이디저장</label>
            <label class="flex cursor-pointer items-center gap-1.5"><input v-model="autoLogin" type="checkbox" class="h-4 w-4 rounded border-slate-300" /> 자동로그인</label>
          </div>
          <button class="btn-primary mt-1 w-full py-3" :disabled="loginLoading">{{ loginLoading ? '로그인 중…' : '로그인' }}</button>
        </form>
      </div>
    </div>

    <!-- 홈 (3 버튼) -->
    <div v-else-if="view === 'home'" class="flex flex-1 flex-col gap-4 p-5">
      <div class="mt-2 text-center">
        <h2 class="text-lg font-bold text-slate-800">무엇을 하시겠어요?</h2>
        <p class="mt-1 text-xs text-slate-400">상품을 검색하거나, 촬영·QR로 찾으세요</p>
      </div>

      <!-- 1. 상품 검색 -->
      <button class="flex flex-col items-center justify-center gap-3 rounded-2xl bg-white p-8 shadow-sm ring-1 ring-slate-100 active:scale-[0.99]" @click="openSearch">
        <div class="flex h-16 w-16 items-center justify-center rounded-2xl bg-brand-50 text-brand-600">
          <svg class="h-9 w-9" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7" /><path d="M21 21l-4.3-4.3" /></svg>
        </div>
        <div class="text-center">
          <p class="text-base font-bold text-slate-800">상품 검색</p>
          <p class="mt-0.5 text-xs text-slate-400">단지 › 카테고리 › 상품 › SKU 로 찾거나, 단지+상품넘버 바로 입력</p>
        </div>
      </button>

      <!-- 2. 사진촬영으로 찾기 -->
      <button class="flex flex-col items-center justify-center gap-3 rounded-2xl bg-white p-8 shadow-sm ring-1 ring-slate-100 active:scale-[0.99]" @click="pickPhoto">
        <div class="flex h-16 w-16 items-center justify-center rounded-2xl bg-amber-50 text-amber-600">
          <svg class="h-9 w-9" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M23 19a2 2 0 01-2 2H3a2 2 0 01-2-2V8a2 2 0 012-2h4l2-3h6l2 3h4a2 2 0 012 2z" /><circle cx="12" cy="13" r="4" /></svg>
        </div>
        <div class="text-center">
          <p class="text-base font-bold text-slate-800">사진촬영으로 찾기</p>
          <p class="mt-0.5 text-xs text-slate-400">제품을 촬영하면 AI가 유사한 등록 제품을 찾아줘요</p>
        </div>
      </button>

      <!-- 3. QR코드로 찾기 -->
      <button class="flex flex-col items-center justify-center gap-3 rounded-2xl bg-white p-8 shadow-sm ring-1 ring-slate-100 active:scale-[0.99]" @click="openQr">
        <div class="flex h-16 w-16 items-center justify-center rounded-2xl bg-emerald-50 text-emerald-600">
          <svg class="h-9 w-9" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7V5a2 2 0 012-2h2M17 3h2a2 2 0 012 2v2M21 17v2a2 2 0 01-2 2h-2M7 21H5a2 2 0 01-2-2v-2M7 7h4v4H7zM13 13h4v4h-4z" /></svg>
        </div>
        <div class="text-center">
          <p class="text-base font-bold text-slate-800">QR코드로 찾기</p>
          <p class="mt-0.5 text-xs text-slate-400">제품 QR을 스캔해 바로 이동해요</p>
        </div>
      </button>
    </div>

    <!-- 상품 검색 (단지 > 카테고리 > 상품 > SKU) -->
    <div v-else-if="view === 'psearch'" class="flex flex-1 flex-col">
      <div class="sticky top-[49px] z-10 space-y-2 border-b border-slate-100 bg-white p-3">
        <div class="flex items-center gap-2">
          <button class="shrink-0 rounded-lg px-2 py-2 text-xs text-slate-500 hover:bg-slate-100" @click="psBack">← 뒤로</button>
          <div class="flex-1 truncate text-xs text-slate-500">
            <span v-if="psComplexId" class="font-medium text-slate-700">{{ psComplexName }}</span>
            <span v-if="psCategoryId"> › {{ psCategoryName }}</span>
            <span v-if="psProduct"> › {{ psProduct.name }}</span>
            <span v-if="!psComplexId" class="text-slate-400">상품 검색</span>
          </div>
        </div>
        <input v-if="psComplexId" v-model="psQuery" class="input w-full" :placeholder="`${psScopeLabel} 내 상품넘버/상품명 검색`" @input="psOnQuery" />
      </div>

      <div class="flex-1 space-y-2 p-3">
        <!-- 1단계: 단지 -->
        <template v-if="!psComplexId">
          <p class="px-1 pb-1 text-sm text-slate-400">단지를 선택하세요</p>
          <button v-for="c in psComplexes" :key="c.id" class="flex w-full items-center justify-between gap-3 rounded-2xl border border-slate-200 bg-white p-6 text-left shadow-sm transition active:scale-[0.97] active:border-brand-400 active:bg-brand-50 hover:border-brand-300" @click="psPickComplex(c.id)">
            <span class="flex items-center gap-3">
              <span class="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-brand-50 text-2xl">🏢</span>
              <span>
                <span class="block text-lg font-bold text-slate-800">{{ c.name }}</span>
                <span class="block font-mono text-xs text-slate-400">{{ c.code }}</span>
              </span>
            </span>
            <svg class="h-6 w-6 shrink-0 text-slate-300" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6" /></svg>
          </button>
        </template>

        <!-- 빠른검색 결과(SKU) — 현재 범위 내 -->
        <template v-else-if="psQuery.trim()">
          <p class="px-1 text-xs text-slate-400">{{ psScopeLabel }} 내 검색 결과 {{ psSearchResults.length }}건</p>
          <button v-for="s in psSearchResults" :key="s.id" class="flex w-full items-center gap-3 rounded-xl border border-slate-100 bg-white p-2.5 text-left hover:border-slate-200" @click="goTo(s.code)">
            <img :src="thumb(s)" class="h-14 w-14 shrink-0 rounded-lg bg-slate-100 object-cover" alt="" />
            <div class="min-w-0 flex-1">
              <p class="font-mono text-xs text-brand-600">{{ s.code }}</p>
              <p class="truncate text-sm font-medium text-slate-800">{{ s.productName }}</p>
              <p v-if="s.spec || s.color" class="truncate text-xs text-slate-400">{{ [s.spec, s.color].filter(Boolean).join(' · ') }}</p>
            </div>
            <svg class="h-5 w-5 shrink-0 text-slate-300" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6" /></svg>
          </button>
          <div v-if="!psLoading && !psSearchResults.length" class="py-10 text-center text-sm text-slate-400">검색 결과가 없습니다</div>
        </template>

        <!-- 2단계: 카테고리 -->
        <template v-else-if="!psCategoryId">
          <p class="px-1 pb-1 text-sm text-slate-400">카테고리를 선택하세요</p>
          <button v-for="c in psCategories" :key="c.id" class="flex w-full items-center justify-between gap-3 rounded-2xl border border-slate-200 bg-white p-6 text-left shadow-sm transition active:scale-[0.97] active:border-brand-400 active:bg-brand-50 hover:border-brand-300" @click="psPickCategory(c.id)">
            <span class="flex items-center gap-3">
              <span class="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-amber-50 text-2xl">📁</span>
              <span class="block text-lg font-bold text-slate-800">{{ c.name }}</span>
            </span>
            <svg class="h-6 w-6 shrink-0 text-slate-300" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6" /></svg>
          </button>
        </template>

        <!-- 3단계: 상품(넘버링) 리스트 -->
        <template v-else-if="!psProduct">
          <p class="px-1 text-xs text-slate-400">상품 {{ psProducts.length }}건</p>
          <button v-for="p in psProducts" :key="p.id" class="flex w-full items-center gap-3 rounded-xl border border-slate-100 bg-white p-2.5 text-left hover:border-slate-200" @click="psPickProduct(p)">
            <img :src="thumb(p)" class="h-14 w-14 shrink-0 rounded-lg bg-slate-100 object-cover" alt="" />
            <div class="min-w-0 flex-1">
              <p class="font-mono text-xs text-brand-600">{{ p.code }}</p>
              <p class="truncate text-sm font-medium text-slate-800">{{ p.name }}</p>
            </div>
            <svg class="h-5 w-5 shrink-0 text-slate-300" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6" /></svg>
          </button>
          <div v-if="!psLoading && !psProducts.length" class="py-10 text-center text-sm text-slate-400">이 단지·카테고리에 상품이 없습니다</div>
        </template>

        <!-- 4단계: SKU 리스트 -->
        <template v-else>
          <p class="px-1 text-xs text-slate-400">SKU {{ psSkus.length }}건 · {{ psProduct.name }}</p>
          <button v-for="s in psSkus" :key="s.id" class="flex w-full items-center gap-3 rounded-xl border border-slate-100 bg-white p-2.5 text-left hover:border-slate-200" @click="goTo(s.code)">
            <img :src="thumb(s)" class="h-14 w-14 shrink-0 rounded-lg bg-slate-100 object-cover" alt="" />
            <div class="min-w-0 flex-1">
              <p class="font-mono text-xs text-brand-600">{{ s.code }}</p>
              <p class="truncate text-sm font-medium text-slate-800">{{ s.productName }}</p>
              <p v-if="s.spec || s.color" class="truncate text-xs text-slate-400">{{ [s.spec, s.color].filter(Boolean).join(' · ') }}</p>
              <p v-if="s.pathLabel" class="truncate text-xs text-slate-400">{{ s.pathLabel }}</p>
            </div>
            <svg class="h-5 w-5 shrink-0 text-slate-300" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6" /></svg>
          </button>
          <div v-if="!psLoading && !psSkus.length" class="py-10 text-center text-sm text-slate-400">이 상품에 SKU가 없습니다</div>
        </template>

        <div v-if="psLoading" class="py-4 text-center text-sm text-slate-400">불러오는 중…</div>
      </div>
    </div>

    <!-- 제품 찾아보기 결과 -->
    <div v-else-if="view === 'photo'" class="flex-1 space-y-4 p-4">
      <button class="text-xs text-slate-400 hover:text-slate-600" @click="resetPhoto">← 처음으로</button>

      <div class="card overflow-hidden">
        <img :src="capturedImage" class="h-40 w-full bg-slate-100 object-cover" alt="촬영 이미지" />
      </div>

      <!-- 검색 중 -->
      <div v-if="searching" class="flex items-center justify-center gap-2 rounded-xl bg-white p-5 text-sm text-slate-500 shadow-sm">
        <svg class="h-5 w-5 animate-spin text-brand-500" viewBox="0 0 24 24" fill="none"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" /><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" /></svg>
        AI가 유사한 제품을 찾고 있어요…
      </div>

      <template v-else>
        <!-- 대화형 안내 말풍선 -->
        <div class="flex items-start gap-2">
          <div class="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-brand-600 text-[11px] font-bold text-white">AI</div>
          <div class="rounded-2xl rounded-tl-md bg-white px-4 py-2.5 text-sm text-slate-700 shadow-sm">
            <template v-if="matches && matches.length">촬영하신 제품이 아래 목록에 있나요? 맞는 제품을 선택해 주세요.</template>
            <template v-else>유사한 등록 제품을 찾지 못했어요. 각도를 바꿔 다시 촬영해 보시겠어요?</template>
          </div>
        </div>

        <!-- 썸네일 리스트 -->
        <div v-if="matches && matches.length" class="space-y-2">
          <button
            v-for="m in matches" :key="m.skuId"
            class="flex w-full items-center gap-3 rounded-xl border bg-white p-2.5 text-left transition"
            :class="selectedCode === m.code ? 'border-brand-400 ring-2 ring-brand-100' : 'border-slate-100 hover:border-slate-200'"
            @click="selectedCode = m.code"
          >
            <img :src="thumb(m)" class="h-16 w-16 shrink-0 rounded-lg bg-slate-100 object-cover" alt="" />
            <div class="min-w-0 flex-1">
              <p class="font-mono text-xs text-brand-600">{{ m.code }}</p>
              <p class="truncate text-sm font-medium text-slate-800">{{ m.productName }}</p>
              <p v-if="m.spec || m.color" class="truncate text-xs text-slate-400">{{ [m.spec, m.color].filter(Boolean).join(' · ') }}</p>
              <p v-if="(m.locations || []).length" class="mt-0.5 flex items-center gap-1 truncate text-[11px] text-slate-500">
                <svg class="h-3 w-3 shrink-0 text-slate-400" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 21s7-5.686 7-11a7 7 0 10-14 0c0 5.314 7 11 7 11zM12 12.5a2.5 2.5 0 100-5 2.5 2.5 0 000 5z" /></svg>
                <span class="truncate">{{ locSummary(m) }}</span>
              </p>
              <p v-else class="mt-0.5 text-[11px] text-rose-400">재고 없음</p>
              <p v-if="m.reason" class="mt-0.5 truncate text-[11px] text-slate-400">“{{ m.reason }}”</p>
            </div>
            <svg v-if="selectedCode === m.code" class="h-5 w-5 shrink-0 text-brand-600" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5" /></svg>
          </button>
        </div>

        <!-- 액션 -->
        <div class="space-y-2 pt-1">
          <button v-if="selectedCode" class="btn w-full bg-brand-600 py-3 text-white hover:bg-brand-700" @click="goTo(selectedCode)">
            선택한 제품으로 이동 →
          </button>
          <button class="btn w-full bg-slate-700 py-3 text-sm text-white hover:bg-slate-800" @click="openList">
            📋 SKU 전체 리스트에서 찾기
          </button>
          <button class="btn-ghost w-full py-3" @click="pickPhoto">다시 촬영</button>
        </div>
      </template>
    </div>

    <!-- SKU 전체 리스트 (검색 폴백) -->
    <div v-else-if="view === 'list'" class="flex flex-1 flex-col">
      <div class="sticky top-[49px] z-10 border-b border-slate-100 bg-white p-3">
        <div class="flex items-center gap-2">
          <button class="shrink-0 rounded-lg px-2 py-2 text-xs text-slate-500 hover:bg-slate-100" @click="closeList">← 뒤로</button>
          <input v-model="listSearch" class="input flex-1" placeholder="코드 · 상품명 · 규격 검색" @input="onListSearch" />
        </div>
      </div>
      <div class="flex-1 space-y-2 p-3">
        <p class="px-1 text-xs text-slate-400">총 {{ listTotal }}개</p>
        <button
          v-for="row in listRows" :key="row.id"
          class="flex w-full items-center gap-3 rounded-xl border border-slate-100 bg-white p-2.5 text-left hover:border-slate-200"
          @click="goTo(row.code)"
        >
          <img :src="thumb(row)" class="h-14 w-14 shrink-0 rounded-lg bg-slate-100 object-cover" alt="" />
          <div class="min-w-0 flex-1">
            <p class="font-mono text-xs text-brand-600">{{ row.code }}<span v-if="row.complexName" class="ml-1 rounded bg-emerald-50 px-1 text-emerald-700">{{ row.complexName }}</span></p>
            <p class="truncate text-sm font-medium text-slate-800">{{ row.productName }}</p>
            <p v-if="row.spec || row.color" class="truncate text-xs text-slate-400">{{ [row.spec, row.color].filter(Boolean).join(' · ') }}</p>
          </div>
          <svg class="h-5 w-5 shrink-0 text-slate-300" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6" /></svg>
        </button>
        <div v-if="listLoading" class="py-4 text-center text-sm text-slate-400">불러오는 중…</div>
        <div v-else-if="!listRows.length" class="py-10 text-center text-sm text-slate-400">검색 결과가 없습니다</div>
        <button v-if="!listLoading && listRows.length < listTotal" class="btn-ghost w-full" @click="loadMore">더 보기 ({{ listRows.length }}/{{ listTotal }})</button>
      </div>
    </div>

    <!-- 숨김 카메라 입력 (제품 촬영) -->
    <input ref="fileInput" type="file" accept="image/*" capture="environment" class="hidden" @change="onPhoto" />

    <!-- QR 스캐너 오버레이 -->
    <Teleport to="body">
      <div v-if="qrOpen" class="fixed inset-0 z-[70] flex flex-col bg-black">
        <div class="flex items-center justify-between px-4 py-3 text-white">
          <span class="text-sm font-semibold">QR 코드를 화면에 맞춰주세요</span>
          <button class="rounded-lg p-2 hover:bg-white/10" @click="closeQr">
            <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </div>
        <div class="relative flex flex-1 items-center justify-center overflow-hidden">
          <video ref="videoEl" class="h-full w-full object-cover" muted playsinline></video>
          <div class="pointer-events-none absolute h-56 w-56 rounded-2xl border-2 border-white/80 shadow-[0_0_0_9999px_rgba(0,0,0,0.45)]"></div>
        </div>
        <p class="py-4 text-center text-xs text-white/70">제품에 부착된 QR을 인식하면 자동으로 이동합니다</p>
      </div>
    </Teleport>
  </div>
</template>

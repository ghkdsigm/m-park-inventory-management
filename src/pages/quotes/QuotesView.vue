<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { quotes, complexes } from '@/services/db'
import Pager from '@/components/ui/Pager.vue'
import { getToken } from '@/api'
import { useToast } from '@/composables/useToast'
import { useBusy } from '@/composables/useBusy'
import BaseModal from '@/components/ui/BaseModal.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import SkuPicker from '@/components/ui/SkuPicker.vue'

const toast = useToast()
const { busy: saving, run } = useBusy()
const confirm = ref(null)

/* ---------- 목록 ---------- */
const list = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(true)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

/* ---------- 조회 필터 (월별 / 업체별 / 단지별) — 서버 파라미터 ---------- */
const fMonth = ref('')
const fVendor = ref('')
const fComplex = ref('')
const options = ref({ vendors: [], complexes: [], months: [] })

async function load() {
  loading.value = true
  try {
    const r = await quotes.pageList({
      vendor: fVendor.value, complex: fComplex.value, month: fMonth.value,
      page: page.value, pageSize: pageSize.value,
    })
    list.value = r.rows
    total.value = r.total
  } catch (e) {
    toast.error('목록 조회 실패: ' + (e.message || e.code))
  } finally {
    loading.value = false
  }
}
async function loadOptions() {
  try { options.value = await quotes.filterOptions() } catch (e) { /* 옵션 실패해도 목록은 됨 */ }
}
function resetFilters() { fMonth.value = ''; fVendor.value = ''; fComplex.value = ''; page.value = 1; load() }
watch([fMonth, fVendor, fComplex], () => { page.value = 1; load() })
watch(page, load)

/* ---------- 매칭용 참조 데이터 (SKU는 전체 로드 안 함 — 피커가 서버검색) ---------- */
const complexList = ref([])
async function loadRefs() {
  try { complexList.value = await complexes.list() } catch (e) { /* 실패해도 업로드/저장 가능 */ }
}

onMounted(() => {
  load()
  loadOptions()
  loadRefs()
})

/* ---------- 업로드 → 검토 ---------- */
const fileInput = ref(null)
const uploading = ref(false)
const reviewModal = ref(false)
const form = reactive({
  vendorName: '', vendorBizNo: '', quoteDate: '', siteLabel: '',
  complexId: '', complexName: '', totalAmount: 0, fileUrl: '', filePath: '',
  items: [],
})

// 업로드 팝업: 단지 먼저 선택 후 파일 선택
const uploadModal = ref(false)
const uploadComplexId = ref('')
function openUpload() { uploadComplexId.value = ''; uploadModal.value = true }
function pickFile() {
  if (!uploadComplexId.value) return toast.error('단지를 먼저 선택하세요.')
  fileInput.value?.click()
}

async function onFile(e) {
  const file = e.target.files?.[0]
  if (fileInput.value) fileInput.value.value = '' // 같은 파일 재선택 허용
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.pdf') && file.type !== 'application/pdf') {
    return toast.error('PDF 파일만 업로드할 수 있습니다.')
  }
  const cxSel = complexList.value.find((c) => c.id === uploadComplexId.value)
  uploading.value = true
  try {
    const r = await quotes.upload(file, uploadComplexId.value, cxSel?.name || '')
    form.vendorName = r.vendorName || ''
    form.vendorBizNo = r.vendorBizNo || ''
    form.quoteDate = r.quoteDate || ''
    form.siteLabel = r.siteLabel || ''
    form.totalAmount = r.totalAmount || 0
    form.fileUrl = r.fileUrl || ''
    form.filePath = r.filePath || ''
    // 업로드 시 선택한 단지로 확정 (SKU 연결·저장 모두 이 단지 기준)
    form.complexId = uploadComplexId.value
    form.complexName = cxSel?.name || ''
    uploadModal.value = false
    // 품목 (skuId = AI 추천 기본값, 원본금액 보관)
    form.items = (r.items || []).map((it) => ({
      lineNo: it.lineNo,
      rawName: it.rawName,
      spec: it.spec || '',
      unit: it.unit || '',
      qty: it.qty,
      unitPrice: it.unitPrice,
      origAmount: it.amount,
      vatAmount: it.vatAmount,
      skuId: it.suggestedSkuId || '',
      suggested: it.matchStatus === 'suggested',
      suggestedSkuId: it.suggestedSkuId || '',
      note: '',
    }))
    reviewModal.value = true
    toast.success(`추출 완료 · 품목 ${form.items.length}건`)
    autoLinkByComplex() // 단지 자동매칭이 되면 즉시 연결
  } catch (e) {
    // 월 1회 중복 등 서버 안내 메시지는 그대로 노출(팝업은 열린 채 유지 → 단지/파일 재선택)
    toast.error(e.message || e.code || '견적서 업로드 실패')
  } finally {
    uploading.value = false
  }
}

function matchComplex(site) {
  if (!site) return null
  const s = String(site).trim()
  return complexList.value.find((c) => c.name && (s.includes(c.name) || c.name.includes(s))) || null
}

// 현장 단지 선택 시 이름 동기화 + 자동연결
function onComplexChange() {
  const c = complexList.value.find((x) => x.id === form.complexId)
  form.complexName = c ? c.name : ''
  autoLinkByComplex()
}

// (품목+규격) 정규화 키 — "A-72 - 2ea(1세트수량)" → "a-72" 접미 제거, 공백/대소문자 무시
function skuKey(name, spec) {
  const bs = String(spec || '').replace(/\s*-\s*\d+ea\(1세트수량\)\s*$/, '')
  return (String(name || '') + '|' + bs).replace(/\s+/g, '').toLowerCase()
}
// 선택 단지의 SKU를 불러와, 품목+규격 정확 일치 품목은 자동 연결
async function autoLinkByComplex() {
  if (!form.complexId || !form.items.length) return
  try {
    const r = await skus.managePage({ complexId: form.complexId, page: 1, pageSize: 1000 })
    const map = new Map()
    for (const s of (r.rows || [])) map.set(skuKey(s.productName, s.spec), s)
    let n = 0
    for (const it of form.items) {
      const hit = map.get(skuKey(it.rawName, it.spec))
      if (hit) { it.skuId = hit.id; it.suggested = true; it.suggestedSkuId = hit.id; it.autoLinked = true; n++ }
    }
    if (n) toast.success(`단지 자동연결 ${n}건`)
  } catch (_) { /* 무시 */ }
}

/* 계산금액 & 불일치(AI 오추출 검토용) */
function calcAmount(it) { return Math.round((Number(it.qty) || 0) * (Number(it.unitPrice) || 0)) }
function mismatch(it) { return calcAmount(it) !== Math.round(Number(it.origAmount) || 0) }
const mismatchCount = computed(() => form.items.filter(mismatch).length)

async function save() {
  if (!form.items.length) return toast.error('품목이 없습니다.')
  const payload = {
    vendorName: form.vendorName,
    vendorBizNo: form.vendorBizNo,
    quoteDate: form.quoteDate || null,
    complexId: form.complexId || null,
    complexName: form.complexName,
    siteLabel: form.siteLabel,
    totalAmount: form.totalAmount,
    fileUrl: form.fileUrl,
    filePath: form.filePath,
    items: form.items.map((it) => ({
      lineNo: it.lineNo,
      rawName: it.rawName,
      spec: it.spec,
      unit: it.unit,
      qty: Number(it.qty) || 0,
      unitPrice: Number(it.unitPrice) || 0,
      amount: calcAmount(it),
      vatAmount: Number(it.vatAmount) || 0,
      skuId: it.skuId || null,
      note: it.note || '',
    })),
  }
  try {
    await quotes.create(payload)
    toast.success('견적서가 저장되었습니다.')
    reviewModal.value = false
    page.value = 1
    await load()
    loadOptions()
  } catch (e) {
    toast.error('저장 실패: ' + (e.message || e.code))
  }
}

/* ---------- 상세 / 삭제 ---------- */
const detailModal = ref(false)
const detail = ref(null)
async function openDetail(item) {
  try {
    const d = await quotes.get(item.id)
    d.items = (d.items || []).map((x) => ({ ...x, skuId: x.skuId || '' })) // null → '' (셀렉트 바인딩)
    detail.value = d
    detailModal.value = true
  } catch (e) {
    toast.error('상세 조회 실패: ' + (e.message || e.code))
  }
}

// 상세에서 연결 SKU 변경/해제 (즉시 저장)
async function relink(it) {
  try {
    await quotes.linkItem(it.id, it.skuId || '')
    toast.success(it.skuId ? '연결이 변경되었습니다.' : '연결이 해제되었습니다.')
  } catch (e) {
    toast.error('연결 변경 실패: ' + (e.message || e.code))
    if (detail.value) {
      const d = await quotes.get(detail.value.id)
      d.items = (d.items || []).map((x) => ({ ...x, skuId: x.skuId || '' }))
      detail.value = d
    }
  }
}
async function removeQuote(item) {
  const ok = await confirm.value.ask({
    title: '견적서 삭제',
    message: `${item.vendorName} (${item.quoteDate || '-'}) 견적서를 삭제할까요?`,
    confirmText: '삭제', danger: true,
  })
  if (!ok) return
  try {
    await quotes.remove(item.id)
    toast.success('삭제되었습니다.')
    await load()
    loadOptions()
  } catch (e) {
    toast.error('삭제 실패: ' + (e.message || e.code))
  }
}

/* ---------- SKU 연결 로그 ---------- */
const linkLogModal = ref(false)
const linkLogs = ref([])
const linkLogLoading = ref(false)
async function openLinkLogs() {
  if (!detail.value) return
  linkLogModal.value = true
  linkLogLoading.value = true
  try {
    linkLogs.value = await quotes.linkLogs(detail.value.id)
  } catch (e) {
    toast.error('연결 로그 조회 실패: ' + (e.message || e.code))
    linkLogs.value = []
  } finally {
    linkLogLoading.value = false
  }
}

/* ---------- 유틸 ---------- */
function fmt(n) { return (Number(n) || 0).toLocaleString() }
function fmtMonth(s) { if (!s) return '-'; const d = new Date(s); return isNaN(d) ? '-' : `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}` }
function fmtDt(s) { if (!s) return '-'; const d = new Date(s); return isNaN(d) ? s : d.toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'short' }) }

// 견적 PDF는 인증 필요(/files/quotes/**) → 토큰 실은 fetch로 받아 새 탭에서 열기
async function openPdf(url) {
  if (!url) return
  try {
    const res = await fetch(url, { headers: { Authorization: `Bearer ${getToken()}` } })
    if (!res.ok) throw new Error('열기 실패 (' + res.status + ')')
    const blobUrl = URL.createObjectURL(await res.blob())
    window.open(blobUrl, '_blank')
    setTimeout(() => URL.revokeObjectURL(blobUrl), 60000)
  } catch (e) {
    toast.error('PDF 열기 실패: ' + (e.message || e.code))
  }
}
</script>

<template>
  <PageHeader title="견적서 관리" subtitle="업체 견적서(PDF)를 올리면 품목·수량·단가를 자동 추출하고 SKU와 매칭합니다.">
    <button class="btn-primary" :disabled="uploading" @click="openUpload">
      {{ uploading ? '추출 중…' : '+ 견적서 업로드' }}
    </button>
    <input ref="fileInput" type="file" accept="application/pdf,.pdf" class="hidden" @change="onFile" />
  </PageHeader>

  <!-- 조회 필터 -->
  <div v-if="options.months.length || options.vendors.length || fMonth || fVendor || fComplex" class="mb-3 flex flex-wrap items-center gap-2">
    <AppSelect v-model="fMonth" class="w-auto">
      <option value="">업로드월 전체</option>
      <option v-for="m in options.months" :key="m" :value="m">{{ m }}</option>
    </AppSelect>
    <AppSelect v-model="fVendor" class="w-auto">
      <option value="">전체 업체</option>
      <option v-for="v in options.vendors" :key="v" :value="v">{{ v }}</option>
    </AppSelect>
    <AppSelect v-model="fComplex" class="w-auto">
      <option value="">전체 단지</option>
      <option v-for="c in options.complexes" :key="c" :value="c">{{ c }}</option>
    </AppSelect>
    <button v-if="fMonth || fVendor || fComplex" class="btn-ghost btn-sm" @click="resetFilters">초기화</button>
    <span class="ml-auto text-xs text-slate-400">총 {{ total }}건</span>
  </div>

  <!-- 목록 -->
  <div class="card">
    <div class="overflow-x-auto scrollbar-slim">
      <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
      <div v-else-if="!list.length && (fMonth || fVendor || fComplex)" class="p-10 text-center text-sm text-slate-400">
        조건에 맞는 견적서가 없습니다.
      </div>
      <div v-else-if="!list.length" class="p-10 text-center text-sm text-slate-400">
        등록된 견적서가 없습니다. 우측 상단 “견적서 업로드”로 시작하세요.
      </div>
      <table v-else class="w-full min-w-[720px] text-sm">
        <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th class="px-4 py-2.5 font-semibold">업로드월</th>
            <th class="px-4 py-2.5 font-semibold">견적일</th>
            <th class="px-4 py-2.5 font-semibold">업체</th>
            <th class="px-4 py-2.5 font-semibold">단지명</th>
            <th class="px-4 py-2.5 font-semibold">등록자</th>
            <th class="px-4 py-2.5 text-right font-semibold">품목수</th>
            <th class="px-4 py-2.5 text-right font-semibold">합계(VAT포함)</th>
            <th class="px-4 py-2.5 text-right font-semibold">관리</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-for="q in list" :key="q.id" class="hover:bg-slate-50/60">
            <td class="px-4 py-3 whitespace-nowrap text-slate-600">{{ fmtMonth(q.createdAt) }}</td>
            <td class="px-4 py-3 whitespace-nowrap text-slate-500">{{ q.quoteDate || '-' }}</td>
            <td class="px-4 py-3 font-medium text-slate-800">{{ q.vendorName }}</td>
            <td class="px-4 py-3">
              <span v-if="q.complexName" class="badge bg-brand-50 text-brand-700">{{ q.complexName }}</span>
              <span v-else class="text-slate-400">-</span>
            </td>
            <td class="px-4 py-3 text-slate-600">{{ q.uploadedByName || '-' }}</td>
            <td class="px-4 py-3 text-right">{{ q.itemCount }}건</td>
            <td class="px-4 py-3 text-right font-medium">{{ fmt(q.totalAmount) }}원</td>
            <td class="px-4 py-3 text-right whitespace-nowrap">
              <button class="btn-ghost btn-sm mr-1" @click="openDetail(q)">상세</button>
              <button class="btn-ghost btn-sm text-rose-600" @click="removeQuote(q)">삭제</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <Pager v-if="total" v-model:page="page" :total="total" :total-pages="totalPages" class="border-t border-slate-100" />
  </div>

  <!-- 검토·매칭 모달 -->
  <BaseModal v-model="reviewModal" title="견적서 검토 · SKU 매칭" size="lg">
    <div class="space-y-4">
      <!-- 헤더 -->
      <div class="grid grid-cols-2 gap-3 sm:grid-cols-3">
        <div><label class="label">업체명</label><input v-model="form.vendorName" class="input" /></div>
        <div><label class="label">사업자번호</label><input v-model="form.vendorBizNo" class="input" /></div>
        <div><label class="label">견적일 (YYYY-MM-DD)</label><input v-model="form.quoteDate" class="input" placeholder="2026-07-04" /></div>
        <div>
          <label class="label">단지 <span class="text-slate-400">(업로드 시 선택 · 고정)</span></label>
          <AppSelect v-model="form.complexId" disabled @change="onComplexChange">
            <option value="">{{ form.siteLabel ? form.siteLabel + ' (미지정)' : '단지 선택' }}</option>
            <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option>
          </AppSelect>
        </div>
        <div><label class="label">합계(VAT포함)</label><input v-model.number="form.totalAmount" type="number" class="input" /></div>
        <div class="flex items-end">
          <button v-if="form.fileUrl" type="button" class="btn-ghost btn-sm" @click="openPdf(form.fileUrl)">원본 PDF 열기</button>
        </div>
      </div>

      <!-- 검산 경고 -->
      <div v-if="mismatchCount" class="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-700">
        ⚠ 수량×단가 ≠ 금액 인 줄이 {{ mismatchCount }}건 있습니다. 노란색 줄의 수량/단가를 확인하세요.
      </div>

      <!-- 품목 테이블 -->
      <div class="max-h-[46vh] overflow-auto scrollbar-slim rounded-lg border border-slate-100">
        <table class="w-full min-w-[820px] text-sm">
          <thead class="sticky top-0 z-10 border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
            <tr>
              <th class="px-2 py-2 font-semibold">#</th>
              <th class="px-2 py-2 font-semibold">품명 / 규격</th>
              <th class="px-2 py-2 text-right font-semibold">수량</th>
              <th class="px-2 py-2 text-right font-semibold">단가</th>
              <th class="px-2 py-2 text-right font-semibold">공급가액</th>
              <th class="px-2 py-2 text-right font-semibold">세액</th>
              <th class="px-2 py-2 font-semibold">SKU 매칭</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-50">
            <tr v-for="it in form.items" :key="it.lineNo" :class="mismatch(it) ? 'bg-amber-50/60' : ''">
              <td class="px-2 py-2 text-slate-400">{{ it.lineNo }}</td>
              <td class="px-2 py-2">
                <div class="font-medium text-slate-800">{{ it.rawName }}</div>
                <div v-if="it.spec" class="text-[11px] text-slate-400">{{ it.spec }}</div>
              </td>
              <td class="px-2 py-2"><input v-model.number="it.qty" type="number" class="input h-8 w-20 text-right" /></td>
              <td class="px-2 py-2"><input v-model.number="it.unitPrice" type="number" class="input h-8 w-24 text-right" /></td>
              <td class="px-2 py-2 text-right tabular-nums" :class="mismatch(it) ? 'text-amber-700 font-medium' : 'text-slate-600'">
                {{ fmt(calcAmount(it)) }}
              </td>
              <td class="px-2 py-2 text-right tabular-nums text-slate-500">{{ fmt(it.vatAmount) }}</td>
              <td class="px-2 py-2">
                <div class="flex items-center gap-1">
                  <SkuPicker v-model="it.skuId" :initial-label="it.suggestedSkuLabel || ''" :default-query="it.rawName" :complex-id="form.complexId" class="min-w-[200px]" placeholder="— 새 제품(미연결) —" />
                  <span v-if="it.suggested && it.skuId && it.skuId === it.suggestedSkuId"
                        class="badge bg-emerald-50 text-emerald-600 whitespace-nowrap">AI추천</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="text-[11px] text-slate-400">
        연결할 기존 SKU가 없으면 “새 제품(미연결)”로 두세요. 나중에 그 제품을 등록/입고할 때 이 견적과 연결됩니다.
      </p>
    </div>

    <template #footer>
      <button class="btn-ghost" @click="reviewModal = false">취소</button>
      <button class="btn-primary" :disabled="saving" @click="run(save)">저장</button>
    </template>
  </BaseModal>

  <!-- 상세 모달 -->
  <BaseModal v-model="detailModal" title="견적서 상세" size="xl">
    <div v-if="detail" class="space-y-3">
      <div class="grid grid-cols-2 gap-2 text-sm sm:grid-cols-3 lg:grid-cols-5">
        <div><span class="text-slate-400">업체</span><div class="font-medium">{{ detail.vendorName }}</div></div>
        <div><span class="text-slate-400">등록번호</span><div>{{ detail.vendorBizNo || '-' }}</div></div>
        <div><span class="text-slate-400">견적일</span><div>{{ detail.quoteDate || '-' }}</div></div>
        <div><span class="text-slate-400">단지명</span><div>{{ detail.complexName || detail.siteLabel || '-' }}</div></div>
        <div><span class="text-slate-400">합계(VAT포함)</span><div>{{ fmt(detail.totalAmount) }}원</div></div>
      </div>
      <div class="max-h-[50vh] overflow-auto scrollbar-slim rounded-lg border border-slate-100">
        <table class="w-full min-w-[640px] text-sm">
          <thead class="sticky top-0 border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
            <tr>
              <th class="px-3 py-2 font-semibold">#</th>
              <th class="px-3 py-2 font-semibold">품명 / 규격</th>
              <th class="px-3 py-2 text-right font-semibold">수량</th>
              <th class="px-3 py-2 text-right font-semibold">단가</th>
              <th class="px-3 py-2 text-right font-semibold">공급가액</th>
              <th class="px-3 py-2 text-right font-semibold">세액</th>
              <th class="px-3 py-2 font-semibold">연결 SKU <span class="font-normal text-slate-400">(변경 가능)</span></th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-50">
            <tr v-for="it in detail.items" :key="it.id">
              <td class="px-3 py-2 text-slate-400">{{ it.lineNo }}</td>
              <td class="px-3 py-2">
                <div class="font-medium text-slate-800">{{ it.rawName }}</div>
                <div v-if="it.spec" class="text-[11px] text-slate-400">{{ it.spec }}</div>
              </td>
              <td class="px-3 py-2 text-right">{{ it.qty }}</td>
              <td class="px-3 py-2 text-right">{{ fmt(it.unitPrice) }}</td>
              <td class="px-3 py-2 text-right tabular-nums">{{ fmt(it.amount) }}</td>
              <td class="px-3 py-2 text-right tabular-nums text-slate-500">{{ fmt(it.vatAmount) }}</td>
              <td class="px-3 py-2">
                <SkuPicker v-model="it.skuId" :default-query="it.rawName" :complex-id="detail.complexId" class="min-w-[220px]" placeholder="— 미연결 —" @change="relink(it)" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="flex items-center justify-end gap-2">
        <button type="button" class="btn-ghost btn-sm" @click="openLinkLogs">🔗 SKU 연결 로그</button>
        <button v-if="detail.fileUrl" type="button" class="btn-ghost btn-sm" @click="openPdf(detail.fileUrl)">원본 PDF 열기</button>
      </div>
    </div>
    <template #footer>
      <button class="btn-ghost" @click="detailModal = false">닫기</button>
    </template>
  </BaseModal>

  <!-- SKU 연결 로그 모달 -->
  <BaseModal v-model="linkLogModal" title="SKU 연결 로그" size="lg">
    <div v-if="linkLogLoading" class="p-6 text-center text-sm text-slate-400">불러오는 중…</div>
    <div v-else-if="!linkLogs.length" class="p-8 text-center text-sm text-slate-400">SKU 연결/해제 기록이 없습니다.</div>
    <div v-else class="max-h-[55vh] overflow-auto scrollbar-slim rounded-lg border border-slate-100">
      <table class="w-full min-w-[560px] text-sm">
        <thead class="sticky top-0 border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th class="px-3 py-2 font-semibold">일시</th>
            <th class="px-3 py-2 font-semibold">작업자</th>
            <th class="px-3 py-2 font-semibold">작업</th>
            <th class="px-3 py-2 font-semibold">견적 품목</th>
            <th class="px-3 py-2 font-semibold">연결 SKU</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-for="(l, i) in linkLogs" :key="i">
            <td class="px-3 py-2 whitespace-nowrap text-slate-500">{{ fmtDt(l.at) }}</td>
            <td class="px-3 py-2 font-medium text-slate-700">{{ l.byName || '-' }}</td>
            <td class="px-3 py-2">
              <span class="badge" :class="l.action === '견적품목 연결' ? 'bg-emerald-50 text-emerald-700' : 'bg-slate-100 text-slate-500'">{{ l.action === '견적품목 연결' ? '연결' : '해제' }}</span>
            </td>
            <td class="px-3 py-2 text-slate-600">{{ l.itemName }}</td>
            <td class="px-3 py-2 text-slate-600">{{ l.skuLabel }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <template #footer>
      <button class="btn-ghost" @click="linkLogModal = false">닫기</button>
    </template>
  </BaseModal>

  <!-- 업로드 모달: 단지 선택 후 PDF 업로드 (견적서는 단지·업체·월 1회) -->
  <BaseModal v-model="uploadModal" title="견적서 업로드">
    <div class="space-y-3">
      <div>
        <label class="label">단지 <span class="text-rose-500">*</span></label>
        <AppSelect v-model="uploadComplexId" class="w-full">
          <option value="">단지 선택</option>
          <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option>
        </AppSelect>
      </div>
      <p class="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-700">
        견적서는 <b>단지별로 따로</b> 업로드합니다. 같은 <b>단지·업체·월</b>에는 1회만 올릴 수 있어요.
      </p>
      <button class="btn-primary w-full py-2.5" :disabled="!uploadComplexId || uploading" @click="pickFile">
        {{ uploading ? '추출 중…' : '📄 PDF 선택 후 업로드' }}
      </button>
    </div>
    <template #footer>
      <button class="btn-ghost" @click="uploadModal = false">취소</button>
    </template>
  </BaseModal>

  <ConfirmDialog ref="confirm" />
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { quotes, skus, complexes } from '@/services/db'
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
const loading = ref(false)
async function load() {
  loading.value = true
  try {
    list.value = await quotes.list()
  } catch (e) {
    toast.error('목록 조회 실패: ' + (e.message || e.code))
  } finally {
    loading.value = false
  }
}

/* ---------- 조회 필터 (월별 / 업체별 / 현장별) ---------- */
const fMonth = ref('')
const fVendor = ref('')
const fComplex = ref('')
const monthOptions = computed(() => {
  const s = new Set()
  list.value.forEach((q) => { if (q.quoteDate) s.add(String(q.quoteDate).slice(0, 7)) })
  return [...s].sort().reverse()
})
const vendorOptions = computed(() => [...new Set(list.value.map((q) => q.vendorName).filter(Boolean))].sort())
const complexOptions = computed(() => [...new Set(list.value.map((q) => q.complexName).filter(Boolean))].sort())
const filtered = computed(() => list.value.filter((q) =>
  (!fMonth.value || (q.quoteDate && String(q.quoteDate).slice(0, 7) === fMonth.value)) &&
  (!fVendor.value || q.vendorName === fVendor.value) &&
  (!fComplex.value || q.complexName === fComplex.value)
))
function resetFilters() { fMonth.value = ''; fVendor.value = ''; fComplex.value = '' }

/* ---------- 매칭용 참조 데이터 ---------- */
const skuOptions = ref([]) // { id, code, productName, spec }
const complexList = ref([])
async function loadRefs() {
  try {
    const [sk, cx] = await Promise.all([skus.list(), complexes.list()])
    skuOptions.value = (sk || []).map((s) => ({ id: s.id, code: s.code, productName: s.productName, spec: s.spec }))
    complexList.value = cx || []
  } catch (e) {
    // 매칭 참조는 실패해도 업로드/저장은 가능
  }
}
function skuLabel(s) {
  return `${s.code} · ${s.productName}${s.spec ? ' (' + s.spec + ')' : ''}`
}

onMounted(() => {
  load()
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

function pickFile() { fileInput.value?.click() }

async function onFile(e) {
  const file = e.target.files?.[0]
  if (fileInput.value) fileInput.value.value = '' // 같은 파일 재선택 허용
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.pdf') && file.type !== 'application/pdf') {
    return toast.error('PDF 파일만 업로드할 수 있습니다.')
  }
  uploading.value = true
  try {
    const r = await quotes.upload(file)
    form.vendorName = r.vendorName || ''
    form.vendorBizNo = r.vendorBizNo || ''
    form.quoteDate = r.quoteDate || ''
    form.siteLabel = r.siteLabel || ''
    form.totalAmount = r.totalAmount || 0
    form.fileUrl = r.fileUrl || ''
    form.filePath = r.filePath || ''
    // 현장(단지) 자동 매칭
    const cx = matchComplex(r.siteLabel)
    form.complexId = cx ? cx.id : ''
    form.complexName = cx ? cx.name : (r.siteLabel || '')
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
  } catch (e) {
    toast.error('견적서 추출 실패: ' + (e.message || e.code))
  } finally {
    uploading.value = false
  }
}

function matchComplex(site) {
  if (!site) return null
  const s = String(site).trim()
  return complexList.value.find((c) => c.name && (s.includes(c.name) || c.name.includes(s))) || null
}

// 현장 단지 선택 시 이름도 동기화
function onComplexChange() {
  const c = complexList.value.find((x) => x.id === form.complexId)
  form.complexName = c ? c.name : ''
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
    await load()
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
function skuName(id) {
  const s = skuOptions.value.find((x) => x.id === id)
  return s ? skuLabel(s) : ''
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
  } catch (e) {
    toast.error('삭제 실패: ' + (e.message || e.code))
  }
}

/* ---------- 유틸 ---------- */
function fmt(n) { return (Number(n) || 0).toLocaleString() }
</script>

<template>
  <PageHeader title="견적서 관리" subtitle="업체 견적서(PDF)를 올리면 품목·수량·단가를 자동 추출하고 SKU와 매칭합니다.">
    <button class="btn-primary" :disabled="uploading" @click="pickFile">
      {{ uploading ? '추출 중…' : '+ 견적서 업로드' }}
    </button>
    <input ref="fileInput" type="file" accept="application/pdf,.pdf" class="hidden" @change="onFile" />
  </PageHeader>

  <!-- 조회 필터 -->
  <div v-if="list.length" class="mb-3 flex flex-wrap items-center gap-2">
    <AppSelect v-model="fMonth" class="w-auto">
      <option value="">전체 월</option>
      <option v-for="m in monthOptions" :key="m" :value="m">{{ m }}</option>
    </AppSelect>
    <AppSelect v-model="fVendor" class="w-auto">
      <option value="">전체 업체</option>
      <option v-for="v in vendorOptions" :key="v" :value="v">{{ v }}</option>
    </AppSelect>
    <AppSelect v-model="fComplex" class="w-auto">
      <option value="">전체 단지</option>
      <option v-for="c in complexOptions" :key="c" :value="c">{{ c }}</option>
    </AppSelect>
    <button v-if="fMonth || fVendor || fComplex" class="btn-ghost btn-sm" @click="resetFilters">초기화</button>
    <span class="ml-auto text-xs text-slate-400">{{ filtered.length }} / {{ list.length }}건</span>
  </div>

  <!-- 목록 -->
  <div class="card">
    <div class="overflow-x-auto scrollbar-slim">
      <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
      <div v-else-if="!list.length" class="p-10 text-center text-sm text-slate-400">
        등록된 견적서가 없습니다. 우측 상단 “견적서 업로드”로 시작하세요.
      </div>
      <div v-else-if="!filtered.length" class="p-10 text-center text-sm text-slate-400">
        조건에 맞는 견적서가 없습니다.
      </div>
      <table v-else class="w-full min-w-[720px] text-sm">
        <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th class="px-4 py-2.5 font-semibold">견적일</th>
            <th class="px-4 py-2.5 font-semibold">업체</th>
            <th class="px-4 py-2.5 font-semibold">단지명</th>
            <th class="px-4 py-2.5 text-right font-semibold">품목수</th>
            <th class="px-4 py-2.5 text-right font-semibold">합계(VAT포함)</th>
            <th class="px-4 py-2.5 text-right font-semibold">관리</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-for="q in filtered" :key="q.id" class="hover:bg-slate-50/60">
            <td class="px-4 py-3 whitespace-nowrap text-slate-600">{{ q.quoteDate || '-' }}</td>
            <td class="px-4 py-3 font-medium text-slate-800">{{ q.vendorName }}</td>
            <td class="px-4 py-3">
              <span v-if="q.complexName" class="badge bg-brand-50 text-brand-700">{{ q.complexName }}</span>
              <span v-else class="text-slate-400">-</span>
            </td>
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
          <label class="label">단지</label>
          <AppSelect v-model="form.complexId" @change="onComplexChange">
            <option value="">{{ form.siteLabel ? form.siteLabel + ' (미지정)' : '단지 선택' }}</option>
            <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option>
          </AppSelect>
        </div>
        <div><label class="label">합계(VAT포함)</label><input v-model.number="form.totalAmount" type="number" class="input" /></div>
        <div class="flex items-end">
          <a v-if="form.fileUrl" :href="form.fileUrl" target="_blank" class="btn-ghost btn-sm">원본 PDF 열기</a>
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
                  <SkuPicker v-model="it.skuId" :skus="skuOptions" class="min-w-[200px]" placeholder="— 새 제품(미연결) —" />
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
                <SkuPicker v-model="it.skuId" :skus="skuOptions" class="min-w-[220px]" placeholder="— 미연결 —" @change="relink(it)" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="text-right">
        <a v-if="detail.fileUrl" :href="detail.fileUrl" target="_blank" class="btn-ghost btn-sm">원본 PDF 열기</a>
      </div>
    </div>
    <template #footer>
      <button class="btn-ghost" @click="detailModal = false">닫기</button>
    </template>
  </BaseModal>

  <ConfirmDialog ref="confirm" />
</template>

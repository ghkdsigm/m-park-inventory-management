<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { skus, complexes, categories, productCodes, productDetails, applyAuditBatch, listMovements, resolveAudit } from '@/services/db'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import BaseModal from '@/components/ui/BaseModal.vue'
import Pager from '@/components/ui/Pager.vue'
import { resolveImage } from '@/utils/image'
import { fmtDateTime } from '@/utils/date'
import { specText } from '@/utils/sku'

const auth = useAuthStore()
const toast = useToast()
const confirm = ref(null)

const loading = ref(true)
const working = ref(false)
const rows = ref([]) // 현재 페이지
const complexList = ref([])
const categoryList = ref([])
const productCodeList = ref([])
const productDetailList = ref([])
// 페이지를 넘나들며 입력값/시스템값을 누적 보존
const counts = reactive({}) // skuId -> 실사수량
const sysQty = reactive({}) // skuId -> 시스템 재고(스냅샷)
const skuMeta = reactive({}) // skuId -> { code }

const fComplex = ref('')
const fCategory = ref('')
const fProductCode = ref('')
const fProductDetail = ref('')
const fAudit = ref('') // 실사상태 필터: '' | unaudited | ok | mismatch
const search = ref('')
const onlyDiff = ref(false)
const memo = ref('')

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

// 서버 페이징
const sizes = [10, 30, 50]
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

async function fetchPage() {
  loading.value = true
  try {
    const r = await skus.page({
      complexId: fComplex.value,
      categoryId: fCategory.value,
      productCodeId: fProductCode.value,
      productDetailId: fProductDetail.value,
      auditStatus: fAudit.value,
      search: search.value.trim(),
      page: page.value,
      pageSize: pageSize.value,
    })
    rows.value = r.rows
    total.value = r.total
    // 시스템값/메타 스냅샷 (실사수량은 비워둠 — 입력한 항목만 '실사한 것'으로 취급, 입력값은 페이지 넘나들어도 보존)
    r.rows.forEach((s) => {
      sysQty[s.stockId] = s.qty
      skuMeta[s.stockId] = { code: s.code }
    })
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
    /* 옵션 로드 실패 무시 */
  }
}
function clearCounts() {
  Object.keys(counts).forEach((k) => delete counts[k])
}
onMounted(async () => { await loadMasters(); await fetchPage() })

watch([fComplex, fCategory, fProductCode, fProductDetail, fAudit], () => { page.value = 1; fetchPage() })
watch(pageSize, () => { page.value = 1; fetchPage() })
watch(page, fetchPage)
let searchTimer = null
watch(search, () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => { page.value = 1; fetchPage() }, 350) })

const categoryOptions = computed(() => categoryList.value)
const pcOptions = computed(() => (fCategory.value ? productCodeList.value.filter((p) => p.categoryId === fCategory.value) : productCodeList.value))
const pdOptions = computed(() => (fProductCode.value ? productDetailList.value.filter((d) => d.productCodeId === fProductCode.value) : productDetailList.value))
watch(fComplex, () => { fCategory.value = ''; fProductCode.value = ''; fProductDetail.value = '' })
watch(fCategory, () => { fProductCode.value = ''; fProductDetail.value = '' })
watch(fProductCode, () => { fProductDetail.value = '' })

function diffOf(s) {
  const c = counts[s.stockId]
  if (c === '' || c == null) return 0
  return Number(c) - Number(s.qty)
}

// 현재 페이지 표시 행 ('차이만'은 현재 페이지 내에서 적용)
const displayRows = computed(() => (onlyDiff.value ? rows.value.filter((s) => diffOf(s) !== 0) : rows.value))

// 실사수량을 입력한 항목 = 실사한 항목 (차이 0=일치 포함). 페이지 누적(counts/sysQty 맵 기준)
const changed = computed(() =>
  Object.keys(counts)
    .filter((id) => {
      const c = counts[id]
      return c !== '' && c != null && sysQty[id] != null
    })
    .map((id) => ({ id, code: skuMeta[id]?.code || id, counted: Number(counts[id]), diff: Number(counts[id]) - Number(sysQty[id]) }))
)
const diffCount = computed(() => changed.value.filter((x) => x.diff !== 0).length)
const diffSum = computed(() => changed.value.reduce((a, x) => a + x.diff, 0))

function attrLine(s) {
  return [specText(s), s.color, s.releaseYear && `출시 ${s.releaseYear}`, s.purpose].filter(Boolean).join(' · ')
}

// 위치 검증 선택
const locSel = ref(new Set())
function toggleLoc(id) {
  locSel.value.has(id) ? locSel.value.delete(id) : locSel.value.add(id)
  locSel.value = new Set(locSel.value)
}
async function verifyLocations() {
  if (!locSel.value.size) return toast.error('검증할 항목을 선택하세요.')
  working.value = true
  try {
    const ids = [...locSel.value]
    for (const id of ids) await skus.verifyLocation(id, auth.actor)
    toast.success(`위치 ${ids.length}건 검증 확정`)
    locSel.value = new Set()
    await fetchPage()
  } catch (e) {
    toast.error('위치 검증 실패: ' + (e.message || e.code))
  } finally {
    working.value = false
  }
}
function resetCounts() {
  clearCounts()
  toast.info('입력한 실사수량을 모두 지웠습니다.')
}

async function confirmAudit() {
  if (!changed.value.length) return toast.error('실사수량을 입력한 항목이 없습니다.')
  for (const x of changed.value) {
    if (x.counted < 0) return toast.error(`실사수량은 0 이상이어야 합니다. (${x.code})`)
  }
  const ok = await confirm.value.ask({
    title: '재고실사 확정',
    message: `실사 입력 ${changed.value.length}건을 확정합니다. (차이 ${diffCount.value}건, 합계 ${diffSum.value > 0 ? '+' : ''}${diffSum.value})\n일치 항목은 '실사완료(정상)'로, 차이 항목은 시스템 재고가 실사값으로 보정되어 원장에 '실사'로 기록됩니다.`,
    confirmText: '실사 확정',
  })
  if (!ok) return
  working.value = true
  try {
    const items = changed.value.map((x) => ({ stockId: x.id, counted: x.counted }))
    const r = await applyAuditBatch(items, auth.actor, memo.value)
    toast.success(`실사 확정 완료 · ${r.changed}건 반영`)
    clearCounts()
    await fetchPage()
    memo.value = ''
  } catch (e) {
    toast.error('실사 실패: ' + (e.message || e.code))
  } finally {
    working.value = false
  }
}

/* ===== 실사 상세 / 오차 정상처리 팝업 ===== */
const auditModal = ref(false)
const auditSku = ref(null)
const auditHist = ref([])
const auditHistLoading = ref(false)
const resolveReason = ref('')
async function openAudit(s) {
  auditSku.value = s
  auditHist.value = []
  resolveReason.value = ''
  auditModal.value = true
  auditHistLoading.value = true
  try {
    const mv = await listMovements(s.skuId, 100)
    auditHist.value = mv.filter((m) => m.type === 'audit' && m.stockId === s.stockId)
  } catch (e) {
    /* 이력 로드 실패 무시 */
  } finally {
    auditHistLoading.value = false
  }
}
async function doResolve() {
  const s = auditSku.value
  if (!s) return
  if (!resolveReason.value.trim()) return toast.error('정상처리 사유를 입력하세요.')
  working.value = true
  try {
    await resolveAudit(s.stockId, resolveReason.value.trim())
    toast.success('정상처리되었습니다.')
    auditModal.value = false
    await fetchPage()
  } catch (e) {
    toast.error('정상처리 실패: ' + (e.message || e.code))
  } finally {
    working.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader title="재고실사" subtitle="실물을 카운트해 실사수량을 입력하면 실사한 항목으로 확정됩니다. 시스템과 일치해도 확정하면 '실사완료(정상)'로 기록됩니다.">
      <button class="btn-ghost" :disabled="working" @click="resetCounts">초기화</button>
      <button class="btn-ghost" :disabled="working || !locSel.size" @click="verifyLocations">위치 검증 ({{ locSel.size }})</button>
      <button class="btn-primary" :disabled="working || !changed.length" @click="confirmAudit">
        {{ working ? '처리 중…' : `실사 확정 (${changed.length})` }}
      </button>
    </PageHeader>

    <!-- 요약 -->
    <div class="mb-4 grid grid-cols-4 gap-3">
      <div class="card p-3 text-center"><p class="text-xs text-slate-400">대상 SKU</p><p class="text-xl font-bold text-slate-800">{{ total.toLocaleString() }}</p></div>
      <div class="card p-3 text-center"><p class="text-xs text-slate-400">실사 입력</p><p class="text-xl font-bold text-brand-600">{{ changed.length }}</p></div>
      <div class="card p-3 text-center"><p class="text-xs text-slate-400">차이 발생</p><p class="text-xl font-bold text-amber-500">{{ diffCount }}</p></div>
      <div class="card p-3 text-center"><p class="text-xs text-slate-400">차이 합계</p><p class="text-xl font-bold" :class="diffSum < 0 ? 'text-rose-500' : 'text-emerald-600'">{{ diffSum > 0 ? '+' : '' }}{{ diffSum }}</p></div>
    </div>

    <!-- 필터 -->
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <AppSelect v-model="fComplex" class="w-auto"><option value="">전체 단지</option><option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option></AppSelect>
      <AppSelect v-model="fCategory" class="w-auto"><option value="">전체 카테고리</option><option v-for="c in categoryOptions" :key="c.id" :value="c.id">{{ c.name }}</option></AppSelect>
      <AppSelect v-model="fProductCode" class="w-auto"><option value="">전체 제품코드</option><option v-for="p in pcOptions" :key="p.id" :value="p.id">{{ p.name }}</option></AppSelect>
      <AppSelect v-model="fProductDetail" class="w-auto"><option value="">전체 상세코드</option><option v-for="d in pdOptions" :key="d.id" :value="d.id">{{ d.name }}</option></AppSelect>
      <AppSelect v-model="fAudit" class="w-auto"><option v-for="a in AUDIT_STATUS" :key="a.v" :value="a.v">{{ a.t }}</option></AppSelect>
      <input v-model="search" class="input w-full sm:w-64" placeholder="SKU코드/상품명 검색" />
      <label class="flex items-center gap-1.5 text-sm text-slate-500"><input v-model="onlyDiff" type="checkbox" class="rounded border-slate-300" /> 차이만</label>
      <input v-model="memo" class="input w-auto sm:max-w-[200px]" placeholder="실사 메모 (예: 2026-06 정기실사)" />
      <AppSelect v-model="pageSize" class="w-auto sm:ml-auto">
        <option v-for="n in sizes" :key="n" :value="n">{{ n }}개씩</option>
      </AppSelect>
    </div>

    <div class="card">
      <div class="overflow-x-auto scrollbar-slim">
      <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
      <div v-else-if="!displayRows.length" class="p-10 text-center text-sm text-slate-400">{{ onlyDiff ? '이 페이지에 차이 항목이 없습니다.' : '대상 SKU가 없습니다.' }}</div>
      <table v-else class="w-full min-w-[820px] text-sm">
        <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th class="px-3 py-2.5 font-semibold">SKU / 상품</th>
            <th class="px-3 py-2.5 font-semibold">위치 (검증)</th>
            <th class="px-3 py-2.5 font-semibold">실사상태</th>
            <th class="px-3 py-2.5 text-right font-semibold">시스템</th>
            <th class="px-3 py-2.5 text-center font-semibold">실사수량</th>
            <th class="px-3 py-2.5 text-right font-semibold">차이</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-for="s in displayRows" :key="s.stockId" class="hover:bg-slate-50/60" :class="diffOf(s) !== 0 ? 'bg-amber-50/40' : ''">
            <td class="px-3 py-2">
              <div class="flex items-center gap-2.5">
                <img :src="resolveImage(s)" class="h-9 w-9 shrink-0 rounded border border-slate-100 object-cover" alt="" />
                <div class="min-w-0">
                  <span class="badge bg-brand-50 font-mono text-brand-700">{{ s.code }}</span>
                  <p class="text-slate-700">{{ s.productName }}</p>
                  <p class="truncate text-[11px] text-slate-400">{{ attrLine(s) }}</p>
                </div>
              </div>
            </td>
            <td class="px-3 py-2">
              <label class="flex items-center gap-1.5">
                <input type="checkbox" class="rounded border-slate-300" :checked="locSel.has(s.stockId)" @change="toggleLoc(s.stockId)" />
                <span class="min-w-0">
                  <span v-if="s.locationLabel" class="block truncate text-xs text-slate-600">📍 {{ s.locationLabel }}</span>
                  <span v-else class="block text-xs text-slate-300">위치 미지정</span>
                  <span v-if="s.locationVerifiedAt" class="block text-[10px] text-emerald-600">✓ 검증됨</span>
                </span>
              </label>
            </td>
            <td class="px-3 py-2">
              <button class="inline-flex flex-col items-start gap-0.5 rounded px-1.5 py-1 text-left hover:bg-brand-50" title="실사 상세 보기" @click="openAudit(s)">
                <span class="badge" :class="auditMeta(s).c">{{ auditMeta(s).t }}</span>
                <span v-if="s.lastAuditedAt" class="text-[10px] text-slate-400">
                  <span v-if="s.lastAuditDiff" :class="s.lastAuditDiff < 0 ? 'text-rose-500' : 'text-emerald-600'">{{ s.lastAuditDiff > 0 ? '+' : '' }}{{ s.lastAuditDiff }} · </span>{{ fmtDateTime(s.lastAuditedAt) }}
                </span>
              </button>
            </td>
            <td class="px-3 py-2 text-right font-semibold text-slate-500">{{ s.qty }}</td>
            <td class="px-3 py-2 text-center">
              <input v-model.number="counts[s.stockId]" type="number" min="0" class="input w-24 text-center" :placeholder="`${s.qty}`" />
            </td>
            <td class="px-3 py-2 text-right font-bold" :class="diffOf(s) === 0 ? 'text-slate-300' : diffOf(s) < 0 ? 'text-rose-500' : 'text-emerald-600'">
              {{ diffOf(s) > 0 ? '+' : '' }}{{ diffOf(s) }}
            </td>
          </tr>
        </tbody>
      </table>
      </div>
      <Pager v-if="total && !onlyDiff" v-model:page="page" :total="total" :total-pages="totalPages" class="border-t border-slate-100" />
    </div>

    <ConfirmDialog ref="confirm" />

    <!-- 실사 상세 / 오차 정상처리 -->
    <BaseModal v-model="auditModal" title="실사 상세" size="md">
      <div v-if="auditSku">
        <div class="mb-3 rounded-lg bg-slate-50 p-3">
          <p class="text-sm"><span class="font-mono text-brand-700">{{ auditSku.code }}</span> <span class="text-slate-700">{{ auditSku.productName }}</span></p>
          <p class="mt-0.5 text-xs text-slate-400">📍 {{ auditSku.locationLabel ? (auditSku.complexName + ' › ' + auditSku.locationLabel) : (auditSku.complexName || '위치 미지정') }}</p>
          <div class="mt-1.5">
            <span class="badge" :class="auditMeta(auditSku).c">{{ auditMeta(auditSku).t }}</span>
          </div>
        </div>

        <!-- 마지막 실사 결과 -->
        <div v-if="auditSku.lastAuditedAt" class="mb-3 rounded-lg border border-slate-200 p-3 text-sm">
          <p class="mb-1 text-xs font-semibold text-slate-500">마지막 실사</p>
          <div class="flex items-center justify-between">
            <span class="text-slate-600">시스템 {{ (auditSku.lastAuditCounted ?? 0) - (auditSku.lastAuditDiff ?? 0) }} <span class="text-slate-300">→</span> 실사 <b class="text-slate-800">{{ auditSku.lastAuditCounted }}</b></span>
            <span class="font-bold" :class="(auditSku.lastAuditDiff || 0) < 0 ? 'text-rose-500' : (auditSku.lastAuditDiff || 0) > 0 ? 'text-emerald-600' : 'text-slate-400'">차이 {{ (auditSku.lastAuditDiff || 0) > 0 ? '+' : '' }}{{ auditSku.lastAuditDiff || 0 }}</span>
          </div>
          <p class="mt-1 text-xs text-slate-400">실사자: {{ auditSku.lastAuditedBy || '-' }} · {{ fmtDateTime(auditSku.lastAuditedAt) }}</p>
          <p v-if="auditSku.auditStatus === 'ok' && auditSku.auditResolvedAt" class="mt-1 text-xs text-emerald-600">
            ✓ 정상처리: {{ auditSku.auditResolvedBy }} · {{ fmtDateTime(auditSku.auditResolvedAt) }}<span v-if="auditSku.auditResolveReason"> · 사유: {{ auditSku.auditResolveReason }}</span>
          </p>
        </div>
        <div v-else class="mb-3 rounded-lg border border-dashed border-slate-200 p-3 text-center text-sm text-slate-400">아직 실사하지 않은 재고입니다.</div>

        <!-- 오차 정상처리 -->
        <div v-if="auditSku.auditStatus === 'mismatch'" class="mb-3 rounded-lg border border-rose-200 bg-rose-50/50 p-3">
          <p class="mb-1.5 text-xs font-semibold text-rose-600">오차 정상처리 — 사유를 입력하면 상태가 정상으로 전환됩니다.</p>
          <div class="flex gap-2">
            <input v-model="resolveReason" class="input flex-1" placeholder="예: 파손 폐기, 분실, 오입력 정정" @keyup.enter="doResolve" />
            <button class="btn-primary shrink-0" :disabled="working" @click="doResolve">정상처리</button>
          </div>
        </div>

        <!-- 실사 이력 -->
        <p class="mb-1 text-xs font-semibold text-slate-500">실사 이력</p>
        <div v-if="auditHistLoading" class="py-6 text-center text-sm text-slate-400">불러오는 중…</div>
        <div v-else-if="!auditHist.length" class="py-6 text-center text-sm text-slate-300">실사 이력이 없습니다.</div>
        <ul v-else class="divide-y divide-slate-50 text-sm">
          <li v-for="m in auditHist" :key="m.id" class="flex items-center justify-between gap-2 py-2">
            <span class="text-slate-600">{{ m.before }} <span class="text-slate-300">→</span> {{ m.after }}
              <b class="ml-1" :class="m.delta < 0 ? 'text-rose-500' : m.delta > 0 ? 'text-emerald-600' : 'text-slate-400'">{{ m.delta > 0 ? '+' : '' }}{{ m.delta }}</b>
            </span>
            <span class="text-right text-xs text-slate-400"><span class="text-slate-600">{{ m.byName }}</span> · {{ fmtDateTime(m.at) }}</span>
          </li>
        </ul>
      </div>
      <template #footer>
        <button class="btn-primary" @click="auditModal = false">닫기</button>
      </template>
    </BaseModal>
  </div>
</template>

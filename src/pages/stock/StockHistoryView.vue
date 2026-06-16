<script setup>
import { ref, computed, onMounted } from 'vue'
import { recentMovements, complexes } from '@/services/db'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import BaseModal from '@/components/ui/BaseModal.vue'
import { toJsDate } from '@/utils/date'

const toast = useToast()
const loading = ref(true)
const rows = ref([])
const complexList = ref([])

// 상세 팝업
const detailOpen = ref(false)
const detail = ref(null)
function openDetail(m) {
  detail.value = m
  detailOpen.value = true
}

const search = ref('')
const fType = ref('')
const fComplex = ref('')
const LIMIT = 100

const TYPES = [
  { v: '', t: '전체 유형' },
  { v: 'in', t: '입고' },
  { v: 'out', t: '출고' },
  { v: 'adjust', t: '재고조정' },
  { v: 'audit', t: '재고실사' },
]
const typeMeta = {
  in: { t: '입고', c: 'bg-emerald-50 text-emerald-700' },
  out: { t: '출고', c: 'bg-sky-50 text-sky-700' },
  adjust: { t: '조정', c: 'bg-amber-50 text-amber-700' },
  audit: { t: '실사', c: 'bg-violet-50 text-violet-700' },
}

async function load() {
  loading.value = true
  try {
    ;[rows.value, complexList.value] = await Promise.all([recentMovements(LIMIT), complexes.list()])
  } catch (e) {
    toast.error('불러오기 실패: ' + (e.message || e.code))
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() =>
  rows.value.filter((m) => {
    if (fType.value && m.type !== fType.value) return false
    if (fComplex.value && m.complexId !== fComplex.value) return false
    if (search.value) {
      const q = search.value.toLowerCase()
      return [m.skuCode, m.productName, m.pathLabel, m.byName, m.memo].some((v) => (v || '').toLowerCase().includes(q))
    }
    return true
  })
)

function fmt(ts) {
  const d = toJsDate(ts)
  if (!d) return ''
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

// CSV 내보내기 (오래된 로그 백업/보관용)
function exportCsv() {
  if (!filtered.value.length) return toast.error('내보낼 내역이 없습니다.')
  const head = ['일시', '유형', 'SKU코드', '상품명', '경로', '이전수량', '이후수량', '증감', '처리자', '사유', '내용']
  const esc = (v) => `"${String(v ?? '').replace(/"/g, '""')}"`
  const lines = filtered.value.map((m) =>
    [fmt(m.at), typeMeta[m.type]?.t || m.type, m.skuCode, m.productName, m.pathLabel, m.before, m.after, (m.delta > 0 ? '+' : '') + m.delta, m.byName, m.reason, m.memo]
      .map(esc)
      .join(',')
  )
  const csv = '﻿' + [head.map(esc).join(','), ...lines].join('\r\n') // BOM(한글 깨짐 방지)
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `입출고내역_${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
  toast.success(`${filtered.value.length}건 CSV로 내보냈습니다.`)
}
</script>

<template>
  <div>
    <PageHeader title="입출고 통합조회" subtitle="상품/SKU별 입·출고·조정·실사 이력 (누가·언제·무엇을·왜)">
      <button class="btn-ghost" @click="exportCsv">CSV 내보내기</button>
    </PageHeader>

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <input v-model="search" class="input w-auto flex-1 sm:max-w-xs" placeholder="SKU코드/상품명/처리자/내용 검색" />
      <select v-model="fType" class="input w-auto"><option v-for="t in TYPES" :key="t.v" :value="t.v">{{ t.t }}</option></select>
      <select v-model="fComplex" class="input w-auto"><option value="">전체 단지</option><option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option></select>
      <button class="btn-ghost btn-sm" @click="load">새로고침</button>
    </div>

    <p class="mb-2 text-xs text-slate-400">최근 {{ LIMIT }}건을 불러와 표시합니다. 더 오래된 이력은 CSV로 내보내 보관하세요.</p>

    <div class="card overflow-x-auto scrollbar-slim">
      <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
      <div v-else-if="!filtered.length" class="p-10 text-center text-sm text-slate-400">조건에 맞는 이력이 없습니다.</div>
      <table v-else class="w-full min-w-[760px] text-sm">
        <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th class="px-3 py-2.5 font-semibold">일시</th>
            <th class="px-3 py-2.5 font-semibold">유형</th>
            <th class="px-3 py-2.5 font-semibold">SKU / 상품</th>
            <th class="hidden px-3 py-2.5 font-semibold lg:table-cell">경로</th>
            <th class="px-3 py-2.5 text-right font-semibold">수량변화</th>
            <th class="px-3 py-2.5 font-semibold">처리자</th>
            <th class="px-3 py-2.5 font-semibold">내용</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-for="m in filtered" :key="m.id" class="cursor-pointer hover:bg-brand-50/40" @click="openDetail(m)">
            <td class="whitespace-nowrap px-3 py-2.5 text-xs text-slate-500">{{ fmt(m.at) }}</td>
            <td class="px-3 py-2.5"><span class="badge" :class="typeMeta[m.type]?.c">{{ typeMeta[m.type]?.t || m.type }}</span></td>
            <td class="px-3 py-2.5">
              <span class="badge bg-brand-50 font-mono text-brand-700">{{ m.skuCode }}</span>
              <p class="mt-0.5 text-slate-700">{{ m.productName }}</p>
            </td>
            <td class="hidden px-3 py-2.5 text-xs text-slate-400 lg:table-cell">{{ m.pathLabel }}</td>
            <td class="whitespace-nowrap px-3 py-2.5">
              <div class="flex items-center justify-end gap-1.5">
                <span class="text-xs text-slate-400">{{ m.before }}</span>
                <svg class="h-3 w-3 text-slate-300" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M5 12h14M13 6l6 6-6 6" stroke-linecap="round" stroke-linejoin="round"/></svg>
                <span class="text-xs font-semibold text-slate-700">{{ m.after }}</span>
                <span class="badge ml-1 font-bold tabular-nums" :class="m.delta >= 0 ? 'bg-emerald-50 text-emerald-700' : 'bg-rose-50 text-rose-600'">{{ m.delta > 0 ? '+' : '' }}{{ m.delta }}</span>
              </div>
            </td>
            <td class="whitespace-nowrap px-3 py-2.5 text-slate-600">{{ m.byName }}</td>
            <td class="px-3 py-2.5 text-slate-500">
              <span v-if="m.reason" class="badge mr-1 bg-amber-50 text-amber-700">{{ m.reason }}</span>{{ m.memo || (m.reason ? '' : '—') }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 수량 변화 상세 -->
    <BaseModal v-model="detailOpen" title="수량 변화 상세" size="sm">
      <div v-if="detail" class="space-y-3 text-sm">
        <div class="flex items-center justify-between">
          <span class="badge" :class="typeMeta[detail.type]?.c">{{ typeMeta[detail.type]?.t || detail.type }}</span>
          <span class="text-xs text-slate-400">{{ fmt(detail.at) }}</span>
        </div>

        <div class="grid grid-cols-3 items-center gap-2 rounded-xl bg-slate-50 p-3 text-center">
          <div><p class="text-[11px] text-slate-400">이전</p><p class="text-2xl font-bold text-slate-500 tabular-nums">{{ detail.before }}</p></div>
          <div>
            <p class="text-[11px] text-slate-400">변동</p>
            <p class="text-2xl font-extrabold tabular-nums" :class="detail.delta >= 0 ? 'text-emerald-600' : 'text-rose-500'">{{ detail.delta > 0 ? '+' : '' }}{{ detail.delta }}</p>
          </div>
          <div><p class="text-[11px] text-slate-400">이후</p><p class="text-2xl font-bold text-slate-800 tabular-nums">{{ detail.after }}</p></div>
        </div>

        <dl class="overflow-hidden rounded-lg border border-slate-100">
          <div class="flex items-center justify-between border-b border-slate-50 px-3 py-2"><dt class="text-slate-400">SKU</dt><dd class="font-mono text-slate-700">{{ detail.skuCode }}</dd></div>
          <div class="flex items-center justify-between border-b border-slate-50 px-3 py-2"><dt class="text-slate-400">상품</dt><dd class="text-slate-700">{{ detail.productName }}</dd></div>
          <div v-if="detail.pathLabel || detail.complexName" class="border-b border-slate-50 px-3 py-2"><dt class="text-slate-400">경로</dt><dd class="mt-0.5 text-xs text-slate-600">{{ detail.pathLabel || detail.complexName }}</dd></div>
          <div class="flex items-center justify-between border-b border-slate-50 px-3 py-2"><dt class="text-slate-400">처리자</dt><dd class="text-slate-700">{{ detail.byName }}</dd></div>
          <div v-if="detail.reason" class="flex items-center justify-between border-b border-slate-50 px-3 py-2"><dt class="text-slate-400">사유</dt><dd><span class="badge bg-amber-50 text-amber-700">{{ detail.reason }}</span></dd></div>
          <div v-if="detail.memo" class="px-3 py-2"><dt class="text-slate-400">내용</dt><dd class="mt-0.5 whitespace-pre-line text-slate-700">{{ detail.memo }}</dd></div>
        </dl>
      </div>
      <template #footer>
        <button class="btn-primary" @click="detailOpen = false">닫기</button>
      </template>
    </BaseModal>
  </div>
</template>

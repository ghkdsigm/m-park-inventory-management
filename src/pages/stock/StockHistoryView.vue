<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { skus, complexes, listMovements, listLocationLogs } from '@/services/db'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import { resolveImage } from '@/utils/image'
import { fmtDateTime, fmtDate, toJsDate } from '@/utils/date'

const toast = useToast()
const loading = ref(true)
const list = ref([])
const complexList = ref([])
const search = ref('')
const fComplex = ref('')
const fColor = ref('')
const fRelease = ref('')
const fProduction = ref('')
const priceMin = ref('')
const priceMax = ref('')

const distinct = (key) =>
  [...new Set(list.value.map((s) => s[key]).filter((v) => v !== null && v !== undefined && String(v).trim() !== ''))]
const colorOptions = computed(() => distinct('color').sort())
const releaseYearOptions = computed(() => distinct('releaseYear').sort((a, b) => Number(b) - Number(a)))
const productionYearOptions = computed(() => distinct('productionYear').sort((a, b) => Number(b) - Number(a)))

const selectedId = ref('')
const moves = ref([])
const locLogs = ref([])
const detailLoading = ref(false)

const typeMeta = {
  in: { t: '입고', c: 'bg-emerald-50 text-emerald-700' },
  out: { t: '출고', c: 'bg-sky-50 text-sky-700' },
  adjust: { t: '조정', c: 'bg-amber-50 text-amber-700' },
  audit: { t: '실사', c: 'bg-violet-50 text-violet-700' },
}
const statusMeta = {
  in_stock: { t: '정상', c: 'bg-emerald-50 text-emerald-700' },
  low: { t: '부족', c: 'bg-amber-50 text-amber-700' },
  out: { t: '품절', c: 'bg-rose-50 text-rose-600' },
}

const ts = (d) => toJsDate(d)?.getTime() || 0

async function load() {
  loading.value = true
  try {
    ;[list.value, complexList.value] = await Promise.all([skus.list(), complexes.list()])
    // 가장 최근 변경(updatedAt)된 SKU 우선
    list.value.sort((a, b) => ts(b.updatedAt || b.createdAt) - ts(a.updatedAt || a.createdAt))
    if (filtered.value.length) select(filtered.value[0])
  } catch (e) {
    toast.error('불러오기 실패: ' + (e.message || e.code))
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() =>
  list.value.filter((s) => {
    if (fComplex.value && s.complexId !== fComplex.value) return false
    if (fColor.value && s.color !== fColor.value) return false
    if (fRelease.value && String(s.releaseYear) !== fRelease.value) return false
    if (fProduction.value && String(s.productionYear) !== fProduction.value) return false
    const price = Number(s.price) || 0
    if (priceMin.value !== '' && price < Number(priceMin.value)) return false
    if (priceMax.value !== '' && price > Number(priceMax.value)) return false
    if (search.value) {
      const q = search.value.toLowerCase()
      return [s.code, s.productName, s.spec, s.pathLabel].some((v) => (v || '').toLowerCase().includes(q))
    }
    return true
  })
)
const selected = computed(() => list.value.find((s) => s.id === selectedId.value) || null)

function select(s) {
  selectedId.value = s.id
}
watch(selectedId, async (id) => {
  if (!id) return
  detailLoading.value = true
  moves.value = []
  locLogs.value = []
  try {
    ;[moves.value, locLogs.value] = await Promise.all([listMovements(id, 100), listLocationLogs(id, 50)])
  } catch (e) {
    toast.error('이력 조회 실패: ' + (e.message || e.code))
  } finally {
    detailLoading.value = false
  }
})

function exportCsv() {
  const s = selected.value
  if (!s || !moves.value.length) return toast.error('내보낼 이력이 없습니다.')
  const head = ['일시', '유형', '이전', '이후', '증감', '사유', '내용', '처리자']
  const esc = (v) => `"${String(v ?? '').replace(/"/g, '""')}"`
  const rows = moves.value.map((m) =>
    [fmtDateTime(m.at), typeMeta[m.type]?.t || m.type, m.before, m.after, (m.delta > 0 ? '+' : '') + m.delta, m.reason, m.memo, m.byName].map(esc).join(',')
  )
  const csv = '﻿' + [head.map(esc).join(','), ...rows].join('\r\n')
  const a = document.createElement('a')
  a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8;' }))
  a.download = `이력_${s.code}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
}
</script>

<template>
  <div>
    <PageHeader title="입출고 통합조회" subtitle="SKU를 선택하면 우측에 수량·위치 변동 이력이 모두 표시됩니다." />

    <div class="grid gap-4 lg:grid-cols-5">
      <!-- 좌: SKU 리스트 (최근 변경순) -->
      <div class="card flex flex-col lg:col-span-2">
        <div class="flex flex-wrap items-center gap-1.5 border-b border-slate-100 p-3">
          <select v-model="fComplex" class="input w-auto text-sm">
            <option value="">전체 단지</option>
            <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
          <select v-model="fColor" class="input w-auto text-sm">
            <option value="">전체 색상</option>
            <option v-for="c in colorOptions" :key="c" :value="c">{{ c }}</option>
          </select>
          <select v-model="fRelease" class="input w-auto text-sm">
            <option value="">출시년도</option>
            <option v-for="y in releaseYearOptions" :key="y" :value="String(y)">{{ y }}</option>
          </select>
          <select v-model="fProduction" class="input w-auto text-sm">
            <option value="">생산년도</option>
            <option v-for="y in productionYearOptions" :key="y" :value="String(y)">{{ y }}</option>
          </select>
          <div class="flex items-center gap-1">
            <input v-model="priceMin" type="number" min="0" class="input w-20 text-sm" placeholder="단가↓" />
            <span class="text-slate-400">~</span>
            <input v-model="priceMax" type="number" min="0" class="input w-20 text-sm" placeholder="↑" />
          </div>
          <input v-model="search" class="input w-full text-sm" placeholder="SKU코드/상품명 검색" />
        </div>
        <div class="max-h-[68vh] flex-1 overflow-y-auto scrollbar-slim">
          <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
          <div v-else-if="!filtered.length" class="p-10 text-center text-sm text-slate-400">SKU가 없습니다.</div>
          <button
            v-for="s in filtered"
            :key="s.id"
            class="flex w-full items-center gap-2.5 border-b border-slate-50 px-3 py-2.5 text-left hover:bg-slate-50"
            :class="selectedId === s.id ? 'bg-brand-50' : ''"
            @click="select(s)"
          >
            <img :src="resolveImage(s)" class="h-9 w-9 shrink-0 rounded border border-slate-100 object-cover" alt="" />
            <div class="min-w-0 flex-1">
              <span class="badge bg-brand-50 font-mono text-brand-700">{{ s.code }}</span>
              <span class="ml-1 text-sm font-medium text-slate-700">{{ s.productName }}</span>
              <p class="truncate text-[11px] text-slate-400">{{ s.pathLabel }}</p>
            </div>
            <div class="shrink-0 text-right">
              <p class="text-sm font-semibold" :class="s.qty <= 0 ? 'text-rose-500' : 'text-slate-700'">{{ s.qty }}개</p>
              <p class="text-[10px] text-slate-400">{{ s.lastMovedAt ? fmtDateTime(s.lastMovedAt) : '-' }}</p>
            </div>
          </button>
        </div>
      </div>

      <!-- 우: 상세 이력 -->
      <div class="lg:col-span-3">
        <div v-if="!selected" class="card p-10 text-center text-sm text-slate-400">왼쪽에서 SKU를 선택하세요.</div>
        <div v-else class="space-y-4">
          <!-- 헤더: 현재 재고/위치 -->
          <div class="card overflow-hidden">
            <div class="flex gap-4 p-4">
              <img :src="resolveImage(selected)" class="h-24 w-24 shrink-0 rounded-lg border border-slate-100 bg-slate-50 object-contain p-1" alt="" />
              <div class="min-w-0 flex-1">
                <p class="font-mono text-lg font-bold text-slate-800">{{ selected.code }}</p>
                <p class="text-sm text-slate-600">{{ selected.productName }}<span v-if="selected.spec" class="text-slate-400"> · {{ selected.spec }}</span></p>
                <p class="mt-0.5 text-xs text-slate-400">{{ selected.pathLabel }}</p>
                <div class="mt-1.5 flex items-center gap-2">
                  <span class="text-sm text-slate-400">현재 재고</span>
                  <span class="text-xl font-extrabold" :class="selected.qty <= 0 ? 'text-rose-500' : 'text-brand-600'">{{ selected.qty }}</span>
                  <span class="badge" :class="statusMeta[selected.status]?.c">{{ statusMeta[selected.status]?.t }}</span>
                </div>
              </div>
            </div>
            <div class="border-t border-slate-100 bg-slate-50/60 px-4 py-2 text-sm">
              📍 현재 위치:
              <b class="text-slate-700">{{ selected.locationLabel ? selected.complexName + ' › ' + selected.locationLabel : (selected.complexName || '위치 미지정') }}</b>
              <span v-if="selected.storageLocationCode" class="font-mono text-xs text-slate-400"> ({{ selected.storageLocationCode }})</span>
              <span v-if="selected.locationVerifiedAt" class="ml-1 text-xs text-emerald-600">✓ {{ selected.locationVerifiedBy }} 검증</span>
            </div>
          </div>

          <div v-if="detailLoading" class="card p-8 text-center text-sm text-slate-400">이력 불러오는 중…</div>

          <!-- 수량 변동 이력 -->
          <div v-else class="card p-4">
            <div class="mb-2 flex items-center justify-between">
              <h3 class="text-sm font-bold text-slate-700">수량 변동 이력 <span class="text-slate-400">({{ moves.length }})</span></h3>
              <button class="btn-ghost btn-sm" @click="exportCsv">CSV</button>
            </div>
            <div v-if="!moves.length" class="py-6 text-center text-sm text-slate-300">변동 이력이 없습니다.</div>
            <ul v-else class="divide-y divide-slate-50 text-sm">
              <li v-for="m in moves" :key="m.id" class="flex items-start justify-between gap-2 py-2">
                <div class="min-w-0">
                  <span class="badge" :class="typeMeta[m.type]?.c">{{ typeMeta[m.type]?.t || m.type }}</span>
                  <span class="ml-1 text-slate-400">{{ m.before }}→{{ m.after }}</span>
                  <span class="ml-1 font-bold" :class="m.delta >= 0 ? 'text-emerald-600' : 'text-rose-500'">{{ m.delta > 0 ? '+' : '' }}{{ m.delta }}</span>
                  <p class="mt-0.5 text-xs text-slate-500">
                    <span v-if="m.reason" class="badge mr-1 bg-amber-50 text-[10px] text-amber-700">{{ m.reason }}</span>{{ m.memo || '' }}
                  </p>
                </div>
                <div class="shrink-0 text-right text-xs text-slate-400">
                  <p class="text-slate-600">{{ m.byName }}</p>
                  <p>{{ fmtDateTime(m.at) }}</p>
                </div>
              </li>
            </ul>
          </div>

          <!-- 위치 변경 이력 -->
          <div v-if="!detailLoading" class="card p-4">
            <h3 class="mb-2 text-sm font-bold text-slate-700">위치 변경 이력 <span class="text-slate-400">({{ locLogs.length }})</span></h3>
            <div v-if="!locLogs.length" class="py-6 text-center text-sm text-slate-300">위치 변경 이력이 없습니다.</div>
            <ul v-else class="divide-y divide-slate-50 text-sm">
              <li v-for="l in locLogs" :key="l.id" class="flex items-center justify-between gap-2 py-2">
                <div class="min-w-0">
                  <p class="text-slate-700">📍 {{ l.fromLabel || '미지정' }} <span class="text-slate-300">→</span> {{ l.toLabel || '미지정' }}
                    <span v-if="l.storageLocationCode" class="font-mono text-xs text-slate-400">({{ l.storageLocationCode }})</span>
                  </p>
                </div>
                <div class="shrink-0 text-right text-xs text-slate-400">
                  <p class="text-slate-600">{{ l.byName }}</p>
                  <p>{{ fmtDateTime(l.at) }}</p>
                </div>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

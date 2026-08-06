<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { listAuditLogs, users } from '@/services/db'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import Pager from '@/components/ui/Pager.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import { usePagination } from '@/composables/usePagination'
import { fmtDateTime, fmtDate } from '@/utils/date'

const toast = useToast()

function todayStr() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

const date = ref(todayStr())
const fModule = ref('')
const fUser = ref('')
const logs = ref([])
const userList = ref([])
const loading = ref(true)

const MODULES = ['기준정보', '상품관리', 'SKU관리', '위치관리', '입/출고관리', '재고관리', '권한관리', '인증']
const actionMeta = {
  생성: 'bg-emerald-50 text-emerald-700',
  수정: 'bg-amber-50 text-amber-700',
  삭제: 'bg-rose-50 text-rose-600',
  입고: 'bg-emerald-50 text-emerald-700',
  출고: 'bg-sky-50 text-sky-700',
  재고조정: 'bg-amber-50 text-amber-700',
  재고실사: 'bg-violet-50 text-violet-700',
  위치변경: 'bg-indigo-50 text-indigo-700',
  교체: 'bg-violet-50 text-violet-700',
}
const moduleColor = {
  기준정보: 'bg-slate-100 text-slate-600',
  상품관리: 'bg-brand-50 text-brand-700',
  SKU관리: 'bg-brand-50 text-brand-700',
  위치관리: 'bg-teal-50 text-teal-700',
  '입/출고관리': 'bg-sky-50 text-sky-700',
  재고관리: 'bg-amber-50 text-amber-700',
  권한관리: 'bg-rose-50 text-rose-600',
  인증: 'bg-violet-50 text-violet-700',
}

async function load() {
  loading.value = true
  try {
    logs.value = await listAuditLogs({ date: date.value, module: fModule.value || undefined, byUserId: fUser.value || undefined })
  } catch (e) {
    toast.error('불러오기 실패: ' + (e.message || e.code))
  } finally {
    loading.value = false
  }
}
onMounted(async () => {
  try {
    userList.value = await users.list()
  } catch (e) {
    /* 무시 */
  }
  load()
})
watch([date, fModule, fUser], load)

function shiftDay(delta) {
  const d = new Date(date.value + 'T00:00:00')
  d.setDate(d.getDate() + delta)
  date.value = fmtDate(d)
}

const moduleCounts = computed(() => {
  const m = {}
  logs.value.forEach((l) => (m[l.module] = (m[l.module] || 0) + 1))
  return m
})
const { paged, page, pageSize, sizes, total, totalPages } = usePagination(logs, 30)

// 변경내용 diff — before/after 요약("k=v, k=v")을 파싱해 값이 바뀐 키만 추출
function parseKv(s) {
  const map = {}
  if (!s) return map
  for (const part of String(s).split(/,\s*/)) {
    const i = part.indexOf('=')
    if (i === -1) { if (part.trim()) map[part.trim()] = '' }
    else map[part.slice(0, i).trim()] = part.slice(i + 1).trim()
  }
  return map
}
function changes(l) {
  const hasB = !!l.beforeValue, hasA = !!l.afterValue
  const b = parseKv(l.beforeValue), a = parseKv(l.afterValue)
  const keys = [...new Set([...Object.keys(b), ...Object.keys(a)])]
  const out = []
  for (const k of keys) {
    const bv = b[k] ?? '', av = a[k] ?? ''
    if (hasB && hasA) { if (bv !== av) out.push({ k, b: bv, a: av }) }   // 수정: 바뀐 것만
    else if (hasA) out.push({ k, b: null, a: av })                       // 생성: 새 값
    else out.push({ k, b: bv, a: null })                                 // 삭제: 이전 값
  }
  return out
}
</script>

<template>
  <div>
    <PageHeader title="감사로그" subtitle="기준정보·상품·위치·입출고·재고 변경 이력 (누가·언제·무엇을). 하루 단위 조회." />

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <div class="flex items-center gap-1">
        <button class="btn-ghost btn-sm" @click="shiftDay(-1)">◀</button>
        <input v-model="date" type="date" class="input w-auto" />
        <button class="btn-ghost btn-sm" :disabled="date >= todayStr()" @click="shiftDay(1)">▶</button>
        <button class="btn-ghost btn-sm" @click="date = todayStr()">오늘</button>
      </div>
      <AppSelect v-model="fModule" class="w-auto">
        <option value="">전체 메뉴</option>
        <option v-for="m in MODULES" :key="m" :value="m">{{ m }}</option>
      </AppSelect>
      <AppSelect v-model="fUser" class="w-auto">
        <option value="">전체 관리자</option>
        <option v-for="u in userList" :key="u.id" :value="u.id">{{ u.displayName }}</option>
      </AppSelect>
      <AppSelect v-model="pageSize" class="w-auto sm:ml-auto">
        <option v-for="n in sizes" :key="n" :value="n">{{ n }}개씩</option>
      </AppSelect>
    </div>

    <p class="mb-2 text-xs text-slate-400">
      {{ date }} · 총 {{ logs.length }}건
      <span v-for="(c, m) in moduleCounts" :key="m" class="ml-1">· {{ m }} {{ c }}</span>
    </p>

    <div class="card">
      <div class="overflow-x-auto scrollbar-slim">
      <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
      <div v-else-if="!logs.length" class="p-10 text-center text-sm text-slate-400">해당 날짜의 변경 이력이 없습니다.</div>
      <table v-else class="w-full min-w-[640px] text-sm">
        <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th class="px-3 py-2.5 font-semibold">일시</th>
            <th class="px-3 py-2.5 font-semibold">메뉴</th>
            <th class="px-3 py-2.5 font-semibold">작업</th>
            <th class="px-3 py-2.5 font-semibold">대상</th>
            <th class="px-3 py-2.5 font-semibold">상품/SKU명</th>
            <th class="px-3 py-2.5 font-semibold">변경내용(전→후)</th>
            <th class="px-3 py-2.5 font-semibold">변경자</th>
            <th class="px-3 py-2.5 font-semibold">IP</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-for="l in paged" :key="l.id" class="hover:bg-slate-50/60">
            <td class="whitespace-nowrap px-3 py-2.5 text-xs text-slate-500">{{ fmtDateTime(l.at) }}</td>
            <td class="px-3 py-2.5"><span class="badge" :class="moduleColor[l.module] || 'bg-slate-100 text-slate-500'">{{ l.module }}</span></td>
            <td class="px-3 py-2.5"><span class="badge" :class="actionMeta[l.action] || 'bg-slate-100 text-slate-500'">{{ l.action }}</span></td>
            <td class="px-3 py-2.5"><span class="font-mono text-xs text-slate-500">{{ l.label || '—' }}</span></td>
            <td class="px-3 py-2.5 text-slate-700">{{ l.name || '—' }}</td>
            <td class="px-3 py-2.5 text-xs text-slate-500">
              <template v-if="changes(l).length">
                <div v-for="c in changes(l)" :key="c.k" class="whitespace-nowrap">
                  <span class="font-medium text-slate-500">{{ c.k }}</span>:
                  <span v-if="c.b !== null" :class="c.a !== null ? 'text-slate-400' : 'text-slate-700'">{{ c.b || '∅' }}</span>
                  <span v-if="c.b !== null && c.a !== null" class="mx-0.5 text-slate-300">→</span>
                  <span v-if="c.a !== null" class="text-slate-700">{{ c.a || '∅' }}</span>
                </div>
              </template>
              <span v-else class="text-slate-300">—</span>
            </td>
            <td class="whitespace-nowrap px-3 py-2.5 text-slate-600">{{ l.byName || l.name || '(알수없음)' }}</td>
            <td class="whitespace-nowrap px-3 py-2.5 font-mono text-xs text-slate-400">{{ l.ip || '—' }}</td>
          </tr>
        </tbody>
      </table>
      </div>
      <Pager v-if="logs.length" v-model:page="page" :total="total" :total-pages="totalPages" class="border-t border-slate-100" />
    </div>
  </div>
</template>

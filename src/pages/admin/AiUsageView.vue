<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { aiUsage } from '@/services/db'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppSelect from '@/components/ui/AppSelect.vue'

const toast = useToast()
const days = ref(0) // 기본: 오늘
const data = ref(null)
const loading = ref(true) // 최초 로딩 전 깜빡임 방지

// USD / 1M tokens (근사치 — 실제 단가는 변동될 수 있음)
const PRICE = {
  chat: { in: 0.15, out: 0.6 },
  find_similar: { in: 0.15, out: 0.6 },
  quote_extract: { in: 2.5, out: 10.0 },
}
const USD_KRW = 1400

function featureLabel(f) {
  return { chat: '챗봇', find_similar: '사진검색', quote_extract: '견적추출', tts: '음성(TTS)' }[f] || f
}
function featureCostUsd(row) {
  const p = PRICE[row.feature]
  if (!p) return 0
  return (row.promptTokens / 1e6) * p.in + (row.completionTokens / 1e6) * p.out
}
const totalCostUsd = computed(() => (data.value?.byFeature || []).reduce((s, r) => s + featureCostUsd(r), 0))

async function load() {
  loading.value = true
  try {
    data.value = await aiUsage.summary(days.value)
  } catch (e) {
    toast.error('사용량 조회 실패: ' + (e.message || e.code))
  } finally {
    loading.value = false
  }
}
function fmt(n) { return (Number(n) || 0).toLocaleString() }
function usd(n) { return '$' + (Number(n) || 0).toFixed(2) }
function krw(n) { return Math.round((Number(n) || 0) * USD_KRW).toLocaleString() + '원' }
watch(days, load) // 기간 바꾸면 확실히 재조회
onMounted(load)
</script>

<template>
  <PageHeader title="AI 사용량" subtitle="계정별·기능별 토큰 사용량과 예상 비용 (관리자 전용)">
    <AppSelect v-model="days" class="w-auto">
      <option :value="0">오늘</option>
      <option :value="7">최근 7일</option>
      <option :value="30">최근 30일</option>
      <option :value="90">최근 90일</option>
    </AppSelect>
  </PageHeader>

  <div v-if="loading" class="card p-8 text-center text-sm text-slate-400">불러오는 중…</div>
  <template v-else-if="data">
    <!-- 요약 -->
    <div class="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
      <div class="card p-4"><p class="text-xs text-slate-400">총 토큰</p><p class="text-2xl font-bold text-slate-800">{{ fmt(data.totalTokens) }}</p></div>
      <div class="card p-4"><p class="text-xs text-slate-400">총 호출</p><p class="text-2xl font-bold text-slate-800">{{ fmt(data.totalCalls) }}</p></div>
      <div class="card p-4"><p class="text-xs text-slate-400">TTS 문자</p><p class="text-2xl font-bold text-slate-800">{{ fmt(data.totalChars) }}</p></div>
      <div class="card p-4"><p class="text-xs text-slate-400">예상 비용(근사)</p><p class="text-2xl font-bold text-brand-600">{{ usd(totalCostUsd) }}</p><p class="text-[11px] text-slate-400">≈ {{ krw(totalCostUsd) }}</p></div>
    </div>

    <!-- 기능별 -->
    <div class="card mb-4">
      <div class="border-b border-slate-100 px-4 py-2.5 text-sm font-semibold text-slate-700">기능별</div>
      <table class="w-full text-sm">
        <thead class="bg-slate-50 text-left text-xs text-slate-500">
          <tr><th class="px-4 py-2 font-semibold">기능</th><th class="px-4 py-2 text-right font-semibold">호출</th><th class="px-4 py-2 text-right font-semibold">토큰</th><th class="px-4 py-2 text-right font-semibold">예상비용</th></tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-for="f in data.byFeature" :key="f.feature">
            <td class="px-4 py-2">{{ featureLabel(f.feature) }}<span v-if="f.feature === 'tts'" class="ml-1 text-[11px] text-slate-400">{{ fmt(f.charCount) }}자</span></td>
            <td class="px-4 py-2 text-right">{{ fmt(f.calls) }}</td>
            <td class="px-4 py-2 text-right">{{ fmt(f.totalTokens) }}</td>
            <td class="px-4 py-2 text-right">{{ PRICE[f.feature] ? usd(featureCostUsd(f)) : '—' }}</td>
          </tr>
          <tr v-if="!data.byFeature.length"><td colspan="4" class="px-4 py-6 text-center text-slate-400">사용 기록 없음</td></tr>
        </tbody>
      </table>
    </div>

    <!-- 계정별 -->
    <div class="card">
      <div class="border-b border-slate-100 px-4 py-2.5 text-sm font-semibold text-slate-700">계정별</div>
      <div class="overflow-x-auto scrollbar-slim">
        <table class="w-full min-w-[560px] text-sm">
          <thead class="bg-slate-50 text-left text-xs text-slate-500">
            <tr><th class="px-4 py-2 font-semibold">사용자</th><th class="px-4 py-2 text-right font-semibold">호출</th><th class="px-4 py-2 text-right font-semibold">프롬프트</th><th class="px-4 py-2 text-right font-semibold">완성</th><th class="px-4 py-2 text-right font-semibold">총 토큰</th></tr>
          </thead>
          <tbody class="divide-y divide-slate-50">
            <tr v-for="u in data.byUser" :key="u.userId || u.userName" class="hover:bg-slate-50/60">
              <td class="px-4 py-2 font-medium text-slate-800">{{ u.userName || '(알 수 없음)' }}</td>
              <td class="px-4 py-2 text-right">{{ fmt(u.calls) }}</td>
              <td class="px-4 py-2 text-right text-slate-500">{{ fmt(u.promptTokens) }}</td>
              <td class="px-4 py-2 text-right text-slate-500">{{ fmt(u.completionTokens) }}</td>
              <td class="px-4 py-2 text-right font-medium">{{ fmt(u.totalTokens) }}</td>
            </tr>
            <tr v-if="!data.byUser.length"><td colspan="5" class="px-4 py-6 text-center text-slate-400">사용 기록 없음</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <p class="mt-3 text-[11px] text-slate-400">
      ※ 예상 비용은 근사치입니다 (챗봇·사진검색=gpt-4o-mini, 견적추출=gpt-4o 단가, 환율 {{ USD_KRW }}원 가정).
      TTS(MiniMax)는 문자 기반이라 비용 미포함. 실제 청구는 공급사 대시보드 기준.
    </p>
  </template>
</template>

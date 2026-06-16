<script setup>
import { computed } from 'vue'

const props = defineProps({
  page: { type: Number, required: true },
  totalPages: { type: Number, required: true },
  total: { type: Number, default: 0 },
})
const emit = defineEmits(['update:page'])

// 현재 페이지 주변 번호 윈도우 (최대 7개)
const pages = computed(() => {
  const tp = props.totalPages
  const cur = props.page
  const win = 5
  let start = Math.max(1, cur - Math.floor(win / 2))
  let end = Math.min(tp, start + win - 1)
  start = Math.max(1, end - win + 1)
  const arr = []
  for (let i = start; i <= end; i++) arr.push(i)
  return arr
})
function go(p) {
  if (p >= 1 && p <= props.totalPages && p !== props.page) emit('update:page', p)
}
</script>

<template>
  <div class="no-print flex items-center justify-between gap-2 px-3 py-2.5">
    <span class="text-xs text-slate-400">총 {{ total }}건 · {{ page }}/{{ totalPages }}p</span>
    <div v-if="totalPages > 1" class="flex items-center gap-0.5 text-sm">
      <button class="rounded px-2 py-1 text-slate-500 hover:bg-slate-100 disabled:opacity-30" :disabled="page <= 1" @click="go(1)">«</button>
      <button class="rounded px-2 py-1 text-slate-500 hover:bg-slate-100 disabled:opacity-30" :disabled="page <= 1" @click="go(page - 1)">‹</button>
      <button
        v-for="p in pages"
        :key="p"
        class="min-w-[28px] rounded px-2 py-1 font-medium"
        :class="p === page ? 'bg-brand-600 text-white' : 'text-slate-600 hover:bg-slate-100'"
        @click="go(p)"
      >
        {{ p }}
      </button>
      <button class="rounded px-2 py-1 text-slate-500 hover:bg-slate-100 disabled:opacity-30" :disabled="page >= totalPages" @click="go(page + 1)">›</button>
      <button class="rounded px-2 py-1 text-slate-500 hover:bg-slate-100 disabled:opacity-30" :disabled="page >= totalPages" @click="go(totalPages)">»</button>
    </div>
  </div>
</template>

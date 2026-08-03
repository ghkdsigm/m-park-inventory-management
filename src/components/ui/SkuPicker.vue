<script setup>
/**
 * 검색 가능한 SKU 선택기 — 셀렉트처럼 쓰되, 드롭다운 상단 검색으로 자동 필터.
 * 사용: <SkuPicker v-model="skuId" :skus="skuList" placeholder="— 미연결 —" @change="..." />
 *   skus: [{ id, code, productName, spec }]. 빈값('')이면 미연결.
 */
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'

defineOptions({ inheritAttrs: false })
const props = defineProps({
  modelValue: { type: [String, null], default: '' },
  skus: { type: Array, default: () => [] },
  placeholder: { type: String, default: '— 미연결 —' },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'change'])

const open = ref(false)
const q = ref('')
const btnRef = ref(null)
const menuRef = ref(null)
const searchRef = ref(null)
const menuStyle = ref({})

const selectedLabel = computed(() => {
  const s = props.skus.find((x) => x.id === props.modelValue)
  return s ? `${s.code} · ${s.productName}` : ''
})
const filtered = computed(() => {
  const s = q.value.trim().toLowerCase()
  if (!s) return props.skus
  return props.skus.filter((x) => `${x.code} ${x.productName} ${x.spec || ''}`.toLowerCase().includes(s))
})

async function toggle() {
  if (props.disabled) return
  open.value = !open.value
  if (open.value) { q.value = ''; await nextTick(); position(); searchRef.value?.focus() }
}
function position() {
  const el = btnRef.value
  if (!el) return
  const r = el.getBoundingClientRect()
  const belowSpace = window.innerHeight - r.bottom
  const openUp = belowSpace < 300 && r.top > belowSpace
  const width = Math.max(r.width, 280)
  menuStyle.value = {
    position: 'fixed', left: Math.min(r.left, window.innerWidth - width - 8) + 'px', width: width + 'px', zIndex: 90,
    ...(openUp ? { bottom: (window.innerHeight - r.top + 4) + 'px' } : { top: (r.bottom + 4) + 'px' }),
  }
}
function close() { open.value = false }
function pick(id) { emit('update:modelValue', id); emit('change', id); close() }
function onDocClick(e) {
  if (!open.value) return
  if (btnRef.value?.contains(e.target) || menuRef.value?.contains(e.target)) return
  close()
}
function onKey(e) { if (e.key === 'Escape') close() }
function onScroll(e) {
  if (!open.value) return
  if (menuRef.value && (e.target === menuRef.value || menuRef.value.contains?.(e.target))) return
  close()
}
onMounted(() => {
  document.addEventListener('mousedown', onDocClick, true)
  document.addEventListener('keydown', onKey, true)
  window.addEventListener('scroll', onScroll, true)
  window.addEventListener('resize', close)
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocClick, true)
  document.removeEventListener('keydown', onKey, true)
  window.removeEventListener('scroll', onScroll, true)
  window.removeEventListener('resize', close)
})
</script>

<template>
  <button
    ref="btnRef"
    type="button"
    v-bind="$attrs"
    :disabled="disabled"
    class="inline-flex items-center justify-between gap-2 rounded-lg border border-slate-300 bg-white px-3 py-2 text-left text-sm outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-inset focus:ring-brand-200 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-400"
    :class="open ? 'border-brand-500 ring-2 ring-inset ring-brand-200' : ''"
    @click.stop="toggle"
  >
    <span class="min-w-0 truncate" :class="selectedLabel ? 'text-slate-700' : 'text-slate-400'">{{ selectedLabel || placeholder }}</span>
    <svg class="h-4 w-4 shrink-0 text-slate-400 transition" :class="open ? 'rotate-180' : ''" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" /></svg>
  </button>

  <Teleport to="body">
    <div v-if="open" ref="menuRef" :style="menuStyle" class="rounded-lg border border-slate-200 bg-white shadow-lg">
      <div class="border-b border-slate-100 p-2">
        <input ref="searchRef" v-model="q" class="input h-8 w-full text-sm" placeholder="코드·상품명·규격 검색" @click.stop @keydown.stop />
      </div>
      <div class="max-h-56 overflow-y-auto py-1 scrollbar-slim">
        <button
          type="button"
          class="flex w-full items-center px-3 py-2 text-left text-sm"
          :class="!modelValue ? 'bg-brand-50 font-medium text-brand-700' : 'text-slate-500 hover:bg-slate-50'"
          @click.stop="pick('')"
        >{{ placeholder }}</button>
        <button
          v-for="s in filtered"
          :key="s.id"
          type="button"
          class="flex w-full flex-col items-start px-3 py-1.5 text-left text-sm"
          :class="s.id === modelValue ? 'bg-brand-50 text-brand-700' : 'text-slate-700 hover:bg-slate-50'"
          @click.stop="pick(s.id)"
        >
          <span class="font-medium">{{ s.code }} · {{ s.productName }}</span>
          <span v-if="s.spec" class="text-[11px] text-slate-400">{{ s.spec }}</span>
        </button>
        <div v-if="!filtered.length" class="px-3 py-3 text-center text-sm text-slate-300">검색 결과 없음</div>
      </div>
    </div>
  </Teleport>
</template>

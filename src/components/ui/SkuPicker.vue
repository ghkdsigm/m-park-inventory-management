<script setup>
/**
 * 검색 가능한 SKU 선택기 — 서버 검색(managePage) + 선택값 자체 조회(listByIds).
 * 전체 SKU 를 미리 로드하지 않아 수천 개여도 가볍다.
 * 사용: <SkuPicker v-model="skuId" :initial-label="label?" placeholder="— 미연결 —" @change="..." />
 *   빈값('')이면 미연결.
 */
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { skus } from '@/services/db'

defineOptions({ inheritAttrs: false })
const props = defineProps({
  modelValue: { type: [String, null], default: '' },
  initialLabel: { type: String, default: '' }, // 이미 아는 라벨이 있으면 조회 생략
  placeholder: { type: String, default: '— 미연결 —' },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'change'])

const open = ref(false)
const q = ref('')
const results = ref([])
const listing = ref(false)
const selectedLabel = ref(props.initialLabel || '')
const btnRef = ref(null)
const menuRef = ref(null)
const searchRef = ref(null)
const menuStyle = ref({})

function labelOf(s) { return s ? `${s.code} · ${s.productName}${s.spec ? ' (' + s.spec + ')' : ''}` : '' }

// 선택된 SKU 라벨 — initialLabel 있으면 그걸, 없으면 id 로 1건 조회
async function resolveSelected() {
  if (!props.modelValue) { selectedLabel.value = ''; return }
  if (props.initialLabel) { selectedLabel.value = props.initialLabel; return }
  try { const arr = await skus.listByIds([props.modelValue]); selectedLabel.value = labelOf(arr?.[0]) }
  catch (_) { selectedLabel.value = '' }
}
watch(() => props.modelValue, resolveSelected, { immediate: true })
watch(() => props.initialLabel, (v) => { if (v && props.modelValue) selectedLabel.value = v })

let searchTimer = null
async function fetchList() {
  listing.value = true
  try { const r = await skus.managePage({ search: q.value.trim(), page: 1, pageSize: 20 }); results.value = r.rows || [] }
  catch (_) { results.value = [] } finally { listing.value = false }
}
watch(q, () => { clearTimeout(searchTimer); searchTimer = setTimeout(fetchList, 300) })

async function toggle() {
  if (props.disabled) return
  open.value = !open.value
  if (open.value) { q.value = ''; await fetchList(); await nextTick(); position(); searchRef.value?.focus() }
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
function pick(s) {
  const id = s ? s.id : ''
  selectedLabel.value = labelOf(s)
  emit('update:modelValue', id)
  emit('change', id)
  close()
}
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
        <button type="button" class="flex w-full items-center px-3 py-2 text-left text-sm"
          :class="!modelValue ? 'bg-brand-50 font-medium text-brand-700' : 'text-slate-500 hover:bg-slate-50'"
          @click.stop="pick(null)">{{ placeholder }}</button>
        <div v-if="listing" class="px-3 py-2 text-sm text-slate-300">검색 중…</div>
        <button
          v-for="s in results"
          :key="s.id"
          type="button"
          class="flex w-full flex-col items-start px-3 py-1.5 text-left text-sm"
          :class="s.id === modelValue ? 'bg-brand-50 text-brand-700' : 'text-slate-700 hover:bg-slate-50'"
          @click.stop="pick(s)"
        >
          <span class="font-medium">{{ s.code }} · {{ s.productName }}</span>
          <span v-if="s.spec" class="text-[11px] text-slate-400">{{ s.spec }}</span>
        </button>
        <div v-if="!listing && !results.length" class="px-3 py-3 text-center text-sm text-slate-300">검색 결과 없음</div>
      </div>
    </div>
  </Teleport>
</template>

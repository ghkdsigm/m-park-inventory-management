<script setup>
/**
 * 공통 셀렉트 — 네이티브 <select> 대신 커스텀 드롭다운으로 옵션 목록 외형을 완전 통제(보더/hover/선택 일관).
 * 사용법: <select> 를 <AppSelect> 로 바꾸고 `.input` 클래스만 제거. 내부 <option> 은 그대로 둔다.
 *   <AppSelect v-model="x" class="w-auto">
 *     <option value="">전체</option>
 *     <option v-for="o in list" :key="o.id" :value="o.id">{{ o.name }}</option>
 *   </AppSelect>
 */
import { ref, useSlots, onMounted, onUpdated, onBeforeUnmount, nextTick } from 'vue'

defineOptions({ inheritAttrs: false })
const props = defineProps({
  modelValue: { type: [String, Number, Boolean, null], default: '' },
  disabled: { type: Boolean, default: false },
  placeholder: { type: String, default: '선택' },
  // true 면 최소너비 측정을 끄고 컨테이너(w-full)를 그대로 따른다(라벨은 truncate).
  // 옵션 텍스트가 길고 많은 셀렉트가 폭을 넘어 늘어나는 것 방지.
  fluid: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'change'])
const slots = useSlots()

const open = ref(false)
const btnRef = ref(null)
const menuRef = ref(null)
const menuStyle = ref({})

/* ---- 슬롯의 <option> vnode 파싱 ---- */
function textOf(ch) {
  if (ch == null || ch === false) return ''
  if (typeof ch === 'string' || typeof ch === 'number') return String(ch)
  if (Array.isArray(ch)) return ch.map(textOf).join('')
  if (typeof ch === 'object' && 'children' in ch) return textOf(ch.children)
  return ''
}
function collect(nodes, out) {
  const arr = Array.isArray(nodes) ? nodes : [nodes]
  for (const n of arr) {
    if (n == null || typeof n !== 'object') continue
    if (n.type === 'option') {
      const d = n.props?.disabled
      out.push({
        value: n.props?.value ?? '',
        label: textOf(n.children).trim(),
        disabled: d !== undefined && d !== null && d !== false,
      })
    } else if (n.children && typeof n.children === 'object') {
      collect(n.children, out) // Fragment(v-for/v-if) 등 하위 탐색
    }
  }
}
function options() {
  const out = []
  try { collect(slots.default ? slots.default() : [], out) } catch (e) { /* */ }
  return out
}
function selectedLabel() {
  const opts = options()
  const hit = opts.find((o) => o.value === props.modelValue)
  return hit ? hit.label : ''
}

/* 가장 긴 옵션 너비를 실제 측정해 최소너비 고정(선택값에 따라 폭이 안 변하게) */
const minW = ref(0)
let _ctx = null
function measure() {
  if (props.fluid) { if (minW.value !== 0) minW.value = 0; return }
  const btn = btnRef.value
  if (!btn) return
  const opts = options()
  if (!opts.length) { minW.value = 0; return }
  const cs = getComputedStyle(btn)
  if (!_ctx) _ctx = document.createElement('canvas').getContext('2d')
  _ctx.font = `${cs.fontWeight} ${cs.fontSize} ${cs.fontFamily}`
  let max = 0
  for (const o of opts) { const w = _ctx.measureText(o.label || props.placeholder).width; if (w > max) max = w }
  // 좌우 패딩(px-3=24) + gap(8) + 화살표(16) + 여유(2)
  const next = Math.ceil(max) + 50
  if (next !== minW.value) minW.value = next
}

/* ---- 열기/닫기/위치 ---- */
async function toggle() {
  if (props.disabled) return
  open.value = !open.value
  if (open.value) { await nextTick(); position() }
}
function position() {
  const el = btnRef.value
  if (!el) return
  const r = el.getBoundingClientRect()
  const belowSpace = window.innerHeight - r.bottom
  const openUp = belowSpace < 240 && r.top > belowSpace
  menuStyle.value = {
    position: 'fixed',
    left: r.left + 'px',
    width: r.width + 'px',
    zIndex: 80,
    ...(openUp ? { bottom: (window.innerHeight - r.top + 4) + 'px' } : { top: (r.bottom + 4) + 'px' }),
  }
}
function close() { open.value = false }
function pick(o) { if (o.disabled) return; emit('update:modelValue', o.value); emit('change', o.value); close() }
function onDocClick(e) {
  if (!open.value) return
  if (btnRef.value?.contains(e.target) || menuRef.value?.contains(e.target)) return
  close()
}
function onKey(e) { if (e.key === 'Escape') close() }
// 바깥(페이지/컨테이너) 스크롤이면 닫고, 드롭다운 목록 내부 스크롤은 무시
function onScroll(e) {
  if (!open.value) return
  if (menuRef.value && (e.target === menuRef.value || menuRef.value.contains?.(e.target))) return
  close()
}
onMounted(() => {
  measure()
  document.addEventListener('mousedown', onDocClick, true)
  document.addEventListener('keydown', onKey, true)
  window.addEventListener('scroll', onScroll, true)
  window.addEventListener('resize', close)
})
onUpdated(measure) // 옵션(슬롯) 변경 시 최소너비 재측정
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
    :style="minW ? { minWidth: minW + 'px' } : null"
    class="inline-flex items-center justify-between gap-2 rounded-lg border border-slate-300 bg-white px-3 py-2 text-left text-sm outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-inset focus:ring-brand-200 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-400"
    :class="open ? 'border-brand-500 ring-2 ring-inset ring-brand-200' : ''"
    @click.stop="toggle"
  >
    <span class="min-w-0 truncate" :class="selectedLabel() ? 'text-slate-700' : 'text-slate-400'">{{ selectedLabel() || placeholder }}</span>
    <svg class="h-4 w-4 shrink-0 text-slate-400 transition" :class="open ? 'rotate-180' : ''" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" /></svg>
  </button>

  <Teleport to="body">
    <div
      v-if="open"
      ref="menuRef"
      :style="menuStyle"
      class="max-h-60 overflow-y-auto rounded-lg border border-slate-200 bg-white py-1 shadow-lg scrollbar-slim"
    >
      <button
        v-for="(o, i) in options()"
        :key="i"
        type="button"
        class="flex w-full items-center px-3 py-2 text-left text-sm"
        :class="[
          o.value === modelValue ? 'bg-brand-50 font-medium text-brand-700' : 'text-slate-700 hover:bg-slate-50',
          o.disabled ? 'cursor-not-allowed text-slate-300 hover:bg-transparent' : '',
        ]"
        @click.stop="pick(o)"
      >
        {{ o.label }}
      </button>
      <div v-if="!options().length" class="px-3 py-2 text-sm text-slate-300">항목 없음</div>
    </div>
  </Teleport>
</template>

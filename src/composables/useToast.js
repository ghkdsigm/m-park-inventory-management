import { reactive } from 'vue'

// 전역 토스트 상태 (간단한 알림용)
const state = reactive({ items: [] })
let seq = 0

export function useToast() {
  function push(message, type = 'info', timeout = 2800) {
    const id = ++seq
    state.items.push({ id, message, type })
    setTimeout(() => remove(id), timeout)
  }
  function remove(id) {
    const i = state.items.findIndex((t) => t.id === id)
    if (i > -1) state.items.splice(i, 1)
  }
  return {
    items: state.items,
    success: (m) => push(m, 'success'),
    error: (m) => push(m, 'error', 4000),
    info: (m) => push(m, 'info'),
    remove,
  }
}

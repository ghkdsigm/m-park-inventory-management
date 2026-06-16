import { ref, computed, watch } from 'vue'

/**
 * 클라이언트 페이지네이션.
 * @param {import('vue').Ref<Array>|import('vue').ComputedRef<Array>} itemsRef  필터링된 전체 목록
 * @param {number} defaultSize 기본 페이지 크기
 */
export function usePagination(itemsRef, defaultSize = 10) {
  const sizes = [10, 30, 50]
  const pageSize = ref(defaultSize)
  const page = ref(1)
  const total = computed(() => (itemsRef.value ? itemsRef.value.length : 0))
  const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
  const paged = computed(() => {
    const start = (page.value - 1) * pageSize.value
    return (itemsRef.value || []).slice(start, start + pageSize.value)
  })
  // 필터/페이지크기 변경 시 1페이지로
  watch(itemsRef, () => { page.value = 1 })
  watch(pageSize, () => { page.value = 1 })

  return { page, pageSize, sizes, total, totalPages, paged }
}

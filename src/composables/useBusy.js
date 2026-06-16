import { ref } from 'vue'

/**
 * 비동기 작업 재진입 방지 가드.
 * 버튼 더블클릭/엔터 연타로 인한 중복 저장을 막는다.
 *
 * 사용:
 *   const { busy, run } = useBusy()
 *   // 템플릿:  @click="run(save)"   :disabled="busy"
 *   // run(fn) 은 이미 실행 중이면 무시한다.
 */
export function useBusy() {
  const busy = ref(false)
  async function run(fn) {
    if (busy.value) return
    busy.value = true
    try {
      return await fn()
    } finally {
      busy.value = false
    }
  }
  return { busy, run }
}

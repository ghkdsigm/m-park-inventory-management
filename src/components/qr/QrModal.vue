<script setup>
import { ref, computed, watch } from 'vue'
import BaseModal from '@/components/ui/BaseModal.vue'
import { makeQrDataUrl, buildSkuUrl } from '@/services/qr'
import { useToast } from '@/composables/useToast'
import { specText } from '@/utils/sku'

const props = defineProps({
  modelValue: Boolean,
  sku: Object, // { code, productName, spec, qty, pathLabel, complexName, locationLabel, storageLocationCode }
})

// 위치 표시 문자열 (단지 › 구역 › 상세구역 › 명칭)
const locText = computed(() => {
  const s = props.sku
  if (!s) return ''
  if (s.locationLabel) return `${s.complexName} › ${s.locationLabel}`
  if (s.complexName) return `${s.complexName} · 위치 미지정`
  return '위치 미지정'
})
const emit = defineEmits(['update:modelValue'])
const toast = useToast()

const qr = ref('')
const url = ref('')
const loading = ref(false)

watch(
  () => props.modelValue,
  async (v) => {
    if (v && props.sku) {
      loading.value = true
      url.value = buildSkuUrl(props.sku.code)
      qr.value = await makeQrDataUrl(props.sku.code, { scale: 8 })
      loading.value = false
    }
  }
)

async function copyUrl() {
  await navigator.clipboard.writeText(url.value)
  toast.success('URL이 복사되었습니다.')
}
function download() {
  const a = document.createElement('a')
  a.href = qr.value
  a.download = `QR_${props.sku.code}.png`
  a.click()
}
</script>

<template>
  <BaseModal :model-value="modelValue" title="SKU QR 코드" size="sm" @update:model-value="emit('update:modelValue', $event)">
    <div v-if="sku" class="text-center">
      <div class="mx-auto mb-3 w-fit rounded-xl border border-slate-200 bg-white p-3">
        <div v-if="loading" class="flex h-44 w-44 items-center justify-center text-sm text-slate-400">생성 중…</div>
        <img v-else :src="qr" alt="QR" class="h-44 w-44" />
      </div>
      <p class="font-mono text-lg font-bold text-slate-800">{{ sku.code }}</p>
      <p class="mt-0.5 text-sm text-slate-600">{{ sku.productName }}<span v-if="specText(sku)"> · {{ specText(sku) }}</span></p>
      <p class="mt-0.5 text-xs text-slate-400">{{ sku.pathLabel }}</p>
      <p class="mt-1 text-xs font-medium text-slate-600">📍 {{ locText }}<span v-if="sku.storageLocationCode" class="font-mono text-slate-400"> ({{ sku.storageLocationCode }})</span></p>
      <p class="mt-1 text-xs text-slate-500">현재 재고 {{ sku.qty }}개</p>

      <div class="mt-3 flex items-center justify-center gap-2 rounded-lg bg-slate-50 px-3 py-2">
        <span class="truncate text-xs text-slate-500">{{ url }}</span>
        <button class="btn-ghost btn-sm shrink-0" @click="copyUrl">복사</button>
      </div>
    </div>
    <template #footer>
      <button class="btn-ghost" @click="download">이미지 저장</button>
      <button class="btn-primary" @click="emit('update:modelValue', false)">닫기</button>
    </template>
  </BaseModal>
</template>

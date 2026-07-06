<script setup>
import { ref } from 'vue'
import { uploadImage, deleteImageByUrl } from '@/services/storage'
import { NO_IMAGE, compressImage } from '@/utils/image'
import { useToast } from '@/composables/useToast'

const props = defineProps({
  modelValue: { type: String, default: '' },
  prefix: { type: String, default: 'images' },
  size: { type: String, default: 'md' }, // sm | md
})
const emit = defineEmits(['update:modelValue'])
const toast = useToast()

const uploading = ref(false)
const fileInput = ref(null)
const sizes = { sm: 'h-20 w-20', md: 'h-28 w-28' }

function pick() {
  fileInput.value?.click()
}
async function onFile(e) {
  const file = e.target.files?.[0]
  if (!file) return
  uploading.value = true
  try {
    const old = props.modelValue
    const compressed = await compressImage(file) // 업로드 전 리사이즈/압축
    const { url } = await uploadImage(compressed, props.prefix)
    emit('update:modelValue', url)
    if (old) deleteImageByUrl(old) // 교체 시 이전 파일 정리
    toast.success('이미지 업로드 완료')
  } catch (err) {
    toast.error('업로드 실패: ' + (err.message || err.code))
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}
function remove() {
  const old = props.modelValue
  emit('update:modelValue', '')
  if (old) deleteImageByUrl(old)
}
</script>

<template>
  <div class="flex items-center gap-3">
    <div class="relative shrink-0 overflow-hidden rounded-lg border border-slate-200 bg-slate-50" :class="sizes[size]">
      <img :src="modelValue || NO_IMAGE" class="h-full w-full object-cover" alt="이미지" />
      <div v-if="uploading" class="absolute inset-0 flex items-center justify-center bg-white/70 text-xs text-slate-500">업로드 중…</div>
    </div>
    <div class="flex flex-col gap-1.5">
      <input ref="fileInput" type="file" accept="image/*" class="hidden" @change="onFile" />
      <button type="button" class="btn-ghost btn-sm" :disabled="uploading" @click="pick">
        {{ modelValue ? '이미지 변경' : '이미지 업로드' }}
      </button>
      <button v-if="modelValue" type="button" class="btn-ghost btn-sm text-rose-600" :disabled="uploading" @click="remove">삭제</button>
      <p class="text-[11px] text-slate-400">JPG/PNG · 업로드 시 자동 리사이즈(최대 1280px)</p>
    </div>
  </div>
</template>

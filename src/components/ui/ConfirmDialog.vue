<script setup>
import { ref } from 'vue'
import BaseModal from './BaseModal.vue'

const open = ref(false)
const opts = ref({ title: '확인', message: '', confirmText: '확인', danger: false })
let resolver = null

function ask(o = {}) {
  opts.value = { title: '확인', message: '', confirmText: '확인', danger: false, ...o }
  open.value = true
  return new Promise((resolve) => (resolver = resolve))
}
function decide(v) {
  open.value = false
  resolver?.(v)
}

defineExpose({ ask })
</script>

<template>
  <BaseModal v-model="open" :title="opts.title" size="sm">
    <p class="whitespace-pre-line text-sm text-slate-600">{{ opts.message }}</p>
    <template #footer>
      <button class="btn-ghost" @click="decide(false)">취소</button>
      <button :class="opts.danger ? 'btn-danger' : 'btn-primary'" @click="decide(true)">
        {{ opts.confirmText }}
      </button>
    </template>
  </BaseModal>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import ToastHost from '@/components/ui/ToastHost.vue'

const auth = useAuthStore()
const booting = ref(true)

onMounted(async () => {
  await auth.init()
  booting.value = false
})
</script>

<template>
  <div v-if="booting" class="flex h-full items-center justify-center">
    <div class="flex flex-col items-center gap-3 text-slate-400">
      <svg class="h-8 w-8 animate-spin text-brand-500" viewBox="0 0 24 24" fill="none">
        <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
        <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
      </svg>
      <span class="text-sm">불러오는 중…</span>
    </div>
  </div>
  <RouterView v-else />
  <ToastHost />
</template>

<script setup>
defineProps({
  modelValue: Boolean,
  title: { type: String, default: '' },
  size: { type: String, default: 'md' }, // sm | md | lg
})
const emit = defineEmits(['update:modelValue'])
const sizes = { sm: 'max-w-sm', md: 'max-w-lg', lg: 'max-w-2xl' }
</script>

<template>
  <Teleport to="body">
    <Transition name="fade">
      <div
        v-if="modelValue"
        class="no-print fixed inset-0 z-50 flex items-end justify-center bg-slate-900/50 p-0 sm:items-center sm:p-4"
      >
        <div
          class="card w-full rounded-b-none sm:rounded-b-xl"
          :class="sizes[size]"
          @click.stop
        >
          <div class="flex items-center justify-between border-b border-slate-100 px-5 py-3.5">
            <h3 class="text-base font-semibold text-slate-800">{{ title }}</h3>
            <button
              class="rounded-md p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600"
              @click="emit('update:modelValue', false)"
            >
              <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M6 6l12 12M18 6L6 18" stroke-linecap="round" />
              </svg>
            </button>
          </div>
          <div class="max-h-[70vh] overflow-y-auto px-5 py-4 scrollbar-slim">
            <slot />
          </div>
          <div v-if="$slots.footer" class="flex justify-end gap-2 border-t border-slate-100 px-5 py-3">
            <slot name="footer" />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>

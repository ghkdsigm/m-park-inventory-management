<script setup>
defineProps({
  title: String,
  subtitle: { type: String, default: '' },
})
</script>

<template>
  <div class="no-print mb-4 flex flex-wrap items-end justify-between gap-3">
    <div>
      <h1 class="text-lg font-bold text-slate-800 sm:text-xl">{{ title }}</h1>
      <p v-if="subtitle" class="mt-0.5 text-sm text-slate-400">{{ subtitle }}</p>
    </div>
    <div class="flex items-center gap-2">
      <slot />
    </div>
  </div>
</template>

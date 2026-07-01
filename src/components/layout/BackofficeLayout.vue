<script setup>
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const drawer = ref(false)

// 전체 네비게이션 정의 (group 으로 묶음)
const allNav = computed(() => [
  { name: 'dashboard', label: '대시보드', icon: 'grid', to: { name: 'dashboard' }, group: '' },

  { name: 'categories', label: '카테고리관리', icon: 'folder', to: { name: 'categories' }, group: '기준정보관리', admin: true },
  { name: 'productCodes', label: '제품코드관리', icon: 'tag', to: { name: 'productCodes' }, group: '기준정보관리', admin: true },
  { name: 'productDetails', label: '제품상세코드관리', icon: 'detail', to: { name: 'productDetails' }, group: '기준정보관리', admin: true },

  { name: 'products', label: '상품관리', icon: 'box', to: { name: 'products' }, group: '상품관리', admin: true },
  { name: 'skus', label: 'SKU관리', icon: 'sku', to: { name: 'skus' }, group: '상품관리', admin: true },

  { name: 'complexes', label: '단지관리', icon: 'building', to: { name: 'complexes' }, group: '위치관리', admin: true },
  { name: 'locations', label: '위치코드관리', icon: 'pin', to: { name: 'locations' }, group: '위치관리', admin: true },
  { name: 'storageLocations', label: '보관위치관리', icon: 'box', to: { name: 'storageLocations' }, group: '위치관리', admin: true },

  { name: 'inbound', label: '입고관리', icon: 'inbound', to: { name: 'inbound' }, group: '입/출고관리', stock: true },
  { name: 'outbound', label: '출고관리', icon: 'outbound', to: { name: 'outbound' }, group: '입/출고관리', stock: true },
  { name: 'transfer', label: '재고이동', icon: 'transfer', to: { name: 'transfer' }, group: '입/출고관리', stock: true },
  { name: 'history', label: '입출고통합조회', icon: 'history', to: { name: 'history' }, group: '입/출고관리' },
  { name: 'lifecycle', label: '연한관리', icon: 'cycle', to: { name: 'lifecycle' }, group: '입/출고관리' },

  { name: 'adjust', label: '재고조정', icon: 'adjust', to: { name: 'adjust' }, group: '재고관리', admin: true },
  { name: 'audit', label: '재고실사', icon: 'audit', to: { name: 'audit' }, group: '재고관리', admin: true },
  { name: 'status', label: '재고현황', icon: 'status', to: { name: 'status' }, group: '재고관리' },

  { name: 'users', label: '사용자관리', icon: 'users', to: { name: 'users' }, group: '설정', admin: true },
  { name: 'audit-log', label: '감사로그', icon: 'shield', to: { name: 'audit-log' }, group: '설정', admin: true },
])

const nav = computed(() => allNav.value.filter((i) => (!i.admin || auth.isAdmin) && (!i.stock || auth.canStock)))

const grouped = computed(() => {
  const g = {}
  nav.value.forEach((i) => (g[i.group] ||= []).push(i))
  return g
})

const bottomNav = computed(() => [
  { name: 'dashboard', label: '홈', icon: 'grid', to: { name: 'dashboard' } },
  { name: 'status', label: '재고현황', icon: 'status', to: { name: 'status' } },
  ...(auth.canStock
    ? [
        { name: 'inbound', label: '입고', icon: 'inbound', to: { name: 'inbound' } },
        { name: 'outbound', label: '출고', icon: 'outbound', to: { name: 'outbound' } },
      ]
    : []),
  { name: 'menu', label: '메뉴', icon: 'menu', action: () => (drawer.value = true) },
])

const icons = {
  grid: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z',
  building: 'M4 21V5a1 1 0 011-1h9a1 1 0 011 1v16M15 9h4a1 1 0 011 1v11M8 8h2M8 12h2M8 16h2',
  folder: 'M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2z',
  tag: 'M3 7v5l8 8 6-6-8-8H3zM7 7h.01',
  detail: 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2M9 12h6M9 16h6',
  box: 'M21 16V8a2 2 0 00-1-1.73l-7-4a2 2 0 00-2 0l-7 4A2 2 0 003 8v8a2 2 0 001 1.73l7 4a2 2 0 002 0l7-4A2 2 0 0021 16zM3.3 7L12 12l8.7-5M12 22V12',
  sku: 'M4 6v12M8 6v12M11 6v12M15 6v12M18 6v12M20 6v12',
  inbound: 'M12 3v10m0 0l4-4m-4 4l-4-4M4 17v2a2 2 0 002 2h12a2 2 0 002-2v-2',
  outbound: 'M12 13V3m0 0l4 4m-4-4l-4 4M4 17v2a2 2 0 002 2h12a2 2 0 002-2v-2',
  transfer: 'M7 16V4m0 0L3 8m4-4l4 4M17 8v12m0 0l4-4m-4 4l-4-4',
  adjust: 'M4 6h16M4 12h16M4 18h16M8 4v4M16 10v4M11 16v4',
  audit: 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 104 0M8 14l2 2 4-4',
  status: 'M4 19V5M4 19h16M8 17v-6M12 17V9M16 17v-9',
  history: 'M3 3v5h5M3.05 13a9 9 0 102.6-6.36L3 8M12 7v5l4 2',
  pin: 'M12 21s7-5.686 7-11a7 7 0 10-14 0c0 5.314 7 11 7 11zM12 12.5a2.5 2.5 0 100-5 2.5 2.5 0 000 5z',
  cycle: 'M3 12a9 9 0 0115.5-6.3L21 8M21 3v5h-5M21 12a9 9 0 01-15.5 6.3L3 16M3 21v-5h5',
  shield: 'M12 3l8 3v6c0 4.5-3 7.5-8 9-5-1.5-8-4.5-8-9V6l8-3zM9.5 12l2 2 3.5-4',
  users: 'M16 11a3 3 0 100-6 3 3 0 000 6zM8 11a3 3 0 100-6 3 3 0 000 6zM2 20v-1a4 4 0 014-4h4a4 4 0 014 4v1M16 14h2a4 4 0 014 4v1',
  menu: 'M4 6h16M4 12h16M4 18h16',
}

async function doLogout() {
  await auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="flex h-full bg-slate-100">
    <!-- PC 사이드바 -->
    <aside class="no-print hidden w-60 shrink-0 flex-col border-r border-slate-200 bg-white lg:flex">
      <div class="flex items-center gap-2 px-5 py-4">
        <div class="flex h-9 w-9 items-center justify-center rounded-lg bg-brand-600 text-sm font-bold text-white">M</div>
        <div>
          <p class="text-sm font-bold leading-tight text-slate-800">엠파크 WMS</p>
          <p class="text-[11px] text-slate-400">재고관리시스템</p>
        </div>
      </div>
      <nav class="flex-1 space-y-3 overflow-y-auto px-3 py-2 scrollbar-slim">
        <div v-for="(items, g) in grouped" :key="g">
          <p v-if="g" class="px-2 pb-1 pt-2 text-[11px] font-semibold uppercase tracking-wide text-slate-400">{{ g }}</p>
          <RouterLink
            v-for="item in items"
            :key="item.name"
            :to="item.to"
            class="mb-0.5 flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm font-medium text-slate-600 hover:bg-slate-100"
            :class="route.name === item.name ? 'bg-brand-50 text-brand-700' : ''"
          >
            <svg class="h-[18px] w-[18px]" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons[item.icon]" /></svg>
            {{ item.label }}
          </RouterLink>
        </div>
      </nav>
      <div class="border-t border-slate-100 p-3">
        <div class="mb-2 flex items-center gap-2 px-2">
          <div class="flex h-8 w-8 items-center justify-center rounded-full bg-slate-200 text-xs font-bold text-slate-600">{{ auth.displayName.charAt(0).toUpperCase() }}</div>
          <div class="min-w-0">
            <p class="truncate text-xs font-semibold text-slate-700">{{ auth.displayName }}</p>
            <p class="text-[11px]" :class="auth.isAdmin ? 'text-brand-600' : 'text-slate-400'">{{ auth.isAdmin ? '관리자' : '일반 사용자' }}</p>
          </div>
        </div>
        <button class="btn-ghost w-full btn-sm" @click="doLogout">로그아웃</button>
      </div>
    </aside>

    <!-- 본문 -->
    <div class="flex min-w-0 flex-1 flex-col">
      <header class="no-print flex items-center justify-between border-b border-slate-200 bg-white px-4 py-3 lg:hidden">
        <div class="flex items-center gap-2">
          <div class="flex h-7 w-7 items-center justify-center rounded-md bg-brand-600 text-xs font-bold text-white">M</div>
          <span class="text-sm font-bold text-slate-800">엠파크 WMS</span>
        </div>
        <span class="badge" :class="auth.isAdmin ? 'bg-brand-50 text-brand-700' : 'bg-slate-100 text-slate-500'">{{ auth.isAdmin ? '관리자' : '일반' }}</span>
      </header>

      <main class="flex-1 overflow-y-auto px-4 pb-24 pt-4 scrollbar-slim sm:px-6 lg:pb-6">
        <RouterView />
      </main>

      <!-- 모바일 하단 탭바 -->
      <nav class="no-print fixed inset-x-0 bottom-0 z-30 flex border-t border-slate-200 bg-white/95 backdrop-blur lg:hidden">
        <component
          :is="item.to ? 'RouterLink' : 'button'"
          v-for="item in bottomNav"
          :key="item.name"
          :to="item.to"
          class="flex flex-1 flex-col items-center gap-0.5 py-2.5 text-[11px] font-medium"
          :class="route.name === item.name ? 'text-brand-600' : 'text-slate-400'"
          @click="item.action && item.action()"
        >
          <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons[item.icon]" /></svg>
          {{ item.label }}
        </component>
      </nav>
    </div>

    <!-- 모바일 메뉴 드로어 -->
    <Transition name="fade">
      <div v-if="drawer" class="no-print fixed inset-0 z-40 lg:hidden" @click.self="drawer = false">
        <div class="absolute inset-0 bg-slate-900/40" />
        <div class="absolute bottom-0 left-0 right-0 max-h-[80vh] overflow-y-auto rounded-t-2xl bg-white p-4 pb-8 scrollbar-slim">
          <div class="mx-auto mb-3 h-1 w-10 rounded-full bg-slate-300" />
          <div v-for="(items, g) in grouped" :key="g" class="mb-3">
            <p v-if="g" class="mb-1 px-1 text-[11px] font-semibold text-slate-400">{{ g }}</p>
            <div class="grid grid-cols-3 gap-2">
              <RouterLink
                v-for="item in items"
                :key="item.name"
                :to="item.to"
                class="flex flex-col items-center gap-1.5 rounded-xl border border-slate-100 p-3 text-center text-[11px] font-medium text-slate-600"
                :class="route.name === item.name ? 'bg-brand-50 text-brand-700' : ''"
                @click="drawer = false"
              >
                <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons[item.icon]" /></svg>
                {{ item.label }}
              </RouterLink>
            </div>
          </div>
          <button class="btn-ghost mt-2 w-full" @click="doLogout">로그아웃</button>
        </div>
      </div>
    </Transition>
  </div>
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

import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/pages/LoginView.vue'),
    meta: { public: true },
  },
  // 모바일 QR 스캔 대상 (SKU 코드 기준) — 백오피스와 분리된 입/출고 전용 단말.
  // 자체 로그인을 화면 안에서 처리하므로 public (백오피스 /login 으로 튕기지 않음)
  {
    path: '/s',
    name: 'scanHome',
    component: () => import('@/pages/mobile/SkuScanHome.vue'),
    meta: { public: true },
  },
  {
    path: '/s/:code',
    name: 'scan',
    component: () => import('@/pages/mobile/SkuScanView.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    component: () => import('@/components/layout/BackofficeLayout.vue'),
    children: [
      { path: '', name: 'dashboard', component: () => import('@/pages/DashboardView.vue') },

      // ── 기준정보관리 (admin) ──
      {
        path: 'master/complexes',
        name: 'complexes',
        component: () => import('@/pages/master/MasterCodeBoard.vue'),
        meta: { admin: true, masterKey: 'complexes' },
      },
      {
        path: 'master/categories',
        name: 'categories',
        component: () => import('@/pages/master/MasterCodeBoard.vue'),
        meta: { admin: true, masterKey: 'categories' },
      },
      {
        path: 'master/product-codes',
        name: 'productCodes',
        component: () => import('@/pages/master/MasterCodeBoard.vue'),
        meta: { admin: true, masterKey: 'productCodes' },
      },
      {
        path: 'master/product-details',
        name: 'productDetails',
        component: () => import('@/pages/master/MasterCodeBoard.vue'),
        meta: { admin: true, masterKey: 'productDetails' },
      },

      // ── 상품관리 (admin) ──
      { path: 'products', name: 'products', component: () => import('@/pages/products/ProductsView.vue'), meta: { admin: true } },
      { path: 'skus', name: 'skus', component: () => import('@/pages/products/SkusView.vue'), meta: { admin: true } },

      // ── 위치관리 (admin) ──
      { path: 'locations', name: 'locations', component: () => import('@/pages/locations/LocationView.vue'), meta: { admin: true } },
      { path: 'storage-locations', name: 'storageLocations', component: () => import('@/pages/locations/StorageLocationView.vue'), meta: { admin: true } },

      // ── 재고관리 ──
      { path: 'stock/inbound', name: 'inbound', component: () => import('@/pages/stock/StockOpView.vue'), meta: { op: 'in', stock: true } },
      { path: 'stock/outbound', name: 'outbound', component: () => import('@/pages/stock/StockOpView.vue'), meta: { op: 'out', stock: true } },
      { path: 'stock/transfer', name: 'transfer', component: () => import('@/pages/stock/StockTransferView.vue'), meta: { stock: true } },
      { path: 'stock/adjust', name: 'adjust', component: () => import('@/pages/stock/StockOpView.vue'), meta: { op: 'adjust', admin: true } },
      { path: 'stock/audit', name: 'audit', component: () => import('@/pages/stock/StockAuditView.vue'), meta: { admin: true } },
      { path: 'stock/history', name: 'history', component: () => import('@/pages/stock/StockHistoryView.vue') },
      { path: 'stock/status', name: 'status', component: () => import('@/pages/stock/StockStatusView.vue') },
      { path: 'stock/lifecycle', name: 'lifecycle', component: () => import('@/pages/stock/LifecycleView.vue') },

      // ── 설정 ──
      { path: 'users', name: 'users', component: () => import('@/pages/admin/UsersView.vue'), meta: { admin: true } },
      { path: 'audit', name: 'audit-log', component: () => import('@/pages/admin/AuditLogView.vue'), meta: { admin: true } },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.ready) await auth.init()
  if (to.meta.public) return true
  if (!auth.isLoggedIn) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.meta.admin && !auth.isAdmin) return { name: 'dashboard' }
  if (to.meta.stock && !auth.canStock) return { name: 'dashboard' }
  return true
})

export default router

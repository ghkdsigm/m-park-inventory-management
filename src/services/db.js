import { api } from '@/api'

/**
 * 엠파크 WMS 데이터 접근 계층 (Spring REST 백엔드).
 * - 백엔드는 camelCase JSON 을 주고받으므로 변환 불필요.
 * - 함수 시그니처/반환형은 기존(Supabase) 버전과 동일하게 유지 → 화면 코드 무수정.
 * - 일부 컬럼명 차이(MySQL 예약어 회피)는 여기서 매핑:
 *     원장 beforeQty/afterQty → before/after,  일별집계 statDate → date
 */

/* ---------- 공통 보드 (CRUD) ---------- */
function makeBoard(path) {
  return {
    list: () => api.get(`/${path}`),
    get: (id) => api.get(`/${path}/${id}`),
    create: (data) => api.post(`/${path}`, data),
    update: (id, data) => api.put(`/${path}/${id}`, data),
    remove: (id) => api.del(`/${path}/${id}`),
  }
}

/* ===================== 기준정보 (4단계 코드) ===================== */
export const complexes = makeBoard('complexes')
export const categories = makeBoard('categories')
export const productCodes = makeBoard('product-codes')
export const productDetails = makeBoard('product-details')

/* ===================== 견적서 ===================== */
export const quotes = {
  ...makeBoard('quotes'),
  // PDF 업로드 → 추출+SKU추천 결과(미저장) 반환
  upload: (file) => {
    const form = new FormData()
    form.append('file', file, file.name)
    return api.upload('/quotes/upload', form)
  },
  // 서버 페이징 목록 + 필터
  async pageList(filters = {}) {
    const r = await api.get('/quotes/page', {
      vendor: filters.vendor || null, complex: filters.complex || null, month: filters.month || null,
      search: filters.search || null, page: filters.page || 1, pageSize: filters.pageSize || 20,
    })
    return { rows: r?.rows || [], total: r?.total || 0 }
  },
  filterOptions: () => api.get('/quotes/filter-options'),
  // 특정 SKU 의 최근 연결 견적 요약(없으면 null)
  forSku: (skuId) => api.get(`/quotes/for-sku/${skuId}`),
  // SKU 미연결 견적 품목(새 제품 연결 후보)
  unmatched: () => api.get('/quotes/unmatched'),
  // 견적 품목 ↔ SKU 연결/해제
  linkItem: (itemId, skuId) => api.post(`/quotes/items/${itemId}/link`, { skuId }),
}

/* ===================== AI 사용량(토큰) 모니터링 ===================== */
export const aiUsage = {
  summary: (days = 30) => api.get('/ai-usage/summary', { days }),
}

export const MASTER = {
  complexes: { board: complexes, label: '단지', parent: null, auto: false },
  categories: { board: categories, label: '카테고리', parent: null, auto: true },
  productCodes: { board: productCodes, label: '제품코드', parent: 'categories', fk: 'categoryId', auto: true },
  productDetails: { board: productDetails, label: '제품상세코드', parent: 'productCodes', fk: 'productCodeId', auto: true },
}

/* ===================== 위치 ===================== */
export const zones = {
  listAll: () => api.get('/zones'),
  listByComplex: (complexId) => api.get('/zones', { complexId }),
  create: (data) => api.post('/zones', data),
  update: (id, data) => api.put(`/zones/${id}`, data),
  remove: (id) => api.del(`/zones/${id}`),
}

export const subZones = {
  listAll: () => api.get('/sub-zones'),
  listByZone: (zoneId) => api.get('/sub-zones', { zoneId }),
  create: (data) => api.post('/sub-zones', data),
  update: (id, data) => api.put(`/sub-zones/${id}`, data),
  remove: (id) => api.del(`/sub-zones/${id}`),
}

export const storageLocations = {
  list: () => api.get('/storage-locations'),
  listByComplex: (complexId) => api.get('/storage-locations', { complexId }),
  create: (data) => api.post('/storage-locations', data),
  update: (id, data) => api.put(`/storage-locations/${id}`, data),
  remove: (id) => api.del(`/storage-locations/${id}`),
}

/* ============================= 상품 ============================= */
export const products = {
  list: () => api.get('/products'),
  /** 상품관리 서버 페이징 */
  async managePage(filters = {}) {
    const r = await api.post('/products/manage-page', {
      categoryId: filters.categoryId || null,
      productCodeId: filters.productCodeId || null,
      productDetailId: filters.productDetailId || null,
      search: filters.search || null,
      page: filters.page || 1,
      pageSize: filters.pageSize || 30,
    })
    return { rows: r?.rows || [], total: r?.total || 0 }
  },
  get: (id) => api.get(`/products/${id}`),
  create: (data) => api.post('/products', data),
  update: (id, data) => api.put(`/products/${id}`, data),
  remove: (id) => api.del(`/products/${id}`),
}

/* ======================== SKU (재고코드) ======================== */
export const skus = {
  list: () => api.get('/skus'),
  listByProduct: (productId) => api.get(`/skus/by-product/${productId}`),
  listLifecycle: () => api.get('/skus/lifecycle'),
  async page(filters = {}) {
    const numOrNull = (v) => (v === '' || v === null || v === undefined ? null : Number(v))
    const r = await api.post('/skus/page', {
      complexId: filters.complexId || null,
      categoryId: filters.categoryId || null,
      productCodeId: filters.productCodeId || null,
      productDetailId: filters.productDetailId || null,
      productId: filters.productId || null,
      skuId: filters.skuId || null,
      status: filters.status || null,
      auditStatus: filters.auditStatus || null,
      search: filters.search || null,
      color: filters.color || null,
      releaseYear: filters.releaseYear || null,
      productionYear: filters.productionYear || null,
      priceMin: numOrNull(filters.priceMin),
      priceMax: numOrNull(filters.priceMax),
      lifecycleOnly: !!filters.lifecycleOnly,
      sort: filters.sort || 'recent',
      page: filters.page || 1,
      pageSize: filters.pageSize || 10,
    })
    return {
      rows: r?.rows || [],
      total: r?.total || 0,
      totalQty: r?.totalQty || 0,
      lowCount: r?.lowCount || 0,
      outCount: r?.outCount || 0,
      totalValue: r?.totalValue || 0,
      avgPrice: r?.avgPrice || 0,
    }
  },
  /** SKU 단위 집계 목록 (전 위치 합산). 입출고 통합조회 좌측 목록용. */
  async pageBySku(filters = {}) {
    const numOrNull = (v) => (v === '' || v === null || v === undefined ? null : Number(v))
    const r = await api.post('/skus/page-by-sku', {
      complexId: filters.complexId || null,
      categoryId: filters.categoryId || null,
      productCodeId: filters.productCodeId || null,
      productDetailId: filters.productDetailId || null,
      productId: filters.productId || null,
      status: filters.status || null,
      search: filters.search || null,
      color: filters.color || null,
      releaseYear: filters.releaseYear || null,
      productionYear: filters.productionYear || null,
      priceMin: numOrNull(filters.priceMin),
      priceMax: numOrNull(filters.priceMax),
      sort: filters.sort || 'moved',
      page: filters.page || 1,
      pageSize: filters.pageSize || 30,
    })
    return { rows: r?.rows || [], total: r?.total || 0 }
  },
  /** SKU관리 서버 페이징 (재고 무관, 변형 목록). */
  async managePage(filters = {}) {
    const numOrNull = (v) => (v === '' || v === null || v === undefined ? null : Number(v))
    const r = await api.post('/skus/manage-page', {
      categoryId: filters.categoryId || null,
      productCodeId: filters.productCodeId || null,
      productDetailId: filters.productDetailId || null,
      productId: filters.productId || null,
      search: filters.search || null,
      color: filters.color || null,
      releaseYear: filters.releaseYear || null,
      productionYear: filters.productionYear || null,
      priceMin: numOrNull(filters.priceMin),
      priceMax: numOrNull(filters.priceMax),
      page: filters.page || 1,
      pageSize: filters.pageSize || 30,
    })
    return { rows: r?.rows || [], total: r?.total || 0 }
  },
  filterOptions: () => api.get('/skus/filter-options'),
  listByIds: (ids) => (!ids || !ids.length ? Promise.resolve([]) : api.post('/skus/by-ids', ids)),
  dashboardSummary: () => api.get('/dashboard/summary'),
  groupByComplex: (filters = {}) =>
    api.post('/skus/group-by-complex', {
      complexId: filters.complexId || null,
      categoryId: filters.categoryId || null,
      productCodeId: filters.productCodeId || null,
      productDetailId: filters.productDetailId || null,
      status: filters.status || null,
      search: filters.search || null,
    }),
  get: (id) => api.get(`/skus/${id}`),
  getByCode: (code) => api.get(`/skus/by-code/${encodeURIComponent(code)}`),
  create: (data) => api.post('/skus', data),
  update: (id, data) => api.put(`/skus/${id}`, data),
  remove: (id) => api.del(`/skus/${id}`),
  /** 위치 검증(실사) — 재고행 기준 */
  verifyLocation: (stockId, actor) =>
    api.post(`/stock/${stockId}/verify`, { name: actor?.name || '' }),
}

/* ===================== 재고 작업 ===================== */
// 원장 컬럼 매핑: beforeQty/afterQty → before/after
function mapMovement(m) {
  if (!m) return m
  return { ...m, before: m.beforeQty, after: m.afterQty }
}

/** 입고 — SKU(변형) + 보관위치(필수) + 수량 → 재고행 find/create */
export async function inboundStock(skuId, storageLocationId, qty, memo = '', reason = '', requestId = '', unitPrice = null) {
  return api.post('/stock/inbound', {
    skuId, storageLocationId, qty: Number(qty), memo: memo || '', reason: reason || '', requestId: requestId || '',
    unitPrice: unitPrice === null || unitPrice === '' ? null : Number(unitPrice),
  })
}

/** 출고 — 재고행(stockId) 대상. extra: 사용처/요청부서/요청자/담당자 */
export async function outboundStock(stockId, qty, memo = '', reason = '', extra = {}, requestId = '') {
  return api.post('/stock/outbound', {
    stockId, qty: Number(qty), memo: memo || '', reason: reason || '',
    usagePlace: extra.usagePlace || '', requestDept: extra.requestDept || '',
    requester: extra.requester || '', handler: extra.handler || '',
    requestId: requestId || '',
  })
}

/** 조정/실사 — 재고행(stockId) 대상. type: 'adjust' | 'audit' */
export async function adjustStock(stockId, type, value, memo = '', reason = '', requestId = '') {
  return api.post('/stock/adjust', { stockId, type, value: Number(value), memo: memo || '', reason: reason || '', requestId: requestId || '' })
}

/** 재고이동 — 출발 재고행(stockId) → 도착 보관위치(필수). 같은 SKU의 도착 재고행에 합류. */
export async function transferStock(payload) {
  return api.post('/stock/transfer', {
    stockId: payload.stockId,
    toStorageLocationId: payload.toStorageLocationId || null,
    qty: payload.qty != null ? Number(payload.qty) : null,
    memo: payload.memo || '',
    reason: payload.reason || '',
    requestId: payload.requestId || '',
  })
}

/** 사진(base64/data URL) → AI 가 찾은 유사 등록제품 후보 리스트 (모바일 "제품 찾아보기") */
export async function findSimilarProducts(imageBase64) {
  return api.post('/chat/find-similar', { imageBase64 })
}

/** 실사 오차 정상처리 — 재고행(stockId) + 사유. 비정상→정상 전환. */
export async function resolveAudit(stockId, reason = '') {
  return api.post(`/stock/${stockId}/audit-resolve`, { reason: reason || '' })
}

export async function applyAuditBatch(items, actor, memo = '') {
  let changed = 0
  for (const it of items) {
    await adjustStock(it.stockId, 'audit', it.counted, memo || '정기 실사', '')
    changed++
  }
  return { changed }
}

export async function listMovements(skuId, max = 100) {
  const rows = await api.get('/movements', { skuId, max })
  return (rows || []).map(mapMovement)
}
export async function recentMovements(max = 100) {
  const rows = await api.get('/movements', { max })
  return (rows || []).map(mapMovement)
}
export async function movementsByDate(date, max = 300) {
  const rows = await api.get('/movements', { date, max })
  return (rows || []).map(mapMovement)
}

/** 입출고 취소(역분개) */
export async function voidMovement(movementId, reason = '') {
  return api.post(`/movements/${movementId}/void`, { reason: reason || '' })
}

/* ===================== 연한관리 ===================== */
export async function replaceLifecycle(stockId, _actor, reason = '') {
  return api.post(`/stock/${stockId}/replace-lifecycle`, { reason: reason || '' })
}
export async function listLocationLogs(skuId, max = 50) {
  return api.get('/location-logs', { skuId, max })
}
export async function listLifecycleLogs(skuId, max = 50) {
  return api.get('/lifecycle-logs', { skuId, max })
}

/* ===================== 일별 집계 ===================== */
// statDate → date 매핑
function mapDaily(d) {
  return d ? { ...d, date: d.statDate } : d
}
export async function getDailyStats(days = 7) {
  const rows = await api.get('/daily-stats', { days })
  return (rows || []).map(mapDaily)
}
export async function getTodayStats() {
  return mapDaily(await api.get('/daily-stats/today'))
}
export async function getDailyStatsRange(from, to) {
  const rows = await api.get('/daily-stats/range', { from, to })
  return (rows || []).map(mapDaily)
}

/* ===================== 감사로그 ===================== */
export async function listAuditLogs({ date, module, byUserId } = {}, max = 500) {
  return api.get('/audit-logs', { date, module, byUserId, max })
}
export async function auditTopUsers(limit = 10) {
  return (await api.get('/audit/top-users', { limit })) || []
}
export async function topProductsBySku(limit = 10) {
  return (await api.get('/audit/top-products', { limit })) || []
}
export async function topChangedSkus(limit = 10) {
  return (await api.get('/audit/top-changed', { limit })) || []
}

/* =============================== 사용자/역할 =============================== */
export const users = {
  get: (uid) => api.get(`/users/${uid}`),
  list: () => api.get('/users'),
  setRole: (uid, role) => api.put(`/users/${uid}/role`, { role }),
  setStockPerm: (uid, canStock) => api.put(`/users/${uid}/stock-perm`, { canStock }),
}

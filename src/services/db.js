import { supabase, rowToCamel, rowsToCamel, objToSnake, unwrap } from '@/supabase'

/**
 * 엠파크 WMS 데이터 접근 계층 (Supabase / PostgreSQL).
 * - DB는 snake_case, 앱은 camelCase → supabase.js 의 변환 헬퍼로 자동 매핑
 * - 자동코드(시퀀스)·SKU코드는 DB 트리거가 채움
 * - 재고/교체 트랜잭션은 Postgres 함수(RPC)로 처리 → 동시성/원장/집계 원자적
 */

function dateKeyLocal(d = new Date()) {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/* ---------- 공통 보드 ---------- */
function makeBoard(table) {
  return {
    async list() {
      return rowsToCamel(unwrap(await supabase.from(table).select('*').order('created_at', { ascending: true })))
    },
    async create(data) {
      return rowToCamel(unwrap(await supabase.from(table).insert(objToSnake(data)).select().single()))
    },
    async update(id, data) {
      return rowToCamel(unwrap(await supabase.from(table).update(objToSnake(data)).eq('id', id).select().single()))
    },
    async remove(id) {
      unwrap(await supabase.from(table).delete().eq('id', id))
    },
    async get(id) {
      const { data, error } = await supabase.from(table).select('*').eq('id', id).maybeSingle()
      if (error) throw error
      return data ? rowToCamel(data) : null
    },
  }
}
// 자동코드형: code 는 트리거가 채우므로 전달돼도 무시
function makeCodedBoard(table) {
  const base = makeBoard(table)
  return {
    ...base,
    create(data) {
      const { code, ...rest } = data
      return base.create(rest)
    },
  }
}

/* ===================== 기준정보 (4단계 코드) ===================== */
export const complexes = makeBoard('complexes') // 단지: 코드 직접 입력
export const categories = makeCodedBoard('categories')
export const productCodes = makeCodedBoard('product_codes')
export const productDetails = makeCodedBoard('product_details')

export const MASTER = {
  complexes: { board: complexes, label: '단지', col: 'complexes', parent: null, auto: false },
  categories: { board: categories, label: '카테고리', col: 'categories', parent: 'complexes', fk: 'complexId', auto: true },
  productCodes: { board: productCodes, label: '제품코드', col: 'product_codes', parent: 'categories', fk: 'categoryId', auto: true },
  productDetails: { board: productDetails, label: '제품상세코드', col: 'product_details', parent: 'productCodes', fk: 'productCodeId', auto: true },
}

/* ===================== 위치 (단지 > 구역 > 상세구역) ===================== */
export const zones = {
  async listAll() {
    return rowsToCamel(unwrap(await supabase.from('zones').select('*').order('created_at', { ascending: true })))
  },
  async listByComplex(complexId) {
    return rowsToCamel(unwrap(await supabase.from('zones').select('*').eq('complex_id', complexId).order('created_at', { ascending: true })))
  },
  create(data) {
    return supabase.from('zones').insert(objToSnake(data)).select().single().then(unwrap).then(rowToCamel)
  },
  update(id, data) {
    return supabase.from('zones').update(objToSnake(data)).eq('id', id).select().single().then(unwrap).then(rowToCamel)
  },
  async remove(id) {
    // sub_zones 는 FK on delete cascade 로 함께 삭제됨
    unwrap(await supabase.from('zones').delete().eq('id', id))
  },
}

export const subZones = {
  async listAll() {
    return rowsToCamel(unwrap(await supabase.from('sub_zones').select('*').order('created_at', { ascending: true })))
  },
  async listByZone(zoneId) {
    return rowsToCamel(unwrap(await supabase.from('sub_zones').select('*').eq('zone_id', zoneId).order('created_at', { ascending: true })))
  },
  create(data) {
    return supabase.from('sub_zones').insert(objToSnake(data)).select().single().then(unwrap).then(rowToCamel)
  },
  update(id, data) {
    return supabase.from('sub_zones').update(objToSnake(data)).eq('id', id).select().single().then(unwrap).then(rowToCamel)
  },
  async remove(id) {
    unwrap(await supabase.from('sub_zones').delete().eq('id', id))
  },
}

/* ===================== 보관위치 (실질적 최종 위치) ===================== */
export const storageLocations = {
  async list() {
    return rowsToCamel(unwrap(await supabase.from('storage_locations').select('*').order('created_at', { ascending: false })))
  },
  async listByComplex(complexId) {
    return rowsToCamel(unwrap(await supabase.from('storage_locations').select('*').eq('complex_id', complexId).order('code', { ascending: true })))
  },
  create(data) {
    const { code, ...rest } = data
    return supabase.from('storage_locations').insert(objToSnake(rest)).select().single().then(unwrap).then(rowToCamel)
  },
  update(id, data) {
    const { code, ...rest } = data
    return supabase.from('storage_locations').update(objToSnake(rest)).eq('id', id).select().single().then(unwrap).then(rowToCamel)
  },
  async remove(id) {
    unwrap(await supabase.from('storage_locations').delete().eq('id', id))
  },
}

/* ============================= 상품 ============================= */
export const products = {
  async list() {
    return rowsToCamel(unwrap(await supabase.from('products').select('*').order('created_at', { ascending: false })))
  },
  async get(id) {
    const { data, error } = await supabase.from('products').select('*').eq('id', id).maybeSingle()
    if (error) throw error
    return data ? rowToCamel(data) : null
  },
  create(data) {
    const { code, skuSeq, ...rest } = data
    return supabase.from('products').insert(objToSnake(rest)).select().single().then(unwrap).then(rowToCamel)
  },
  update(id, data) {
    const { code, skuSeq, ...rest } = data
    return supabase.from('products').update(objToSnake(rest)).eq('id', id).select().single().then(unwrap).then(rowToCamel)
  },
  async remove(id) {
    unwrap(await supabase.from('products').delete().eq('id', id))
  },
}

/* ======================== SKU (재고코드) ======================== */
function skuStatus(qty, safety = 0) {
  if (qty <= 0) return 'out'
  if (safety > 0 && qty <= safety) return 'low'
  return 'in_stock'
}

export const skus = {
  async list() {
    return rowsToCamel(unwrap(await supabase.from('skus').select('*').order('created_at', { ascending: false })))
  },
  async listByProduct(productId) {
    return rowsToCamel(unwrap(await supabase.from('skus').select('*').eq('product_id', productId).order('code', { ascending: true })))
  },
  /** 연한관리 대상(소량)만 서버에서 조회 — 전체 풀로드 방지 */
  async listLifecycle() {
    return rowsToCamel(
      unwrap(await supabase.from('skus').select('*').eq('lifecycle_enabled', true).order('next_replace_at', { ascending: true, nullsFirst: false }))
    )
  },
  /**
   * 서버측 페이징/필터/정렬/집계 (#1 풀로드 제거). 한 번 호출로 페이지 행 + 총계까지.
   * @returns {{ rows, total, totalQty, lowCount, outCount }}
   */
  async page({
    complexId = '', categoryId = '', productCodeId = '', productDetailId = '',
    status = '', search = '', color = '', releaseYear = '', productionYear = '',
    priceMin = '', priceMax = '', lifecycleOnly = false,
    sort = 'recent', page = 1, pageSize = 10,
  } = {}) {
    const numOrNull = (v) => (v === '' || v === null || v === undefined ? null : Number(v))
    const data = unwrap(
      await supabase.rpc('skus_page', {
        p_complex: complexId || null,
        p_category: categoryId || null,
        p_product_code: productCodeId || null,
        p_product_detail: productDetailId || null,
        p_status: status || null,
        p_search: search || null,
        p_color: color || null,
        p_release_year: releaseYear || null,
        p_production_year: productionYear || null,
        p_price_min: numOrNull(priceMin),
        p_price_max: numOrNull(priceMax),
        p_lifecycle_only: !!lifecycleOnly,
        p_sort: sort || 'recent',
        p_limit: pageSize,
        p_offset: Math.max(0, (page - 1) * pageSize),
      })
    )
    return {
      rows: rowsToCamel(data?.rows || []),
      total: data?.total || 0,
      totalQty: data?.totalQty || 0,
      lowCount: data?.lowCount || 0,
      outCount: data?.outCount || 0,
    }
  },
  /** 색상/출시년도/생산년도 셀렉트 옵션(전체 distinct) */
  async filterOptions() {
    const data = unwrap(await supabase.rpc('sku_filter_options'))
    return { colors: data?.colors || [], releaseYears: data?.releaseYears || [], productionYears: data?.productionYears || [] }
  },
  /** 선택된 SKU들(여러 페이지 걸쳐 선택 가능)을 id로 일괄 조회 — QR 출력용 */
  async listByIds(ids) {
    if (!ids || !ids.length) return []
    return rowsToCamel(unwrap(await supabase.from('skus').select('*').in('id', ids)))
  },
  /** 대시보드 요약(서버 집계) — 전체 SKU 풀로드 대체 */
  async dashboardSummary() {
    const d = unwrap(await supabase.rpc('dashboard_summary'))
    return {
      complexCount: d?.complexCount || 0,
      productCount: d?.productCount || 0,
      skuCount: d?.skuCount || 0,
      totalQty: d?.totalQty || 0,
      lowCount: d?.lowCount || 0,
      outCount: d?.outCount || 0,
      lowList: rowsToCamel(d?.lowList || []),
      lifeSoon: d?.lifeSoon || 0,
      lifeOver: d?.lifeOver || 0,
      lifeList: rowsToCamel(d?.lifeList || []),
    }
  },
  /** 단지별 묶기 요약 (단지별 SKU수/총재고/부족/품절) */
  async groupByComplex(filters = {}) {
    const data = unwrap(
      await supabase.rpc('skus_group_by_complex', {
        p_complex: filters.complexId || null,
        p_category: filters.categoryId || null,
        p_product_code: filters.productCodeId || null,
        p_product_detail: filters.productDetailId || null,
        p_status: filters.status || null,
        p_search: filters.search || null,
      })
    )
    return rowsToCamel(data || [])
  },
  async get(id) {
    const { data, error } = await supabase.from('skus').select('*').eq('id', id).maybeSingle()
    if (error) throw error
    return data ? rowToCamel(data) : null
  },
  async getByCode(code) {
    const { data, error } = await supabase.from('skus').select('*').eq('code', code).maybeSingle()
    if (error) throw error
    return data ? rowToCamel(data) : null
  },
  // 코드는 트리거가 상품코드 기반으로 채움. status/초기수량/QR 발급은 여기서 세팅
  async create(data) {
    const { code, ...rest } = data
    const qty = Number(rest.qty) || 0
    const safety = Number(rest.safetyStock) || 0
    const payload = {
      ...rest,
      qty,
      initialQty: qty,
      safetyStock: safety,
      totalIn: 0,
      totalOut: 0,
      status: skuStatus(qty, safety),
      qrGenerated: true,
    }
    return rowToCamel(unwrap(await supabase.from('skus').insert(objToSnake(payload)).select().single()))
  },
  update(id, data) {
    const { code, ...rest } = data
    return supabase.from('skus').update(objToSnake(rest)).eq('id', id).select().single().then(unwrap).then(rowToCamel)
  },
  async remove(id) {
    unwrap(await supabase.from('skus').delete().eq('id', id))
  },
  /** 보관위치 설정/삭제 (재고조정, admin) — RPC: 변경 시 이력 자동 기록 */
  setLocation(skuId, loc) {
    return supabase
      .rpc('set_location', {
        p_sku_id: skuId,
        p_storage_location_id: loc.storageLocationId || null,
        p_storage_location_code: loc.storageLocationCode || '',
        p_zone_id: loc.zoneId || null,
        p_zone_name: loc.zoneName || '',
        p_sub_zone_id: loc.subZoneId || null,
        p_sub_zone_name: loc.subZoneName || '',
        p_location_label: loc.locationLabel || '',
      })
      .then(unwrap)
  },
  /** 위치 검증 (재고실사, admin) */
  verifyLocation(skuId, actor) {
    return supabase.from('skus')
      .update({ location_verified_at: new Date().toISOString(), location_verified_by: actor.name })
      .eq('id', skuId)
      .then(unwrap)
  },
}

/* ===================== 재고 작업 (RPC 트랜잭션) ===================== */
export async function applyStock(skuId, type, value, _actor, memo = '', reason = '') {
  const data = unwrap(
    await supabase.rpc('apply_stock', {
      p_sku_id: skuId,
      p_type: type,
      p_value: Number(value),
      p_memo: memo || '',
      p_reason: reason || '',
    })
  )
  return data // { before, after, delta }
}

export async function applyAuditBatch(items, actor, memo = '') {
  let changed = 0
  for (const it of items) {
    await applyStock(it.skuId, 'audit', it.counted, actor, memo || '정기 실사', '')
    changed++
  }
  return { changed }
}

/** 입출고 취소(역분개) — 본인 등록 당일분만. 원장 보존 + 취소 전표 기록 (RPC) */
export async function voidMovement(movementId, reason = '') {
  return unwrap(await supabase.rpc('void_movement', { p_movement_id: movementId, p_reason: reason || '' }))
}

export async function listMovements(skuId, max = 100) {
  return rowsToCamel(
    unwrap(await supabase.from('stock_movements').select('*').eq('sku_id', skuId).order('at', { ascending: false }).limit(max))
  )
}
export async function recentMovements(max = 100) {
  return rowsToCamel(
    unwrap(await supabase.from('stock_movements').select('*').order('at', { ascending: false }).limit(max))
  )
}

/* ===================== 연한관리 (RPC) ===================== */
export async function replaceLifecycle(skuId, _actor, reason = '') {
  const data = unwrap(await supabase.rpc('replace_lifecycle', { p_sku_id: skuId, p_reason: reason || '' }))
  return data // { replacedAt, nextReplaceAt }
}
/** 보관위치 변경 이력 */
export async function listLocationLogs(skuId, max = 50) {
  return rowsToCamel(
    unwrap(await supabase.from('location_logs').select('*').eq('sku_id', skuId).order('at', { ascending: false }).limit(max))
  )
}

export async function listLifecycleLogs(skuId, max = 50) {
  return rowsToCamel(
    unwrap(await supabase.from('lifecycle_logs').select('*').eq('sku_id', skuId).order('at', { ascending: false }).limit(max))
  )
}

/* ===================== 일별 집계 ===================== */
export async function getDailyStats(days = 7) {
  return rowsToCamel(unwrap(await supabase.from('daily_stats').select('*').order('date', { ascending: false }).limit(days)))
}
export async function getTodayStats() {
  const { data, error } = await supabase.from('daily_stats').select('*').eq('date', dateKeyLocal()).maybeSingle()
  if (error) throw error
  return data ? rowToCamel(data) : null
}

/* ===================== 감사로그 ===================== */
/** 하루 단위 조회 (date='YYYY-MM-DD'). module/byUserId 선택 필터 */
export async function listAuditLogs({ date, module, byUserId } = {}, max = 500) {
  let q = supabase.from('audit_logs').select('*').order('at', { ascending: false }).limit(max)
  if (date) {
    const start = new Date(date + 'T00:00:00')
    const end = new Date(start)
    end.setDate(end.getDate() + 1)
    q = q.gte('at', start.toISOString()).lt('at', end.toISOString())
  }
  if (module) q = q.eq('module', module)
  if (byUserId) q = q.eq('by_user_id', byUserId)
  return rowsToCamel(unwrap(await q))
}
export async function auditTopUsers(limit = 10) {
  return rowsToCamel(unwrap(await supabase.rpc('audit_top_users', { p_limit: limit })) || [])
}
export async function topProductsBySku(limit = 10) {
  return rowsToCamel(unwrap(await supabase.rpc('top_products_by_sku', { p_limit: limit })) || [])
}
export async function topChangedSkus(limit = 10) {
  return rowsToCamel(unwrap(await supabase.rpc('top_changed_skus', { p_limit: limit })) || [])
}

/* =============================== 사용자/역할 =============================== */
export const users = {
  async get(uid) {
    const { data, error } = await supabase.from('profiles').select('*').eq('id', uid).maybeSingle()
    if (error) throw error
    return data ? rowToCamel(data) : null
  },
  async list() {
    return rowsToCamel(unwrap(await supabase.from('profiles').select('*').order('created_at', { ascending: true })))
  },
  setRole(uid, role) {
    return supabase.from('profiles').update({ role }).eq('id', uid).then(unwrap)
  },
  /** 입/출고 권한 부여/회수 (admin) */
  setStockPerm(uid, canStock) {
    return supabase.from('profiles').update({ can_stock: !!canStock }).eq('id', uid).then(unwrap)
  },
}

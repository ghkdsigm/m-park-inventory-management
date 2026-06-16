import { db } from '@/firebase'
import {
  collection,
  doc,
  addDoc,
  getDoc,
  getDocs,
  updateDoc,
  deleteDoc,
  query,
  where,
  orderBy,
  limit,
  serverTimestamp,
  runTransaction,
  writeBatch,
} from 'firebase/firestore'
import { addCycle } from '@/utils/date'

/**
 * 엠파크 WMS 데이터 접근 계층.
 *
 * 기준정보(4단계 코드):
 *   complexes       단지(창고)       최상위 — 코드 직접 입력(예: DANJI-000001)
 *   categories      카테고리         → complexId,  코드 자동(CTG-000001)
 *   productCodes    제품코드         → categoryId, 코드 자동(PC-000001)
 *   productDetails  제품상세코드     → productCodeId, 코드 자동(PCD-000001)
 *
 * 상품/재고:
 *   products        상품             코드 자동(P-000001)
 *   skus            SKU(재고코드)    상품코드 기반 자동(P-000001-001), 생성 시 QR 자동
 *   stockMovements  재고원장         입고/출고/조정/실사 이력
 *
 *   counters        코드 시퀀스      { seq } — 트랜잭션으로만 증가 (중복/재사용 방지)
 *   users           사용자/권한
 */

const snap = (d) => ({ id: d.id, ...d.data() })
const colRef = (name) => collection(db, name)

/** 로컬 기준 YYYY-MM-DD (일별 집계 버킷 키) */
function dateKey(d = new Date()) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/**
 * 카운터 기반 자동코드 + 문서 생성 (단일 트랜잭션).
 * - seq 는 단조 증가만 함 → 삭제된 번호도 재사용되지 않음
 * - 동시 등록 시에도 트랜잭션 충돌 재시도로 중복 발생 안 함
 */
async function createWithSeq(name, counterKey, prefix, pad, data) {
  return runTransaction(db, async (tx) => {
    const cRef = doc(db, 'counters', counterKey)
    const cSnap = await tx.get(cRef)
    const next = (cSnap.exists() ? Number(cSnap.data().seq) || 0 : 0) + 1
    const code = `${prefix}-${String(next).padStart(pad, '0')}`
    const newRef = doc(colRef(name))
    tx.set(cRef, { seq: next, updatedAt: serverTimestamp() }, { merge: true })
    tx.set(newRef, { ...data, code, createdAt: serverTimestamp() })
    return { id: newRef.id, code }
  })
}

/** 공통 CRUD (목록/조회/수정/삭제) */
function makeBoard(name) {
  return {
    async list() {
      return (await getDocs(query(colRef(name), orderBy('createdAt', 'asc')))).docs.map(snap)
    },
    async create(data) {
      // 코드 직접 입력형(단지)에서 사용
      return addDoc(colRef(name), { ...data, createdAt: serverTimestamp() })
    },
    update(id, data) {
      return updateDoc(doc(db, name, id), { ...data, updatedAt: serverTimestamp() })
    },
    remove(id) {
      return deleteDoc(doc(db, name, id))
    },
    async get(id) {
      const d = await getDoc(doc(db, name, id))
      return d.exists() ? snap(d) : null
    },
  }
}

/** 자동코드형 보드 (create 만 카운터 트랜잭션으로 대체) */
function makeCodedBoard(name, counterKey, prefix, pad = 6) {
  const base = makeBoard(name)
  return {
    ...base,
    create(data) {
      // 전달된 code 는 무시하고 자동생성
      const { code, ...rest } = data
      return createWithSeq(name, counterKey, prefix, pad, rest)
    },
  }
}

/* ===================== 기준정보 (4단계 코드) ===================== */
export const complexes = makeBoard('complexes') // 단지: 코드 직접 입력
export const categories = makeCodedBoard('categories', 'categories', 'CTG')
export const productCodes = makeCodedBoard('productCodes', 'productCodes', 'PC')
export const productDetails = makeCodedBoard('productDetails', 'productDetails', 'PCD')

export const MASTER = {
  complexes: { board: complexes, label: '단지', col: 'complexes', parent: null, auto: false },
  categories: { board: categories, label: '카테고리', col: 'categories', parent: 'complexes', fk: 'complexId', auto: true },
  productCodes: { board: productCodes, label: '제품코드', col: 'productCodes', parent: 'categories', fk: 'categoryId', auto: true },
  productDetails: { board: productDetails, label: '제품상세코드', col: 'productDetails', parent: 'productCodes', fk: 'productCodeId', auto: true },
}

/* ===================== 위치 (단지 > 구역 > 상세구역) ===================== */
export const zones = {
  async listAll() {
    return (await getDocs(query(colRef('zones'), orderBy('createdAt', 'asc')))).docs.map(snap)
  },
  async listByComplex(complexId) {
    const r = await getDocs(query(colRef('zones'), where('complexId', '==', complexId)))
    return r.docs.map(snap).sort((a, b) => (a.createdAt?.seconds || 0) - (b.createdAt?.seconds || 0))
  },
  create(data) {
    return addDoc(colRef('zones'), {
      name: data.name.trim(),
      complexId: data.complexId,
      complexName: data.complexName || '',
      createdAt: serverTimestamp(),
    })
  },
  update(id, data) {
    return updateDoc(doc(db, 'zones', id), { ...data, updatedAt: serverTimestamp() })
  },
  // 구역 삭제 시 하위 상세구역도 함께 삭제
  async remove(id) {
    const subs = await getDocs(query(colRef('subZones'), where('zoneId', '==', id)))
    const batch = writeBatch(db)
    subs.docs.forEach((d) => batch.delete(d.ref))
    batch.delete(doc(db, 'zones', id))
    await batch.commit()
  },
}

export const subZones = {
  async listAll() {
    return (await getDocs(query(colRef('subZones'), orderBy('createdAt', 'asc')))).docs.map(snap)
  },
  async listByZone(zoneId) {
    const r = await getDocs(query(colRef('subZones'), where('zoneId', '==', zoneId)))
    return r.docs.map(snap).sort((a, b) => (a.createdAt?.seconds || 0) - (b.createdAt?.seconds || 0))
  },
  create(data) {
    return addDoc(colRef('subZones'), {
      name: data.name.trim(),
      zoneId: data.zoneId,
      zoneName: data.zoneName || '',
      complexId: data.complexId,
      complexName: data.complexName || '',
      createdAt: serverTimestamp(),
    })
  },
  update(id, data) {
    return updateDoc(doc(db, 'subZones', id), { ...data, updatedAt: serverTimestamp() })
  },
  remove(id) {
    return deleteDoc(doc(db, 'subZones', id))
  },
}

/**
 * 보관위치 (실질적 최종 위치) — 단지 필수, 구역/상세구역 선택.
 * 코드 자동(LOC-000001). SKU에 이 위치를 지정(재고조정)한다.
 */
export const storageLocations = {
  async list() {
    return (await getDocs(query(colRef('storageLocations'), orderBy('createdAt', 'desc')))).docs.map(snap)
  },
  async listByComplex(complexId) {
    const r = await getDocs(query(colRef('storageLocations'), where('complexId', '==', complexId)))
    return r.docs.map(snap).sort((a, b) => (a.code > b.code ? 1 : -1))
  },
  create(data) {
    const { code, ...rest } = data
    return createWithSeq('storageLocations', 'storageLocations', 'LOC', 6, rest)
  },
  update(id, data) {
    const { code, ...rest } = data
    return updateDoc(doc(db, 'storageLocations', id), { ...rest, updatedAt: serverTimestamp() })
  },
  remove(id) {
    return deleteDoc(doc(db, 'storageLocations', id))
  },
}

/* ============================= 상품 ============================= */
export const products = {
  async list() {
    return (await getDocs(query(colRef('products'), orderBy('createdAt', 'desc')))).docs.map(snap)
  },
  async get(id) {
    const d = await getDoc(doc(db, 'products', id))
    return d.exists() ? snap(d) : null
  },
  // 상품코드 자동(P-000001) + SKU 시퀀스 초기화(skuSeq:0)
  async create(data) {
    return createWithSeq('products', 'products', 'P', 6, { ...data, skuSeq: 0 })
  },
  update(id, data) {
    // 코드는 수정 불가 → 들어와도 제거
    const { code, skuSeq, ...rest } = data
    return updateDoc(doc(db, 'products', id), { ...rest, updatedAt: serverTimestamp() })
  },
  remove(id) {
    return deleteDoc(doc(db, 'products', id))
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
    return (await getDocs(query(colRef('skus'), orderBy('createdAt', 'desc')))).docs.map(snap)
  },
  async listByProduct(productId) {
    const q = query(colRef('skus'), where('productId', '==', productId))
    return (await getDocs(q)).docs.map(snap).sort((a, b) => (a.code > b.code ? 1 : -1))
  },
  async get(id) {
    const d = await getDoc(doc(db, 'skus', id))
    return d.exists() ? snap(d) : null
  },
  /** SKU 코드로 조회 (QR 스캔용) */
  async getByCode(code) {
    const q = query(colRef('skus'), where('code', '==', code), limit(1))
    const r = await getDocs(q)
    return r.empty ? null : snap(r.docs[0])
  },
  /**
   * SKU 생성 → 코드 자동(상품코드-001) + QR 자동발급.
   * 상품 문서의 skuSeq 를 트랜잭션으로 증가시켜 상품별 순번을 보장.
   */
  async create(data) {
    const qty = Number(data.qty) || 0
    const safety = Number(data.safetyStock) || 0
    return runTransaction(db, async (tx) => {
      const pRef = doc(db, 'products', data.productId)
      const pSnap = await tx.get(pRef)
      if (!pSnap.exists()) throw new Error('상품을 찾을 수 없습니다.')
      const p = pSnap.data()
      if (!p.code) throw new Error('상품 코드가 없습니다. 상품을 다시 등록해주세요.')
      const seq = (Number(p.skuSeq) || 0) + 1
      const code = `${p.code}-${String(seq).padStart(3, '0')}`

      const skuRef = doc(colRef('skus'))
      tx.update(pRef, { skuSeq: seq })
      tx.set(skuRef, {
        code,
        productId: data.productId,
        productName: data.productName || p.name || '',
        spec: data.spec?.trim() || '',
        color: data.color?.trim() || '',
        releaseYear: data.releaseYear ? String(data.releaseYear).trim() : '',
        productionYear: data.productionYear ? String(data.productionYear).trim() : '',
        purpose: data.purpose?.trim() || '',
        imageUrl: data.imageUrl || '', // SKU 옵션 이미지(선택)
        productMainImageUrl: data.productMainImageUrl || '', // 상품 대표이미지 비정규화(목록 폴백용)
        price: Number(data.price) || 0,
        qty,
        initialQty: qty,
        safetyStock: safety,
        totalIn: 0,
        totalOut: 0,
        status: skuStatus(qty, safety),
        qrGenerated: true, // 생성 시 자동 QR 발급
        complexId: data.complexId || null,
        complexName: data.complexName || '',
        categoryId: data.categoryId || null,
        productCodeId: data.productCodeId || null,
        productDetailId: data.productDetailId || null,
        pathLabel: data.pathLabel || '',
        // 보관위치 - 재고조정에서 설정
        storageLocationId: data.storageLocationId || '',
        storageLocationCode: data.storageLocationCode || '',
        zoneId: data.zoneId || '',
        zoneName: data.zoneName || '',
        subZoneId: data.subZoneId || '',
        subZoneName: data.subZoneName || '',
        locationLabel: data.locationLabel || '',
        // 연한관리(주기 교체)
        lifecycleEnabled: !!data.lifecycleEnabled,
        cycleValue: Number(data.cycleValue) || 0,
        cycleUnit: data.cycleUnit || 'month',
        lastReplacedAt: data.lastReplacedAt || null,
        nextReplaceAt: data.nextReplaceAt || null,
        replaceReason: data.replaceReason || '',
        lifecycleNote: data.lifecycleNote || '',
        createdAt: serverTimestamp(),
      })
      return { id: skuRef.id, code }
    })
  },
  update(id, data) {
    // 코드는 수정 불가
    const { code, ...rest } = data
    return updateDoc(doc(db, 'skus', id), { ...rest, updatedAt: serverTimestamp() })
  },
  remove(id) {
    return deleteDoc(doc(db, 'skus', id))
  },
  /** SKU 보관위치 설정/삭제 (재고조정에서 사용). loc 비우면 위치 삭제 */
  setLocation(skuId, loc) {
    return updateDoc(doc(db, 'skus', skuId), {
      storageLocationId: loc.storageLocationId || '',
      storageLocationCode: loc.storageLocationCode || '',
      zoneId: loc.zoneId || '',
      zoneName: loc.zoneName || '',
      subZoneId: loc.subZoneId || '',
      subZoneName: loc.subZoneName || '',
      locationLabel: loc.locationLabel || '',
      updatedAt: serverTimestamp(),
    })
  },
  /** SKU 위치 확정/검증 (재고실사에서 사용) */
  verifyLocation(skuId, actor) {
    return updateDoc(doc(db, 'skus', skuId), {
      locationVerifiedAt: serverTimestamp(),
      locationVerifiedBy: actor.name,
      updatedAt: serverTimestamp(),
    })
  },
}

/* ===================== 재고 작업 (트랜잭션) ===================== */
/**
 * 입고/출고/조정/실사 공통 처리.
 * value: in/out=수량(양수), adjust/audit=목표 재고수량
 */
export async function applyStock(skuId, type, value, actor, memo = '', reason = '') {
  const ref = doc(db, 'skus', skuId)
  value = Number(value)
  if (!Number.isFinite(value) || value < 0) throw new Error('수량을 올바르게 입력하세요.')

  const todayKey = dateKey()
  const dailyRef = doc(db, 'dailyStats', todayKey)

  return runTransaction(db, async (tx) => {
    // 모든 read 를 write 보다 먼저
    const d = await tx.get(ref)
    if (!d.exists()) throw new Error('SKU를 찾을 수 없습니다.')
    const dailySnap = await tx.get(dailyRef)
    const s = d.data()
    const before = Number(s.qty) || 0
    let after
    let delta

    if (type === 'in') {
      delta = value
      after = before + value
    } else if (type === 'out') {
      if (before < value) throw new Error(`재고 부족: 현재 ${before}개`)
      delta = -value
      after = before - value
    } else if (type === 'adjust' || type === 'audit') {
      after = value
      delta = after - before
    } else {
      throw new Error('알 수 없는 작업 유형')
    }

    const safety = Number(s.safetyStock) || 0
    const patch = {
      qty: after,
      status: skuStatus(after, safety),
      lastMovedBy: actor.name,
      lastMovedAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    }
    if (type === 'in') patch.totalIn = (Number(s.totalIn) || 0) + value
    if (type === 'out') patch.totalOut = (Number(s.totalOut) || 0) + value
    tx.update(ref, patch)

    const moveRef = doc(colRef('stockMovements'))
    tx.set(moveRef, {
      skuId,
      skuCode: s.code,
      productName: s.productName || '',
      type,
      qty: Math.abs(delta),
      delta,
      before,
      after,
      complexId: s.complexId || null,
      complexName: s.complexName || '',
      pathLabel: s.pathLabel || '',
      memo: memo?.trim() || '',
      reason: reason?.trim() || '', // 재고조정 사유 (파손/분실 등)
      byUid: actor.uid,
      byName: actor.name,
      at: serverTimestamp(),
    })

    // 일별 집계 갱신 (대시보드가 로그 전체를 스캔하지 않도록)
    const prev = dailySnap.exists() ? dailySnap.data() : {}
    const inc = (k, v) => (Number(prev[k]) || 0) + v
    tx.set(
      dailyRef,
      {
        date: todayKey,
        moveCount: inc('moveCount', 1),
        inCount: inc('inCount', type === 'in' ? 1 : 0),
        outCount: inc('outCount', type === 'out' ? 1 : 0),
        adjustCount: inc('adjustCount', type === 'adjust' ? 1 : 0),
        auditCount: inc('auditCount', type === 'audit' ? 1 : 0),
        inQty: inc('inQty', type === 'in' ? value : 0),
        outQty: inc('outQty', type === 'out' ? value : 0),
        updatedAt: serverTimestamp(),
      },
      { merge: true }
    )
    return { before, after, delta }
  })
}

/**
 * 재고실사 일괄 확정.
 * @param {Array<{skuId:string, counted:number}>} items  실사수량이 시스템과 다른 항목만
 * @param {{uid,name}} actor
 * @param {string} memo  실사 세션 메모(예: "2026-06 정기실사")
 * @returns {{changed:number}}
 */
export async function applyAuditBatch(items, actor, memo = '') {
  let changed = 0
  for (const it of items) {
    await applyStock(it.skuId, 'audit', it.counted, actor, memo || '정기 실사', '')
    changed++
  }
  return { changed }
}

export async function listMovements(skuId, max = 100) {
  // 복합 인덱스 불필요: where 단일 필드로 가져와 클라이언트에서 정렬/슬라이스
  const r = await getDocs(query(colRef('stockMovements'), where('skuId', '==', skuId)))
  return r.docs
    .map(snap)
    .sort((a, b) => (b.at?.seconds || 0) - (a.at?.seconds || 0))
    .slice(0, max)
}

/** 최근 입출고 원장 (화면 조회 기본 100건). 추가 필터는 클라이언트에서 처리 */
export async function recentMovements(max = 100) {
  const q = query(colRef('stockMovements'), orderBy('at', 'desc'), limit(max))
  return (await getDocs(q)).docs.map(snap)
}

/* ===================== 일별 집계 (대시보드용) ===================== */
export async function getDailyStats(days = 7) {
  const q = query(colRef('dailyStats'), orderBy('date', 'desc'), limit(days))
  return (await getDocs(q)).docs.map(snap)
}
export async function getTodayStats() {
  const d = await getDoc(doc(db, 'dailyStats', dateKey()))
  return d.exists() ? snap(d) : null
}

/* ===================== 연한관리 (주기 교체) ===================== */
/**
 * SKU 교체 처리: 최근교체일=지금, 다음교체예정일 재계산, 이력 기록.
 * (현장 QR/연한관리 화면에서 사용 — 로그인 사용자 가능)
 */
export async function replaceLifecycle(skuId, actor, reason = '') {
  const ref = doc(db, 'skus', skuId)
  return runTransaction(db, async (tx) => {
    const d = await tx.get(ref)
    if (!d.exists()) throw new Error('SKU를 찾을 수 없습니다.')
    const s = d.data()
    const now = new Date()
    const next = s.lifecycleEnabled ? addCycle(now, s.cycleValue, s.cycleUnit) : null
    tx.update(ref, {
      lastReplacedAt: now,
      nextReplaceAt: next,
      lastReplacedBy: actor.name,
      updatedAt: serverTimestamp(),
    })
    const logRef = doc(colRef('lifecycleLogs'))
    tx.set(logRef, {
      skuId,
      skuCode: s.code,
      productName: s.productName || '',
      replacedAt: now,
      nextReplaceAt: next,
      reason: reason?.trim() || s.replaceReason || '',
      pathLabel: s.pathLabel || '',
      complexName: s.complexName || '',
      byUid: actor.uid,
      byName: actor.name,
      at: serverTimestamp(),
    })
    return { replacedAt: now, nextReplaceAt: next }
  })
}

export async function listLifecycleLogs(skuId, max = 50) {
  // 복합 인덱스 불필요: where 단일 필드 + 클라이언트 정렬
  const r = await getDocs(query(colRef('lifecycleLogs'), where('skuId', '==', skuId)))
  return r.docs
    .map(snap)
    .sort((a, b) => (b.at?.seconds || 0) - (a.at?.seconds || 0))
    .slice(0, max)
}

/* =============================== 사용자/역할 =============================== */
export const users = {
  async get(uid) {
    const d = await getDoc(doc(db, 'users', uid))
    return d.exists() ? snap(d) : null
  },
  async list() {
    return (await getDocs(colRef('users'))).docs.map(snap)
  },
  setRole(uid, role) {
    return updateDoc(doc(db, 'users', uid), { role })
  },
}

import { createClient } from '@supabase/supabase-js'

// 환경변수(.env): Supabase 프로젝트 설정 > API
const url = (import.meta.env.VITE_SUPABASE_URL || '').trim()
const anonKey = (import.meta.env.VITE_SUPABASE_ANON_KEY || '').trim()

if (!url || !anonKey) {
  console.warn('[Supabase] .env 설정이 비어 있습니다. VITE_SUPABASE_URL / VITE_SUPABASE_ANON_KEY 를 채워주세요.')
}
// 키에 비ASCII(한글 placeholder 등)가 남아 있으면 헤더 오류가 나므로 미리 경고
if (anonKey && /[^\x00-\x7F]/.test(anonKey)) {
  console.error('[Supabase] anon key 에 한글 등 비ASCII 문자가 있습니다. .env 의 VITE_SUPABASE_ANON_KEY 를 실제 키로 교체하세요.')
}

/* ============ 자동로그인 토글 ============
 * '1'(기본) = 영구 보관(localStorage) → 브라우저 닫아도 로그인 유지(자동로그인)
 * '0'        = 세션 보관(sessionStorage) → 탭/브라우저 닫으면 로그아웃
 * 로그인 직전 setAutoLogin() 으로 설정하면, 세션 토큰이 알맞은 저장소에 기록된다.
 */
const AUTOLOGIN_KEY = 'mpark.autoLogin'
export function setAutoLogin(on) {
  try { localStorage.setItem(AUTOLOGIN_KEY, on ? '1' : '0') } catch (e) { /* ignore */ }
}
export function getAutoLogin() {
  try { return localStorage.getItem(AUTOLOGIN_KEY) !== '0' } catch (e) { return true }
}
// localStorage(영구) ↔ sessionStorage(세션) 를 토글에 따라 전환하는 저장소 어댑터
const authStorage = {
  getItem(k) {
    try { return localStorage.getItem(k) ?? sessionStorage.getItem(k) } catch (e) { return null }
  },
  setItem(k, v) {
    try {
      if (getAutoLogin()) { localStorage.setItem(k, v); sessionStorage.removeItem(k) }
      else { sessionStorage.setItem(k, v); localStorage.removeItem(k) }
    } catch (e) { /* ignore */ }
  },
  removeItem(k) {
    try { localStorage.removeItem(k); sessionStorage.removeItem(k) } catch (e) { /* ignore */ }
  },
}

export const supabase = createClient(url || 'http://localhost', anonKey || 'anon', {
  auth: {
    persistSession: true,
    autoRefreshToken: true,
    storage: authStorage,
  },
})

/* ============ snake_case(DB) ↔ camelCase(앱) 변환 ============ */
const toCamel = (s) => s.replace(/_([a-z0-9])/g, (_, c) => c.toUpperCase())
const toSnake = (s) => s.replace(/[A-Z]/g, (c) => '_' + c.toLowerCase())

export function rowToCamel(row) {
  if (!row || typeof row !== 'object') return row
  const o = {}
  for (const k in row) o[toCamel(k)] = row[k]
  return o
}
export function rowsToCamel(rows) {
  return (rows || []).map(rowToCamel)
}
/** 앱 객체 → DB 컬럼(snake). undefined 는 제외 */
export function objToSnake(obj) {
  const o = {}
  for (const k in obj) {
    if (obj[k] !== undefined) o[toSnake(k)] = obj[k]
  }
  return o
}

/** 쿼리 에러를 throw 로 통일 */
export function unwrap({ data, error }) {
  if (error) throw error
  return data
}

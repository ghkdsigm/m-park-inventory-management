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

export const supabase = createClient(url || 'http://localhost', anonKey || 'anon', {
  auth: {
    persistSession: true,
    autoRefreshToken: true,
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

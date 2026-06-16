/**
 * 날짜 공통 유틸 (화면마다 중복 구현 금지).
 * Firestore Timestamp / JS Date / 'YYYY-MM-DD' / millis 를 폭넓게 받는다.
 */

/** 어떤 값이든 JS Date 로 (없으면 null) */
export function toJsDate(v) {
  if (!v) return null
  if (typeof v.toDate === 'function') return v.toDate() // Firestore Timestamp
  if (v instanceof Date) return v
  const d = new Date(v)
  return isNaN(d.getTime()) ? null : d
}

const pad = (n) => String(n).padStart(2, '0')

/** 'YYYY-MM-DD' (input[type=date] 용 / 표시 용) */
export function fmtDate(v) {
  const d = toJsDate(v)
  if (!d) return ''
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 'M/D HH:mm' (이력 등 간단 표시) */
export function fmtDateTime(v) {
  const d = toJsDate(v)
  if (!d) return ''
  return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 기준일 + 주기 = 다음 날짜 (Date 반환) */
export function addCycle(base, value, unit) {
  const d = toJsDate(base) || new Date()
  const n = Number(value) || 0
  const out = new Date(d)
  if (unit === 'day') out.setDate(out.getDate() + n)
  else if (unit === 'year') out.setFullYear(out.getFullYear() + n)
  else out.setMonth(out.getMonth() + n) // 기본 month
  return out
}

/** 오늘부터 해당 날짜까지 남은 일수 (지났으면 음수, 값 없으면 null) */
export function daysUntil(v) {
  const d = toJsDate(v)
  if (!d) return null
  const a = new Date()
  a.setHours(0, 0, 0, 0)
  const b = new Date(d)
  b.setHours(0, 0, 0, 0)
  return Math.round((b - a) / 86400000)
}

/** 연한 상태: none(미설정) | ok(정상) | soon(임박) | over(초과) */
export function lifecycleStatus(nextDate, threshold = 30) {
  const days = daysUntil(nextDate)
  if (days === null) return 'none'
  if (days < 0) return 'over'
  if (days <= threshold) return 'soon'
  return 'ok'
}

export const CYCLE_UNITS = [
  { v: 'day', t: '일' },
  { v: 'month', t: '개월' },
  { v: 'year', t: '년' },
]

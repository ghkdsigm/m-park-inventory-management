// 엠파크 WMS — REST API 클라이언트 (Spring 백엔드).
// 백엔드 결합을 이 파일 + services/db.js·storage.js·stores/auth.js 로 격리한다.
// 화면(.vue) 코드는 db.js 등의 함수 시그니처/반환형에만 의존하므로 그대로 동작한다.

const BASE = (import.meta.env.VITE_API_BASE || 'http://localhost:8080/api').replace(/\/$/, '')

/* ============ 토큰 저장 + 자동로그인 토글 ============
 * '자동로그인' ON  → localStorage(영구)  / OFF → sessionStorage(브라우저 닫으면 만료)
 * (기존 supabase.js 의 자동로그인 동작을 그대로 계승) */
const TOKEN_KEY = 'mpark.token'
const AUTOLOGIN_KEY = 'mpark.autoLogin'

export function setAutoLogin(on) {
  try { localStorage.setItem(AUTOLOGIN_KEY, on ? '1' : '0') } catch (e) { /* ignore */ }
}
export function getAutoLogin() {
  try { return localStorage.getItem(AUTOLOGIN_KEY) !== '0' } catch (e) { return true }
}
export function getToken() {
  try { return localStorage.getItem(TOKEN_KEY) ?? sessionStorage.getItem(TOKEN_KEY) } catch (e) { return null }
}
export function setToken(t) {
  try {
    if (getAutoLogin()) { localStorage.setItem(TOKEN_KEY, t); sessionStorage.removeItem(TOKEN_KEY) }
    else { sessionStorage.setItem(TOKEN_KEY, t); localStorage.removeItem(TOKEN_KEY) }
  } catch (e) { /* ignore */ }
}
export function clearToken() {
  try { localStorage.removeItem(TOKEN_KEY); sessionStorage.removeItem(TOKEN_KEY) } catch (e) { /* ignore */ }
}

/* ============ 요청 ============ */
function qs(query) {
  if (!query) return ''
  const p = new URLSearchParams()
  for (const k in query) {
    const v = query[k]
    if (v !== undefined && v !== null && v !== '') p.append(k, v)
  }
  const s = p.toString()
  return s ? `?${s}` : ''
}

async function request(method, path, { body, query } = {}) {
  const headers = {}
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  const opts = { method, headers }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    opts.body = JSON.stringify(body)
  }
  const res = await fetch(`${BASE}${path}${qs(query)}`, opts)
  if (res.status === 204 || res.headers.get('content-length') === '0') {
    if (!res.ok) throw new Error('요청 실패')
    return null
  }
  let data = null
  const text = await res.text()
  if (text) {
    try { data = JSON.parse(text) } catch (e) { data = text }
  }
  if (!res.ok) {
    const msg = (data && data.message) || (typeof data === 'string' ? data : '') || `오류 (${res.status})`
    const err = new Error(msg)
    err.status = res.status
    throw err
  }
  return data
}

export const api = {
  get: (path, query) => request('GET', path, { query }),
  post: (path, body) => request('POST', path, { body }),
  put: (path, body) => request('PUT', path, { body }),
  del: (path, query) => request('DELETE', path, { query }),
  upload: async (path, formData) => {
    const headers = {}
    const token = getToken()
    if (token) headers.Authorization = `Bearer ${token}`
    const res = await fetch(`${BASE}${path}`, { method: 'POST', headers, body: formData })
    const text = await res.text()
    let data = null
    if (text) { try { data = JSON.parse(text) } catch (e) { data = text } }
    if (!res.ok) throw new Error((data && data.message) || '업로드 실패')
    return data
  },
}

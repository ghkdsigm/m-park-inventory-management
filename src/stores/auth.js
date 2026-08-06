import { defineStore } from 'pinia'
import { api, setToken, clearToken, getToken } from '@/api'

let initPromise = null

/**
 * 인증 (Spring + JWT).
 * - 로그인/가입 시 서버가 { token, profile } 반환 → 토큰 저장(자동로그인 토글에 따라 local/session)
 * - 앱 시작 시 토큰이 있으면 /auth/me 로 프로필 복원
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null,    // { id, username } — 화면 호환용
    profile: null, // { id, username, email, displayName, role, canStock, createdAt }
    ready: false,
  }),
  getters: {
    isLoggedIn: (s) => !!s.profile,
    role: (s) => s.profile?.role || null,
    isSuper: (s) => s.profile?.role === 'super',
    isManager: (s) => s.profile?.role === 'manager',
    isRegistrar: (s) => s.profile?.role === 'registrar',
    // 백오피스 관리(기준정보/상품/위치/재고조정·실사·이동) = 슈퍼관리자 또는 매니저
    canManage: (s) => s.profile?.role === 'super' || s.profile?.role === 'manager',
    // 입/출고 = 세 역할 모두
    canStock: (s) => ['super', 'manager', 'registrar'].includes(s.profile?.role),
    // 하위호환(설정 메뉴 = 슈퍼관리자 전용)
    isAdmin: (s) => s.profile?.role === 'super',
    displayName: (s) => s.profile?.displayName || s.profile?.username || '사용자',
    actor: (s) => ({ uid: s.profile?.id, name: s.profile?.displayName || s.profile?.username || '사용자' }),
  },
  actions: {
    /** 앱 시작 시 1회: 토큰으로 세션 복원 */
    init() {
      if (this.ready) return Promise.resolve()
      if (initPromise) return initPromise
      initPromise = (async () => {
        if (getToken()) {
          try {
            const profile = await api.get('/auth/me')
            this._setProfile(profile)
          } catch (e) {
            clearToken() // 만료/무효 토큰
            this._setProfile(null)
          }
        }
        this.ready = true
      })()
      return initPromise
    },
    _setProfile(profile) {
      this.profile = profile || null
      this.user = profile ? { id: profile.id, username: profile.username } : null
    },
    async login(username, password) {
      const { token, profile } = await api.post('/auth/login', { username, password })
      setToken(token)
      this._setProfile(profile)
      return this.profile
    },
    async logout() {
      clearToken()
      this._setProfile(null)
    },
  },
})

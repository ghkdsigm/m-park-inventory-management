import { defineStore } from 'pinia'
import { supabase, rowToCamel } from '@/supabase'

// 동시 init 호출/중복 구독 방지 (모듈 레벨)
let initPromise = null
let subscribed = false

/**
 * 인증 (Supabase Auth).
 * - 가입 시 트리거가 public.profiles 행을 자동 생성(role=user)
 * - 최초 관리자는 Supabase Studio 또는 SQL 로 role 을 admin 으로 변경
 *
 * 주의: onAuthStateChange 콜백 안에서 supabase 호출을 await 하면 deadlock 위험
 *       → 콜백에선 user 만 동기 설정하고, 프로필 로드는 콜백 밖(setTimeout)에서 처리
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null,
    profile: null,
    ready: false,
  }),
  getters: {
    isLoggedIn: (s) => !!s.user,
    isAdmin: (s) => s.profile?.role === 'admin',
    displayName: (s) => s.profile?.displayName || s.user?.email || '사용자',
    actor: (s) => ({ uid: s.user?.id, name: s.profile?.displayName || s.user?.email || '사용자' }),
  },
  actions: {
    /** 앱 시작 시 1회: 세션 복원 + 변경 구독 */
    init() {
      if (this.ready) return Promise.resolve()
      if (initPromise) return initPromise
      initPromise = (async () => {
        // 1) 현재 세션 복원 (여기선 await 해도 안전)
        try {
          const { data } = await supabase.auth.getSession()
          const u = data.session?.user || null
          this.user = u
          this.profile = u ? await this._loadProfile(u.id).catch(() => null) : null
        } catch (e) {
          console.error('[Auth] 세션 확인 실패:', e)
        }
        this.ready = true

        // 2) 이후 상태 변화 구독 (1회만) — 콜백 안에서 await 금지
        if (!subscribed) {
          subscribed = true
          supabase.auth.onAuthStateChange((_event, session) => {
            const u = session?.user || null
            this.user = u
            if (!u) {
              this.profile = null
              return
            }
            // 프로필 로드는 콜백 밖에서 (deadlock 방지)
            setTimeout(() => {
              this._loadProfile(u.id)
                .then((p) => {
                  this.profile = p
                })
                .catch(() => {})
            }, 0)
          })
        }
      })()
      return initPromise
    },
    async _loadProfile(uid) {
      const { data, error } = await supabase.from('profiles').select('*').eq('id', uid).maybeSingle()
      if (error) throw error
      return data ? rowToCamel(data) : null
    },
    async login(email, password) {
      const { data, error } = await supabase.auth.signInWithPassword({ email, password })
      if (error) throw error
      this.user = data.user
      this.profile = await this._loadProfile(data.user.id).catch(() => null)
      return this.profile
    },
    /** 회원가입: 기본 역할 user (트리거가 profiles 생성) */
    async register(email, password, displayName) {
      const { data, error } = await supabase.auth.signUp({
        email,
        password,
        options: { data: { display_name: displayName || email } },
      })
      if (error) throw error
      this.user = data.user || data.session?.user || null
      if (this.user) {
        this.profile = await this._loadProfile(this.user.id).catch(() => null)
      }
      return this.profile
    },
    async logout() {
      await supabase.auth.signOut()
      this.user = null
      this.profile = null
    },
  },
})

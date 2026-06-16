import { defineStore } from 'pinia'
import { supabase, rowToCamel } from '@/supabase'

/**
 * 인증 (Supabase Auth).
 * - 가입 시 트리거가 public.profiles 행을 자동 생성(role=user)
 * - 최초 관리자는 Supabase Studio 또는 SQL 로 role 을 admin 으로 변경
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null, // supabase auth user
    profile: null, // profiles row (camelCase)
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
      return new Promise((resolve) => {
        let settled = false
        const done = () => {
          if (settled) return
          settled = true
          this.ready = true
          resolve()
        }
        const timer = setTimeout(done, 5000)
        supabase.auth
          .getSession()
          .then(async ({ data }) => {
            this.user = data.session?.user || null
            if (this.user) {
              try {
                this.profile = await this._loadProfile(this.user.id)
              } catch (e) {
                console.error('[Auth] 프로필 로드 실패:', e)
              }
            }
            clearTimeout(timer)
            done()
          })
          .catch((e) => {
            console.error('[Auth] 세션 확인 실패:', e)
            clearTimeout(timer)
            done()
          })

        // 로그인/로그아웃 등 상태 변화 구독
        supabase.auth.onAuthStateChange(async (_event, session) => {
          this.user = session?.user || null
          this.profile = this.user ? await this._loadProfile(this.user.id).catch(() => null) : null
        })
      })
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
      this.profile = await this._loadProfile(data.user.id)
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
      this.user = data.user
      // 이메일 확인이 꺼져 있으면 즉시 세션 발급됨
      if (data.user) {
        this.profile = await this._loadProfile(data.user.id).catch(() => null)
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

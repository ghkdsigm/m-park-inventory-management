import { defineStore } from 'pinia'
import { auth, db } from '@/firebase'
import {
  onAuthStateChanged,
  signInWithEmailAndPassword,
  createUserWithEmailAndPassword,
  signOut,
  updateProfile,
} from 'firebase/auth'
import { doc, getDoc, setDoc, serverTimestamp } from 'firebase/firestore'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null, // firebase user
    profile: null, // users/{uid} 문서 (role 등)
    ready: false, // 최초 인증상태 확인 완료 여부
  }),
  getters: {
    isLoggedIn: (s) => !!s.user,
    isAdmin: (s) => s.profile?.role === 'admin',
    displayName: (s) => s.profile?.displayName || s.user?.email || '사용자',
    actor: (s) => ({ uid: s.user?.uid, name: s.profile?.displayName || s.user?.email || '사용자' }),
  },
  actions: {
    /** 앱 시작 시 1회 호출: 인증상태 구독 */
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
        // Firebase 설정 누락/오류로 콜백이 오지 않아도 앱이 멈추지 않도록 안전장치
        const timer = setTimeout(done, 5000)
        onAuthStateChanged(
          auth,
          async (u) => {
            this.user = u
            try {
              this.profile = u ? await this._ensureProfile(u) : null
            } catch (e) {
              console.error('[Auth] 프로필 로드 실패(보안 규칙 배포 확인):', e)
              this.profile = null
            }
            clearTimeout(timer)
            done()
          },
          (err) => {
            console.error('[Auth] 인증 상태 확인 실패:', err)
            clearTimeout(timer)
            done()
          }
        )
      })
    },
    async _loadProfile(uid) {
      const d = await getDoc(doc(db, 'users', uid))
      return d.exists() ? { id: d.id, ...d.data() } : null
    },
    /** 프로필 문서가 없으면 자동 생성 (규칙 잠금 등으로 가입 시 누락된 경우 복구) */
    async _ensureProfile(user) {
      let profile = await this._loadProfile(user.uid)
      if (!profile) {
        profile = {
          email: user.email,
          displayName: user.displayName || user.email,
          role: 'user',
          createdAt: serverTimestamp(),
        }
        await setDoc(doc(db, 'users', user.uid), profile)
        profile = { id: user.uid, ...profile }
      }
      return profile
    },
    async login(email, password) {
      const cred = await signInWithEmailAndPassword(auth, email, password)
      this.user = cred.user
      this.profile = await this._ensureProfile(cred.user)
      return this.profile
    },
    /** 회원가입: 기본 역할 user. (최초 관리자는 README 참고하여 콘솔에서 승격) */
    async register(email, password, displayName) {
      const cred = await createUserWithEmailAndPassword(auth, email, password)
      if (displayName) await updateProfile(cred.user, { displayName })
      const profile = {
        email,
        displayName: displayName || email,
        role: 'user',
        createdAt: serverTimestamp(),
      }
      await setDoc(doc(db, 'users', cred.user.uid), profile)
      this.user = cred.user
      this.profile = { id: cred.user.uid, ...profile }
      return this.profile
    },
    async logout() {
      await signOut(auth)
      this.user = null
      this.profile = null
    },
  },
})

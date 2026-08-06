<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { setAutoLogin, getAutoLogin } from '@/supabase'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const toast = useToast()

const username = ref('')
const password = ref('')
const loading = ref(false)

// 아이디 저장 / 자동 로그인
const ID_KEY = 'mpark.savedUsername'
const rememberId = ref(true)
const autoLogin = ref(true)
try {
  const u = localStorage.getItem(ID_KEY)
  if (u) { username.value = u; rememberId.value = true } else { rememberId.value = false }
  autoLogin.value = getAutoLogin()
} catch (e) { /* ignore */ }

async function submit() {
  const id = (username.value || '').trim().replace(/\s/g, '')
  const pw = password.value || ''
  if (!id || !pw) {
    toast.error('아이디와 비밀번호를 입력하세요.')
    return
  }
  setAutoLogin(autoLogin.value)
  try {
    if (rememberId.value) localStorage.setItem(ID_KEY, id); else localStorage.removeItem(ID_KEY)
  } catch (e) { /* ignore */ }
  loading.value = true
  try {
    await auth.login(id, pw)
    // 등록인은 모바일 입출고 단말로
    const redirect = auth.isRegistrar ? '/s' : (route.query.redirect || '/')
    router.replace(redirect)
  } catch (e) {
    toast.error(e?.message || '아이디 또는 비밀번호가 올바르지 않습니다.')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="flex min-h-full items-center justify-center bg-gradient-to-br from-brand-600 to-brand-900 p-4">
    <div class="w-full max-w-sm">
      <div class="mb-6 text-center text-white">
        <div class="mx-auto mb-3 flex h-14 w-14 items-center justify-center rounded-2xl bg-white/15 text-2xl font-bold backdrop-blur">M</div>
        <h1 class="text-xl font-bold">엠파크 재고관리</h1>
        <p class="mt-1 text-sm text-white/70">중고차매매단지 단지별 재고관리</p>
      </div>

      <div class="card p-6">
        <h2 class="mb-4 text-center text-sm font-semibold text-slate-600">로그인</h2>

        <form class="space-y-3" @submit.prevent="submit">
          <div>
            <label class="label">아이디</label>
            <input v-model="username" class="input" placeholder="아이디" autocomplete="username" />
          </div>
          <div>
            <label class="label">비밀번호</label>
            <input v-model="password" type="password" class="input" placeholder="••••••••" autocomplete="current-password" />
          </div>
          <div class="flex items-center gap-4 pt-0.5 text-sm text-slate-600">
            <label class="flex cursor-pointer items-center gap-1.5"><input v-model="rememberId" type="checkbox" class="h-4 w-4 rounded border-slate-300" /> 아이디 저장</label>
            <label class="flex cursor-pointer items-center gap-1.5"><input v-model="autoLogin" type="checkbox" class="h-4 w-4 rounded border-slate-300" /> 자동 로그인</label>
          </div>
          <button class="btn-primary mt-2 w-full" :disabled="loading">
            {{ loading ? '처리 중…' : '로그인' }}
          </button>
        </form>

        <p class="mt-3 text-center text-[11px] leading-relaxed text-slate-400">
          계정이 필요하면 관리자에게 요청하세요.
        </p>
      </div>
    </div>
  </div>
</template>

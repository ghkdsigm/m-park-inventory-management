<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const toast = useToast()

const mode = ref('login') // login | register
const email = ref('')
const password = ref('')
const displayName = ref('')
const loading = ref(false)

// Supabase 인증 에러 메시지(영문) → 한글 매핑
function krError(e) {
  const m = (e?.message || '').toLowerCase()
  if (m.includes('invalid login')) return '이메일 또는 비밀번호가 올바르지 않습니다.'
  if (m.includes('already registered') || m.includes('already been registered')) return '이미 사용 중인 이메일입니다.'
  if (m.includes('password should be')) return '비밀번호는 6자 이상이어야 합니다.'
  if (m.includes('email') && m.includes('invalid')) return '이메일 형식이 올바르지 않습니다.'
  if (m.includes('not confirmed') || m.includes('confirm')) return '이메일 인증이 필요합니다. (관리자: Supabase에서 이메일 확인 비활성화 권장)'
  if (m.includes('rate limit') || m.includes('too many')) return '잠시 후 다시 시도해주세요.'
  return '오류가 발생했습니다. (' + (e?.message || '') + ')'
}

async function submit() {
  if (!email.value || !password.value) {
    toast.error('이메일과 비밀번호를 입력하세요.')
    return
  }
  loading.value = true
  try {
    if (mode.value === 'login') {
      await auth.login(email.value, password.value)
    } else {
      await auth.register(email.value, password.value, displayName.value)
      toast.success('가입 완료! 환영합니다.')
    }
    const redirect = route.query.redirect || '/'
    router.replace(redirect)
  } catch (e) {
    toast.error(krError(e))
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
        <div class="mb-4 flex rounded-lg bg-slate-100 p-1 text-sm font-medium">
          <button
            class="flex-1 rounded-md py-1.5 transition"
            :class="mode === 'login' ? 'bg-white text-brand-700 shadow-sm' : 'text-slate-500'"
            @click="mode = 'login'"
          >
            로그인
          </button>
          <button
            class="flex-1 rounded-md py-1.5 transition"
            :class="mode === 'register' ? 'bg-white text-brand-700 shadow-sm' : 'text-slate-500'"
            @click="mode = 'register'"
          >
            회원가입
          </button>
        </div>

        <form class="space-y-3" @submit.prevent="submit">
          <div v-if="mode === 'register'">
            <label class="label">이름</label>
            <input v-model="displayName" class="input" placeholder="홍길동" />
          </div>
          <div>
            <label class="label">이메일</label>
            <input v-model="email" type="email" class="input" placeholder="you@m-park.co.kr" autocomplete="username" />
          </div>
          <div>
            <label class="label">비밀번호</label>
            <input v-model="password" type="password" class="input" placeholder="••••••••" autocomplete="current-password" />
          </div>
          <button class="btn-primary mt-2 w-full" :disabled="loading">
            {{ loading ? '처리 중…' : mode === 'login' ? '로그인' : '가입하기' }}
          </button>
        </form>

        <p v-if="mode === 'register'" class="mt-3 text-center text-[11px] leading-relaxed text-slate-400">
          가입 시 기본 권한은 <b>일반 사용자</b>입니다.<br />관리자 권한은 관리자에게 요청하세요.
        </p>
      </div>
    </div>
  </div>
</template>

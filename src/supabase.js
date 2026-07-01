// 백엔드를 Supabase → Spring REST 로 교체(포팅)했습니다.
// 이 파일은 기존 import 경로(@/supabase)를 깨지 않기 위해 자동로그인 헬퍼만 재노출합니다.
// 실제 통신은 src/api.js + services/db.js·storage.js·stores/auth.js 에서 처리합니다.
export { setAutoLogin, getAutoLogin } from '@/api'

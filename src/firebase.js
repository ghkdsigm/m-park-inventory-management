import { initializeApp } from 'firebase/app'
import { getAuth } from 'firebase/auth'
import { getFirestore } from 'firebase/firestore'
import { getStorage } from 'firebase/storage'

// 환경변수(.env)에서 설정값을 읽어옵니다. (.env.example 참고)
const firebaseConfig = {
  apiKey: "AIzaSyDX03Mv5qQxjYBjrB7V3TCAr08rE6J_uyQ",
  authDomain: "mpark-inventory-management.firebaseapp.com",
  projectId: "mpark-inventory-management",
  storageBucket: "mpark-inventory-management.firebasestorage.app",
  messagingSenderId: "153577461637",
  appId: "1:153577461637:web:f5802f64d4fa15b36396ec"
};

// 설정 누락 시 개발 중 빨리 알아챌 수 있도록 경고
if (!firebaseConfig.apiKey) {
  console.warn(
    '[Firebase] .env 설정이 비어 있습니다. .env.example 을 복사해 .env 를 만들고 값을 채워주세요.'
  )
}

const app = initializeApp(firebaseConfig)

export const auth = getAuth(app)
export const db = getFirestore(app)
export const storage = getStorage(app)
export default app

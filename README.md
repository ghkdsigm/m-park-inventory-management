# 엠파크 WMS (m-park Inventory Management)

엠파크 중고차매매단지 **단지(창고)별 시설자재 재고관리 시스템(WMS)**.
PC는 백오피스, 모바일(`/s`)은 앱처럼 동작하는 반응형 웹앱입니다.

- **프론트엔드:** Vue 3 + Vite + Tailwind CSS + Pinia + Vue Router
- **백엔드:** Spring Boot 3.2 (Java 17) + Spring Data JPA + Spring Security(JWT) + Flyway
- **DB:** MySQL 8
- **AI:** OpenAI — 챗봇(툴 기반 라이브 DB 조회)·사진 제품검색(Vision)·견적서 자동추출 / MiniMax TTS(선택, 음성 답변)
- **QR:** SKU 생성 시 자동 발급 → 모바일 스캔으로 입고/출고/이동/조정/실사

> **이력 메모:** 초기엔 Firebase → Supabase(PostgreSQL) 로 만들었다가, 현재는 **Spring Boot + MySQL** 백엔드로 완전 이전했습니다.
> 프론트는 `src/services/*`(api/db/storage)·`src/stores/auth.js` 계층으로만 백엔드에 접근하므로 화면 코드는 그대로입니다.
> ⚠️ `package.json` 에 `@supabase/*` 의존성/`db:*` 스크립트가 잔재로 남아 있으나 **현재는 사용하지 않습니다**(정리 대상).

---

## 1. 저장소 구조

```
/                    프론트엔드 (Vue 3 + Vite)  — 루트가 프론트
├─ src/
│  ├─ pages/         화면 (백오피스 + mobile/ = /s 스캔앱)
│  ├─ components/    공통 UI (레이아웃, QR 모달 등)
│  ├─ services/      백엔드 REST 호출 계층 (api/db/storage/qr)
│  ├─ stores/        Pinia (auth 등)
│  └─ router/        Vue Router (/s, /s/:code, 백오피스)
├─ netlify.toml      프론트 배포(Netlify) 설정
└─ backend/          백엔드 (Spring Boot + MySQL)
   ├─ src/main/java/com/mpark/wms/
   │   auth · chat · quote · master · product · sku · stock ·
   │   movement · location · storage · audit · common · config
   ├─ src/main/resources/db/migration/   Flyway (V1~V22)
   ├─ docker-compose.yml   로컬 MySQL8 + 백엔드
   ├─ Dockerfile           멀티스테이지 gradle 빌드
   └─ railway.json         Railway 배포(Dockerfile) 설정
```

---

## 2. 빠른 시작 — 로컬

### 백엔드 + MySQL (Docker, 호스트에 Java/Gradle 불필요)
```bash
cd backend
docker compose up -d --build
#  → 백엔드 http://localhost:8080  ·  MySQL 호스트포트 3307 → 컨테이너 3306
#  → 기동 시 Flyway 가 V1~V22 마이그레이션을 적용해 스키마 자동 생성
```
`backend/.env` 에 `JWT_SECRET` 은 반드시 지정해야 기동됩니다(`.env.example` 복사 후 채움).

### 프론트엔드
```bash
npm install
npm run dev
#  → http://localhost:5173 (또는 5175). 백엔드 CORS 는 5173~5177 허용
#  → VITE_API_BASE 기본값 http://localhost:8080/api
```

### 로그인
- **아이디 `admin` / 비밀번호 `123123`** (role=super)
- 기동 시 `AdminBootstrap` 이 `admin` 계정을 super 로 승격(멱등). MySQL 볼륨이 유지돼 계정은 계속 남습니다.

> 로컬에 남아있는 Supabase 스택(54321 등)은 **인증에 쓰지 않는 포팅 잔재**입니다.

---

## 3. 인증 · 권한

- **아이디(username) 기반 로그인** — `POST /api/auth/login {username,password}` (BCrypt). 이메일 아님.
- **공개 회원가입 없음** — 계정은 슈퍼관리자가 백오피스 **사용자관리**(`/api/users`, super 전용)에서 등록/수정/삭제.
- **역할 3단계**
  | 역할 | 권한 |
  |---|---|
  | `super` | 전체 |
  | `manager` | 사용자관리·감사로그·AI사용량 제외 전부 |
  | `registrar` | 모바일 `/s` 입고·출고 + 챗봇 (백오피스 접근 시 `/s` 리다이렉트) |
- 규칙은 `SecurityConfig`(hasRole) + `CurrentUser.isSuper()/canManage()/canStock()`.

---

## 4. 데이터 모델 (MySQL · Flyway V1~V22)

```
profiles           사용자/권한 (username 로그인, role: super|manager|registrar, can_stock)
seq_counters       자동코드 시퀀스 카운터 (Postgres 시퀀스 대체, 행 락)
complexes          단지 (코드 직접입력: TWR/HUB/LND)
categories         카테고리 (코드 CTG-NN — 기계01·소방02·영선03·전기04)
product_codes      제품코드 (PC-000001)
product_details    제품상세코드 (PCD-000001)
zones / sub_zones  위치코드 (구역 / 상세구역)
storage_locations  보관위치 (LOC-000001)
products           상품(품목) — 단지+카테고리 귀속, 대표/다중 이미지
skus               SKU(규격 변형) — 단지 귀속, QR, 안전재고, 연한설정, 치수
stock              재고행 — SKU당 1행(단일 보관위치), 수량·상태(in_stock/low/out)
stock_movements    재고 원장 (in/out/adjust/audit/void/move)
lifecycle_logs     연한(주기 교체) 이력
location_logs      위치 이동 이력
quotes/quote_items 견적서 + 품목 (AI 추출·SKU 연결)
ai_usage           AI 사용량/비용 집계
audit_logs         감사 로그
daily_stats        일별 집계
idempotency_keys   재고 작업 멱등키
```

- **자동코드**: `seq_counters` + 서비스 채번(비관적 락). Postgres 트리거를 서비스 로직으로 대체.
- **재고 = SKU당 1행(단일 위치)**: 재고이동은 재고행의 위치값 변경(`move`)으로 기록.
- **감사·집계·이력**은 서비스 트랜잭션에서 원자적으로 갱신.

---

## 5. 코드 채번 규칙 (현행)

`단지 > 카테고리 > 상품(품목) > SKU(규격)` 구조를 코드에 반영합니다.

| 대상 | 형식 | 예 |
|---|---|---|
| 상품(품목) | `{단지코드}-{카테고리번호2}-P{품목순번3}` | `TWR-04-P036` |
| SKU(규격) | `{상품코드}-{고유번호}` | `TWR-04-P036-42` |

- **단지코드**: 타워=`TWR` · 허브=`HUB` · 랜드=`LND` (`complexes.code`)
- **카테고리번호**: 기계=`01` · 소방=`02` · 영선=`03` · 전기=`04` (`categories.code` = `CTG-NN`)
- **고유번호**: `(단지 × 카테고리)` 단위 SKU(규격)별 일련번호. **패딩 없음**(1~9999). 한 상품 아래 규격들이 각자 다른 고유번호를 가짐.
- **경로 라벨(`path_label`)**: `단지 > 카테고리 > 고유번호` (예: `허브 > 전기 > 47`) — 재고현황·모바일 리스트의 경로 컬럼에 표시.
- 채번 채널: 상품 `prodp:{단지}:{카테고리}`, SKU `skuuid:{단지}:{카테고리}` (`seq_counters`).

---

## 6. 핵심 기능

- **기준정보**: 단지 · 카테고리 · 제품코드 · 제품상세 · 위치코드(구역/상세구역) · 보관위치
- **상품 / SKU**: 상품(품목) → SKU(규격) 등록, 대표/다중 이미지, QR 자동발급, 안전재고, 연한(주기 교체), 치수
- **재고**: 입고 · 출고 · 재고이동 · 재고조정 · 재고실사 — 원장/이력/집계/감사로그 자동, 사유 필수, 당일 취소(역분개)
- **재고 현황 / 통합조회 / 대시보드**: 상태(정상/부족/품절), 단지·카테고리 필터, SKU 단위 집계
- **견적서**: PDF 업로드 → AI 추출(품명/규격/수량/단가) → 품명+단지로 SKU 자동추천/연결 (단가·수량 참조용, 재고 자동반영 아님)
- **AI 챗봇**: 툴 기반으로 **라이브 DB 조회**(search_sku/search_location/stock_overview) — 입출고·조회·안내. 음성 답변(MiniMax TTS, 선택)
- **사진 제품검색**: 사진 → Vision 분류 → 후보 SKU 매칭
- **모바일 `/s`**: QR 스캔 또는 단지>카테고리>상품>SKU 탐색 → 현장 입고/출고/교체

재고상태 기준: **재고 ≤ 0 → 품절 / 안전재고>0 이고 재고 ≤ 안전재고 → 부족 / 그 외 정상.**

---

## 7. 스크립트

| 명령 | 설명 |
|---|---|
| `npm run dev` | 프론트 개발 서버 (LAN 노출, 모바일 테스트) |
| `npm run build` | 프론트 프로덕션 빌드 → `dist/` |
| `npm run preview` | 빌드 결과 미리보기 |
| `cd backend && docker compose up -d --build` | 백엔드 + MySQL 기동 (:8080) |
| `docker logs -f mpark-backend` | 백엔드 로그 |

> `db:start/stop/reset/push` 스크립트는 옛 Supabase용 잔재로 현재는 사용하지 않습니다.

---

## 8. 배포

운영은 **Railway(백엔드 Spring + MySQL) + Netlify(프론트)** 구성입니다. 자세한 절차는 **[DEPLOY.md](./DEPLOY.md)** 참조.
- 백엔드 배포 브랜치: `feature/porting01` (푸시 시 Railway 자동 빌드)
- 프론트: Netlify (GitHub 연동, `netlify.toml` 자동 적용)

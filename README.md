# 엠파크 WMS (m-park Inventory Management)

엠파크 중고차매매단지 **단지(창고)별 재고관리 시스템(WMS)**.
PC는 백오피스, 모바일은 앱처럼 동작하는 반응형 웹앱입니다.

- **프론트엔드:** Vue 3 + Vite + Tailwind CSS + Pinia + Vue Router
- **백엔드:** **Supabase (PostgreSQL · Auth · Storage)** — 로컬은 Docker
- **QR:** SKU 생성 시 자동 발급 → 스캔 시 입고/출고/조정/실사

> v2.0 부터 백엔드를 Firebase → **Supabase/PostgreSQL** 로 이전했습니다.
> 프론트는 Firebase를 직접 모르고 `src/services/db.js`·`storage.js`·`stores/auth.js` 3곳만 통해 접근하도록 설계돼 있어 화면 코드는 그대로입니다.

---

## 1. 사전 준비
- Node.js 20+ / npm
- **Docker Desktop** (로컬 Supabase 스택 구동용)
- (선택) **Supabase CLI** — `npm i -g supabase` 또는 scoop/brew

---

## 2. 빠른 시작 — 로컬(Docker)

```powershell
# 1) 의존성
npm install

# 2) 로컬 Supabase 스택 기동 (Docker 필요). 마이그레이션 자동 적용됨
supabase start
#   → 출력되는 API URL / anon key 를 복사 (예: http://localhost:54321, anon key: ey...)

# 3) 환경변수
Copy-Item .env.example .env
#   .env 에 위 API URL / anon key 입력

# 4) 개발 서버
npm run dev          # http://localhost:5175
```

- `supabase start` 가 `supabase/migrations/*.sql` 을 적용해 **테이블·트리거·함수·RLS·Storage 버킷**까지 만들어 줍니다.
- Studio(관리 UI): http://localhost:54323 / 메일 확인함(Inbucket): http://localhost:54324
- 스키마 바꾸면 `supabase db reset` (로컬 DB 초기화 + 마이그레이션 재적용)

## 3. 빠른 시작 — Supabase 클라우드
1. [supabase.com](https://supabase.com) 프로젝트 생성
2. **SQL Editor** 에서 `supabase/migrations/` 의 3개 파일을 **순서대로**(schema → functions → rls) 실행
   - 또는 CLI: `supabase link --project-ref <ref>` 후 `supabase db push`
3. **Authentication → Providers → Email**: 사내용이면 **"Confirm email" 끄기**(즉시 로그인)
4. **Settings → API** 의 Project URL / anon key 를 `.env` 에 입력
5. `npm run dev` 또는 아래 Docker 배포

---

## 4. 최초 관리자 지정
회원가입 시 트리거가 `profiles` 행을 **role=user** 로 생성합니다. 첫 관리자는 직접 승격:
- Supabase **Studio → Table editor → profiles** 에서 본인 행의 `role` 을 `admin` 으로 변경
- 또는 SQL: `update public.profiles set role='admin' where email='you@example.com';`
- 이후엔 앱의 **사용자관리** 화면에서 다른 사용자를 승격할 수 있습니다.

---

## 5. Docker 로 프론트 배포

```powershell
# .env 에 운영 Supabase URL/anon key 가 있어야 함 (빌드 시 주입됨)
docker compose up -d --build
#   → http://localhost:8080
```
Vite 환경변수는 **빌드 시점에 주입**되므로, 운영 도메인이 바뀌면 다시 빌드해야 합니다.

---

## 6. 데이터 모델 (PostgreSQL)
`supabase/migrations/20260616090001_schema.sql` 참고.

```
profiles            사용자/권한 (auth.users 1:1, role: admin|user)
complexes           단지(코드 직접입력)
categories          카테고리 (코드 자동 CTG-000001)
product_codes       제품코드 (PC-000001)
product_details     제품상세코드 (PCD-000001)
products            상품 (P-000001, 대표/다중 이미지)
skus                SKU (상품코드-001, 재고·속성·위치·연한)
zones / sub_zones   위치코드(구역/상세구역)
storage_locations   보관위치 (LOC-000001)
stock_movements     재고원장 (in/out/adjust/audit)
lifecycle_logs      연한 교체 이력
daily_stats         일별 집계
```
- **자동코드**: 시퀀스 + BEFORE INSERT 트리거. SKU는 상품 `sku_seq` 증가로 순번 보장
- **재고/교체 트랜잭션**: `apply_stock()`, `replace_lifecycle()` (SECURITY DEFINER 함수=RPC). 재고수량·원장·집계가 원자적
- **보안(RLS)**: 조회=로그인 사용자, 관리(쓰기)=admin. 재고/교체는 함수로만 → 일반 사용자도 입출고/교체 가능, 정책수정은 admin

---

## 7. 핵심 기능
기준정보(4단계 코드) → 상품 → SKU(QR) → 위치(구역/상세/보관위치) → 입고/출고/조정/실사/연한 → 현황·통합조회. 모바일 QR 스캔으로 현장 입출고·교체. 이미지(상품 대표/다중, SKU 옵션, 우선순위 표시). 자세한 화면 구성은 좌측 메뉴 참조.

---

## 스크립트
| 명령 | 설명 |
|---|---|
| `npm run dev` | 개발 서버 (LAN 노출, 모바일 테스트) |
| `npm run build` | 프로덕션 빌드 → `dist/` |
| `npm run preview` | 빌드 결과 미리보기 |
| `npm run db:start` / `db:stop` | 로컬 Supabase 스택(Docker) 시작/정지 |
| `npm run db:reset` | 로컬 DB 초기화 + 마이그레이션 재적용 |
| `npm run db:push` | (클라우드 link 후) 마이그레이션 푸시 |
| `docker compose up -d --build` | 프론트 컨테이너 배포 (:8080) |

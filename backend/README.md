# 엠파크 WMS — 백엔드 (Spring Boot + MySQL)

Supabase(PostgreSQL · RPC · Auth · Storage)를 대체하는 Java 백엔드. 프론트(Vue)는 그대로 두고
`src/services/db.js`·`storage.js`·`stores/auth.js` 만 이 API 를 보도록 교체한다(Phase 7).

- **스택:** Spring Boot 3.2 / Java 17 / Gradle / Spring Data JPA / Spring Security / Flyway
- **DB:** MySQL 8 (로컬은 Docker)

## 로컬 실행

```bash
# 1) MySQL 기동 (Docker)
cd backend
docker compose up -d

# 2) (최초 1회) Gradle Wrapper 생성 — gradle 설치돼 있거나 IntelliJ 로 열면 자동
gradle wrapper        # 또는 IntelliJ "Open" 으로 backend 폴더 열기

# 3) 앱 실행 (Flyway 가 V1__init.sql 로 스키마 자동 생성)
./gradlew bootRun     # Windows: gradlew.bat bootRun
#   → http://localhost:8080
```

환경변수(기본값 있음): `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `CORS_ORIGINS`, `STORAGE_DIR`.

## 진행 단계 (포팅 로드맵)

- [x] **Phase 1** — 스캐폴드 + Flyway 전체 스키마 + 공통(예외/보안/CORS/BaseEntity)
- [x] **Phase 2** — 기준정보 CRUD + 자동코드(seq_counters)
- [x] **Phase 3** — 위치/상품/SKU CRUD + 검색/페이징 + 집계
- [x] **Phase 4** — 재고 트랜잭션(입출고/취소/연한/위치) + 원장·이력·집계 + 감사로그
- [x] **Phase 5** — 인증(JWT) + 권한(role/can_stock)
- [x] **Phase 6** — 이미지 스토리지(/files 서빙)
- [x] **Phase 7** — 프론트 4파일 교체 (api.js/db.js/storage.js/auth.js, Vue 화면 무수정)
- [ ] **Phase 8** — 로컬 통합 실행/검증  ← 지금 단계 (아래 가이드)

남은 보완: 기준정보/상품/SKU/위치 **CRUD 감사로그**(현재는 재고 작업만 기록).

## 전체 로컬 실행 (프론트 + 백엔드 + MySQL)

```bash
# 1) MySQL
cd backend && docker compose up -d

# 2) 백엔드 (Flyway 가 스키마 생성)
gradle wrapper            # 최초 1회 (또는 IntelliJ 로 backend 열기)
./gradlew bootRun         # http://localhost:8080

# 3) 프론트 (저장소 루트)
cd ..
npm install
npm run dev               # http://localhost:5175  (VITE_API_BASE 기본 localhost:8080/api)
```

### 스모크 테스트 체크리스트
1. 회원가입(`admin@dongwha.com`) → 재기동 시 자동 관리자 승격 → 로그인
2. 기준정보(단지→카테고리→제품코드→상세) 생성 → 코드 자동(CTG-/PC-/PCD-) 확인
3. 상품 생성(이미지 업로드) → SKU 생성(코드 `P-000001-001`, 최초수량이면 입고 이력 1건)
4. 입고/출고 → 재고·원장·대시보드 반영, 사유 필수
5. 최근 이력에서 당일 본인 처리 "취소" → 역분개 기록
6. 일반 계정으로 로그인 → 입/출고 차단(권한 부여 후 가능), 조정/실사는 admin만
7. 모바일 QR: `http://localhost:5175/s/P-000001-001`

## Supabase ↔ Spring 매핑 (요약)

| Supabase | Spring |
|---|---|
| PostgREST 자동 API | `@RestController` |
| PL/pgSQL RPC (apply_stock 등) | `@Service @Transactional` (+ 비관적 락) |
| 트리거(자동코드/감사/updated_at) | 서비스 로직 / JPA Auditing |
| RLS | Spring Security `@PreAuthorize` |
| Supabase Auth | Spring Security + JWT |
| Supabase Storage | 로컬 디스크/S3 + `/files` 서빙 |

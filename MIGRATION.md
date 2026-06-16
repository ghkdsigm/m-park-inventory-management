# 마이그레이션 & 아키텍처 가이드 (Firebase → NestJS + PostgreSQL + EC2)

> 이 시스템은 **나중에 NestJS + PostgreSQL + EC2(+S3)** 로 전환할 가능성이 높습니다.
> 그래서 처음부터 **백엔드 교체가 쉬운 구조**로 설계했습니다.
> **앞으로 모든 기능 작업은 이 문서의 "코딩 규칙"을 지켜서** 마이그레이션 비용을 낮게 유지하세요.

---

## 1. 핵심 원칙 — 백엔드는 3곳에만

화면(컴포넌트/페이지)은 Firebase를 **직접 알지 못합니다.** 모든 외부 접근은 아래 3개 파일에만 있습니다.

| 계층 | 파일 | 역할 |
|---|---|---|
| 데이터 | `src/services/db.js` | Firestore CRUD·트랜잭션·자동채번·집계 |
| 이미지 | `src/services/storage.js` | Storage 업로드/삭제 |
| 인증 | `src/stores/auth.js` | 로그인/회원가입/세션/역할 |

→ 전환 시 **이 3개 파일의 내부만 REST(axios) 호출로 교체**하면 화면 코드는 거의 그대로 유지됩니다.

---

## 2. 코딩 규칙 (앞으로 작업 시 필수 준수)

**DO**
- DB/이미지/인증 접근은 **반드시 `services/*` 또는 `stores/auth.js`를 통해서만**. 새 기능도 여기에 함수 추가.
- 함수는 **순수 데이터(plain object/array)** 를 주고받기. 컴포넌트엔 Firestore 객체를 그대로 노출하지 않기. (현재 `{ id, ...data }` 형태 유지)
- **비즈니스 로직(트랜잭션·자동채번·재고계산)은 서비스 계층에** 둔다. 컴포넌트는 호출만.
- 관계는 **ID + FK**로 표현(이미 그렇게 됨). 비정규화 필드(`pathLabel`, `productMainImageUrl`, `complexName` 등)는 "조회 편의용 사본"임을 인지 — SQL에선 JOIN으로 대체.
- 필터/정렬은 가능하면 서비스 함수의 **인자**로 받게 설계(나중에 SQL WHERE/ORDER BY로 매핑 쉬움).

**DON'T**
- 컴포넌트에서 `firebase/*` 를 **직접 import 하지 않기.** (`firebase/firestore`, `firebase/storage` 등)
- 컴포넌트에서 `serverTimestamp()`, `Timestamp`, `runTransaction` 등 **Firestore 전용 API를 쓰지 않기.**
- 날짜 표시 시 `ts.toDate()` 를 컴포넌트 곳곳에 흩뿌리지 않기 → **공통 날짜 유틸로 모으기** (전환 시 ISO 문자열로 바뀜).

> ⚠️ 현재 일부 화면(StockHistory/Scan/Dashboard 등)에 `ts.toDate()` 포맷 함수가 중복돼 있습니다.
> 추후 `src/utils/date.js`로 공통화 예정 — 새 화면은 처음부터 공통 유틸을 쓰세요.

---

## 3. 목표 아키텍처

```
[Vue3 SPA (거의 그대로)]  ──HTTPS/REST──>  [Nginx]  ──>  [NestJS API (EC2)]  ──>  [PostgreSQL (RDS 또는 EC2)]
                                                              │
                                                              └──> [S3] (이미지, presigned URL)
```

| 현재(Firebase) | 이후(NestJS + PostgreSQL) |
|---|---|
| Firestore 컬렉션 | PostgreSQL 테이블 |
| `counters` 트랜잭션 자동코드 | PG **시퀀스** 또는 채번 테이블 + 트랜잭션 |
| `applyStock` 트랜잭션 | NestJS 서비스 + Prisma/TypeORM **트랜잭션** (`SELECT … FOR UPDATE`) |
| `applyAuditBatch` | 트랜잭션 루프 또는 단일 트랜잭션 배치 |
| Firebase Auth | NestJS **JWT** + bcrypt (`@nestjs/passport`) |
| 보안 규칙(rules) | NestJS **Guard / RBAC** (role: admin/user) |
| Storage(이미지) | **S3** presigned URL 업로드 |
| `dailyStats` 문서 | `daily_stats` 테이블 (트랜잭션 내 upsert) 또는 크론 집계 |
| 클라이언트 필터/정렬 | SQL `WHERE` / `ORDER BY` (인덱스로 더 빠름) |

---

## 4. PostgreSQL 스키마 (DDL 초안)

```sql
create extension if not exists "pgcrypto"; -- gen_random_uuid()

-- 사용자
create table users (
  id            uuid primary key default gen_random_uuid(),
  email         varchar(255) unique not null,
  display_name  varchar(100) not null,
  password_hash text not null,
  role          varchar(10) not null default 'user' check (role in ('admin','user')),
  created_at    timestamptz not null default now()
);

-- 기준정보 4단계
create table complexes (
  id uuid primary key default gen_random_uuid(),
  code varchar(40) unique not null,
  name varchar(100) not null,
  description text,
  created_at timestamptz not null default now()
);
create table categories (
  id uuid primary key default gen_random_uuid(),
  code varchar(40) unique not null,
  name varchar(100) not null,
  complex_id uuid not null references complexes(id) on delete restrict,
  description text,
  created_at timestamptz not null default now()
);
create table product_codes (
  id uuid primary key default gen_random_uuid(),
  code varchar(40) unique not null,
  name varchar(100) not null,
  category_id uuid not null references categories(id) on delete restrict,
  description text,
  created_at timestamptz not null default now()
);
create table product_details (
  id uuid primary key default gen_random_uuid(),
  code varchar(40) unique not null,
  name varchar(100) not null,
  product_code_id uuid not null references product_codes(id) on delete restrict,
  description text,
  created_at timestamptz not null default now()
);

-- 상품
create table products (
  id uuid primary key default gen_random_uuid(),
  code varchar(40) unique not null,            -- P-000001 (자동)
  name varchar(200) not null,
  complex_id        uuid not null references complexes(id),
  category_id       uuid references categories(id),
  product_code_id   uuid references product_codes(id),
  product_detail_id uuid references product_details(id),
  maker varchar(100),
  barcode varchar(60),
  note text,
  main_image_url text,
  created_at timestamptz not null default now()
);
create table product_images (   -- 다중 이미지
  id uuid primary key default gen_random_uuid(),
  product_id uuid not null references products(id) on delete cascade,
  url text not null,
  sort int default 0
);

-- 위치 (단지 > 구역 > 상세구역)
create table zones (
  id uuid primary key default gen_random_uuid(),
  name varchar(100) not null,
  complex_id uuid not null references complexes(id) on delete cascade,
  created_at timestamptz not null default now()
);
create table sub_zones (
  id uuid primary key default gen_random_uuid(),
  name varchar(100) not null,
  zone_id uuid not null references zones(id) on delete cascade,
  complex_id uuid not null references complexes(id) on delete cascade,
  created_at timestamptz not null default now()
);
-- 보관위치 (실질적 최종 위치): 단지 필수, 구역/상세구역 선택, 코드 자동 LOC-000001
create table storage_locations (
  id uuid primary key default gen_random_uuid(),
  code varchar(40) unique not null,
  name varchar(100),
  complex_id  uuid not null references complexes(id) on delete cascade,
  zone_id     uuid references zones(id) on delete set null,
  sub_zone_id uuid references sub_zones(id) on delete set null,
  created_at timestamptz not null default now()
);
create sequence seq_storage_locations;   -- 'LOC-' || lpad(nextval, 6, '0')

-- SKU (재고코드)
create table skus (
  id uuid primary key default gen_random_uuid(),
  code varchar(60) unique not null,            -- P-000001-001 (자동)
  product_id uuid not null references products(id) on delete restrict,
  spec varchar(100),
  color varchar(50),
  release_year varchar(8),
  production_year varchar(8),
  purpose varchar(100),
  image_url text,
  price numeric(14,2) not null default 0,
  qty int not null default 0,
  initial_qty int not null default 0,
  safety_stock int not null default 0,
  total_in int not null default 0,
  total_out int not null default 0,
  status varchar(12) not null default 'in_stock' check (status in ('in_stock','low','out')),
  -- 보관위치 (재고조정에서 지정, 재고실사에서 검증)
  storage_location_id uuid references storage_locations(id) on delete set null,
  zone_id uuid references zones(id) on delete set null,          -- storage_location 에서 복사(비정규화)
  sub_zone_id uuid references sub_zones(id) on delete set null,
  location_verified_at timestamptz,
  location_verified_by varchar(100),
  -- 연한관리(주기 교체)
  lifecycle_enabled boolean not null default false,
  cycle_value int,
  cycle_unit varchar(8),               -- day | month | year
  last_replaced_at timestamptz,
  next_replace_at timestamptz,
  replace_reason varchar(100),
  lifecycle_note text,
  last_replaced_by varchar(100),
  created_at timestamptz not null default now()
);
create index idx_skus_product on skus(product_id);
create index idx_skus_zone on skus(zone_id);
create index idx_skus_next_replace on skus(next_replace_at) where lifecycle_enabled;
-- 위치 라벨(zone > sub_zone)은 JOIN/뷰로 대체 (현재 Firestore는 zoneName/subZoneName/locationLabel 비정규화 저장)

-- 재고 원장
create table stock_movements (
  id uuid primary key default gen_random_uuid(),
  sku_id uuid not null references skus(id) on delete cascade,
  type varchar(10) not null check (type in ('in','out','adjust','audit')),
  qty int not null,
  delta int not null,
  before_qty int not null,
  after_qty int not null,
  reason varchar(40),    -- 재고조정 사유
  memo text,
  by_user_id uuid references users(id),
  by_name varchar(100),
  created_at timestamptz not null default now()
);
create index idx_movements_sku_at on stock_movements(sku_id, created_at desc);
create index idx_movements_at on stock_movements(created_at desc);

-- 연한(주기 교체) 이력
create table lifecycle_logs (
  id uuid primary key default gen_random_uuid(),
  sku_id uuid not null references skus(id) on delete cascade,
  replaced_at timestamptz not null,
  next_replace_at timestamptz,
  reason varchar(100),
  by_user_id uuid references users(id),
  by_name varchar(100),
  created_at timestamptz not null default now()
);
create index idx_lifecycle_logs_sku on lifecycle_logs(sku_id, created_at desc);

-- 일별 집계
create table daily_stats (
  date date primary key,
  in_count int default 0, out_count int default 0,
  adjust_count int default 0, audit_count int default 0,
  in_qty int default 0, out_qty int default 0,
  updated_at timestamptz default now()
);

-- 자동 채번 (시퀀스)
create sequence seq_categories;
create sequence seq_product_codes;
create sequence seq_product_details;
create sequence seq_products;
-- 코드 생성 예:  'CTG-' || lpad(nextval('seq_categories')::text, 6, '0')
-- SKU 코드:      products.code || '-' || lpad((sku_seq)::text, 3, '0')
--   → products 에 sku_seq int default 0 컬럼을 두고 트랜잭션에서 +1
```

> `products` 에 `sku_seq int not null default 0` 컬럼 추가(위 DDL에 누락 없이 반영). SKU 생성 트랜잭션에서 `update products set sku_seq = sku_seq + 1 ... returning sku_seq` 로 순번 확보.

---

## 5. 핵심 트랜잭션 (NestJS 예시 의사코드)

```ts
// 입고/출고/조정/실사 — 재고 UPDATE + 원장 INSERT + 일별집계 upsert 를 한 트랜잭션으로
await dataSource.transaction(async (m) => {
  const sku = await m.findOne(Sku, { where: { id }, lock: { mode: 'pessimistic_write' } }); // SELECT … FOR UPDATE
  const before = sku.qty;
  let after, delta;
  if (type === 'in')   { delta = v;  after = before + v; }
  if (type === 'out')  { if (before < v) throw new BadRequest('재고 부족'); delta = -v; after = before - v; }
  if (type === 'adjust' || type === 'audit') { after = v; delta = after - before; }
  sku.qty = after; sku.status = calcStatus(after, sku.safetyStock);
  if (type==='in')  sku.totalIn  += v;
  if (type==='out') sku.totalOut += v;
  await m.save(sku);
  await m.insert(StockMovement, { skuId:id, type, qty:Math.abs(delta), delta, beforeQty:before, afterQty:after, reason, memo, byUserId, byName });
  await m.query(`insert into daily_stats(date,...) values(current_date,...) on conflict(date) do update set ...`);
});
```

자동채번도 동일 트랜잭션에서 `nextval()` 또는 `update ... returning` 으로 처리 → **동시성·중복·재사용 모두 PG가 보장.**

---

## 6. 권장 전환 순서

1. NestJS 스캐폴딩 + PostgreSQL 스키마(위 DDL) 적용
2. 인증: JWT 로그인/회원가입, 역할(RBAC) Guard
3. 기준정보/상품/SKU CRUD API (+ 자동채번)
4. 재고 트랜잭션 API (입고/출고/조정/실사/배치실사) + 원장 + 일별집계
5. 이미지: S3 presigned URL 업로드 API
6. **프런트 `services/db.js`·`services/storage.js`·`stores/auth.js` 내부를 axios 호출로 교체** (화면 무수정 목표)
7. 데이터 이관: Firestore export(JSON) → 변환 스크립트 → PG `COPY`/insert. 이미지: Storage → S3 복사 후 URL 치환
8. 검증(읽기/쓰기/권한/재고정합성) 후 컷오버

---

## 7. 데이터 이관 메모

- Firestore 문서 id(문자열) → PG에서는 새 uuid 발급 + 매핑테이블로 FK 재연결, 또는 기존 id를 그대로 text PK로 사용
- `createdAt`(Timestamp) → `created_at timestamptz`
- 비정규화 필드(`pathLabel`, `complexName`, `productMainImageUrl`)는 이관 후 **JOIN 뷰로 대체**하거나 유지(선택)
- `counters` 의 현재 seq 값 → PG 시퀀스 `setval()` 로 이어받기 (번호 연속성 유지)

---

*이 문서는 구조가 바뀔 때마다 갱신하세요. 새 컬렉션/필드를 추가하면 4장 DDL에도 반영합니다.*

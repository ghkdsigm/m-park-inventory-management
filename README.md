# 엠파크 WMS (m-park Inventory Management)

엠파크 중고차매매단지 **단지(창고)별 재고관리 시스템(WMS)**.
PC는 백오피스, 모바일은 앱처럼 동작하는 반응형 웹앱입니다.

- **프론트엔드:** Vue 3 + Vite + Tailwind CSS + Pinia + Vue Router
- **백엔드(서버리스):** Firebase (Firestore · Authentication · Hosting)
- **QR:** SKU 생성 시 자동 발급 → 스캔 시 입고/출고/조정/실사 처리

---

## 1. 시스템 구조

```
기준정보관리 (admin)        상품관리 (admin)          재고관리
├ 단지관리                  ├ 상품관리                ├ 입고관리        (전체)
├ 카테고리관리              └ SKU관리 (QR 자동발급)   ├ 출고관리        (전체)
├ 제품코드관리                                        ├ 재고조정        (admin)
└ 제품상세코드관리                                    ├ 재고실사        (admin)
                                                      └ 재고현황        (전체)
설정 (admin) ├ 사용자관리
```

**계층 개념**
- **기준정보(코드)**: 단지 › 카테고리 › 제품코드 › 제품상세코드 (4단계, 각 단계 상위 선택)
  - 예) 서울창고 › 생활용품 › 화장지 › 두루마리 휴지
- **상품**: 실제 관리 대상. 기준정보에 연결 (**단지만 필수**, 나머지 선택)
  - 예) "깨끗한나라 순수 3겹" → 서울창고 / 생활용품 / 화장지 / 두루마리 휴지
- **SKU(재고코드)**: 상품의 실제 재고 단위. **재고수량은 SKU에 보관**
  - 예) TP001-10(10롤), TP001-30(30롤), TP001-50(50롤)
- **재고**: SKU별로 입고·출고·조정·실사 → 모든 변경은 원장(`stockMovements`)에 기록

---

## 2. 빠른 시작 (로컬 개발)

```powershell
npm install
Copy-Item .env.example .env   # → Firebase 설정값 6개 입력 (아래 3번)
npm run dev                   # http://localhost:5175
```

> `.env` 의 Firebase 값이 없으면 로그인/데이터가 동작하지 않습니다. 3번을 먼저 완료하세요.

---

## 3. Firebase 설정 (최초 1회)

1. [Firebase 콘솔](https://console.firebase.google.com) → 프로젝트 추가
2. **Authentication → 로그인 방법 → 이메일/비밀번호 사용 설정**
3. **Firestore Database → 데이터베이스 만들기** (리전: `asia-northeast3` 서울 권장)
4. **프로젝트 설정 → 내 앱(웹) → SDK 구성값** 을 `.env`(또는 `src/firebase.js`)에 입력
5. **Storage 사용 설정**: 콘솔 → Build → **Storage → 시작하기** (이미지 업로드용). 규칙은 `storage.rules` 배포.
6. **보안 규칙 배포**: 콘솔 Firestore → 규칙 탭에 `firestore.rules` 내용 붙여넣기 → 게시
   (또는 `firebase deploy --only firestore:rules,storage`)

### ⚠️ permission denied 가 뜬다면
거의 항상 **보안 규칙 미배포**가 원인입니다. 위 5번을 먼저 하세요.

### 최초 관리자 지정
회원가입 시 기본 권한은 `user`. 첫 관리자는 콘솔에서 승격합니다.
1. 앱에서 회원가입 → 로그인
2. 콘솔 → Firestore → `users` 컬렉션 → **본인 문서**의 `role` 값을 `user` → **`admin`** 으로 수정
3. 앱 새로고침. 이후엔 앱의 **사용자관리** 화면에서 다른 사용자를 승격할 수 있습니다.

---

## 4. 데이터 모델 (Firestore)

```
complexes/{id}       단지(창고)       { code, name, description }
categories/{id}      카테고리         { code, name, complexId, complexName, pathLabel }
productCodes/{id}    제품코드         { code, name, categoryId, complexId, ..., pathLabel }
productDetails/{id}  제품상세코드     { code, name, productCodeId, ..., pathLabel }

products/{id}        상품             { name, complexId(필수), categoryId?, productCodeId?,
                                        productDetailId?, pathLabel, maker, barcode, note }

skus/{id}            SKU(재고코드)    { code, productId, productName, spec, price,
                                        qty, safetyStock, totalIn, totalOut, status,
                                        qrGenerated:true, complexId, pathLabel, ... }

stockMovements/{id}  재고원장         { skuId, skuCode, type:'in'|'out'|'adjust'|'audit',
                                        qty, delta, before, after, byName, memo, at }

users/{uid}          사용자/권한      { email, displayName, role:'admin'|'user' }
```

SKU `status`: `in_stock`(정상) · `low`(안전재고 이하) · `out`(품절)
재고 변경은 항상 트랜잭션으로 `skus.qty` 갱신 + `stockMovements` 기록.

---

## 5. QR 동작

- **SKU 생성 시 QR 자동 발급**(`qrGenerated: true`).
- QR 값은 `https://<배포도메인>/s/<SKU코드>` 형태(핵심값은 SKU 코드).
  → 휴대폰 기본 카메라로 스캔해도 바로 페이지가 열립니다.
- 스캔 → SKU 조회 → **입고 / 출고**(전체 사용자), **재고조정 / 실사**(관리자) 처리.
- **SKU관리**에서 여러 SKU 체크 후 **QR 출력** → 잘라서 상품/위치에 부착.

> ⚠️ QR은 현재 접속 도메인 기준으로 생성됩니다. **운영 배포 도메인에서 출력**해야 현장 스캔이 정상 동작합니다.

---

## 6. 권한 체계

| 기능 | 관리자(admin) | 일반(user) |
|---|:---:|:---:|
| 기준정보/상품/SKU 관리 | ✅ | ❌ (조회만) |
| 입고 / 출고 | ✅ | ✅ |
| 재고조정 / 재고실사 | ✅ | ❌ |
| 재고현황 조회 | ✅ | ✅ |
| 사용자 권한 변경 | ✅ | ❌ |

보안 규칙(`firestore.rules`)으로 서버에서 강제됩니다. (일반 사용자는 SKU의 재고 관련 필드만 수정 가능)

---

## 7. 배포 (Firebase Hosting)

```powershell
npm install -g firebase-tools
firebase login
firebase use --add
npm run deploy            # vite build && firebase deploy
```

배포 후 `https://<프로젝트id>.web.app` 가 운영 URL이며 QR도 이 주소 기준으로 생성됩니다.

---

## 8. 비용 (제품 1만 / 사용자 1천 기준)

- 저장·인증: 무료 한도 내 (여유 충분)
- Firestore 읽기/쓰기: 무료 Spark = 일 5만 읽기 / 2만 쓰기. 일일 활성 사용량이 적으면 무료 내 동작
- 초과 시 Blaze(종량제)도 매우 저렴 (읽기 10만건 ≈ $0.06) → 보통 월 1만원 미만

> 참고: 현재 목록 조회는 단순화를 위해 컬렉션 전체를 불러와 클라이언트에서 필터/정렬합니다.
> SKU/상품이 수천 건을 넘어가면 페이지네이션·서버측 쿼리(복합 인덱스) 도입을 권장합니다.

---

## 9. 코드 자동생성 규칙

등록 시 코드가 **자동 생성**됩니다. (단지는 직접 입력)

| 구분 | 형식 | 예 |
|---|---|---|
| 단지코드 | 직접 입력 | DANJI-000001 |
| 카테고리코드 | `CTG-000000` | CTG-000001 |
| 제품코드 | `PC-000000` | PC-000001 |
| 제품상세코드 | `PCD-000000` | PCD-000001 |
| 상품코드 | `P-000000` | P-000001 |
| SKU코드 | `상품코드-000` | P-000001-001, P-000001-002 |

- `counters/{type}` 문서의 시퀀스를 **Firestore 트랜잭션**으로 증가 → 동시 등록·중복·삭제 후 재사용 모두 방지(번호는 단조 증가, 빈 번호 재사용 안 함)
- 코드는 **사용자가 수정 불가**(읽기전용), 코드 관리 화면은 **관리자만** 접근
- SKU는 상품의 `skuSeq`를 트랜잭션으로 증가시켜 상품별 순번 보장 → 생성 즉시 QR 자동발급

## 10. 로그(원장) 보관 정책

- **재고원장(`stockMovements`)**: 입고/출고/조정/실사 전 이력. 화면 조회는 **최근 100건** limit.
- **일별 집계(`dailyStats/{YYYY-MM-DD}`)**: 입출고 시 트랜잭션으로 함께 갱신. **대시보드는 원장 전체를 스캔하지 않고 이 집계 문서만** 읽음.
- **백업/보관**: 입출고 조회 화면의 **CSV 내보내기**로 월별 백업 → 별도 보관 권장. 최근 1~2년치만 Firestore에 두고 오래된 로그는 백업 후 정리(수동/배치)하면 비용·성능에 유리.

> 향후 대량(수만 건↑) 운영 시: 원장 조회 페이지네이션, 오래된 로그 정리 스케줄(Cloud Functions/수동 export), SKU 합계도 집계문서화 권장.

## 11. 이미지 (대표/옵션 이미지)

- **상품**: 대표 이미지(`mainImageUrl`) + 추가 이미지(`images[]`). **SKU**: 옵션 이미지(`imageUrl`, 선택).
- 업로드는 **Firebase Storage**(클라이언트에서 자동 리사이즈·압축 후 업로드). 관리자만 업로드, 5MB·이미지 타입 제한(`storage.rules`).
- **표시 우선순위(공통 유틸 `src/utils/image.js`의 `resolveImage`)**: ① SKU 이미지 → ② 상품 대표 이미지 → ③ 기본(no-image). 화면마다 중복 구현하지 않습니다.
- 목록/현황 화면은 빠른 표시를 위해 SKU에 비정규화된 `productMainImageUrl`을 폴백으로 사용, QR 스캔 화면은 상품을 조회해 **최신 대표이미지**를 폴백으로 사용합니다.
- 모든 화면(QR 조회·재고현황·입고/출고·재고조정/실사·SKU/상품 목록)에서 항상 이미지가 표시됩니다. SKU 이미지가 없어도 정상 동작.

> 참고: 상품 대표이미지를 나중에 바꾸면 이미 생성된 SKU의 비정규화 폴백(`productMainImageUrl`)은 갱신되지 않습니다(목록 한정). QR 스캔 화면은 항상 최신값을 보여줍니다.

## 12. 향후 마이그레이션 (Firebase → EC2 + PostgreSQL + NestJS)

전환을 쉽게 하려고 **Firebase 의존성을 3개 파일에 격리**했습니다(`services/db.js`, `services/storage.js`, `stores/auth.js`). 마이그레이션 시 이 파일들의 내부만 REST로 교체하면 화면은 거의 그대로 유지됩니다.

➡️ **상세 계획·PostgreSQL DDL·코딩 규칙은 [`MIGRATION.md`](./MIGRATION.md) 참고.**
앞으로 기능을 추가할 때도 `MIGRATION.md`의 "코딩 규칙"(컴포넌트에서 firebase 직접 import 금지, 비즈니스 로직은 서비스 계층 등)을 지켜주세요.

## 스크립트

| 명령 | 설명 |
|---|---|
| `npm run dev` | 개발 서버 (LAN 노출, 모바일 테스트) |
| `npm run build` | 프로덕션 빌드 → `dist/` |
| `npm run preview` | 빌드 결과 미리보기 |
| `npm run deploy` | 빌드 + Firebase 배포 |

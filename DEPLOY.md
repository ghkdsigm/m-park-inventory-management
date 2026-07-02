# 배포 가이드 — Railway(백엔드 + MySQL) + Netlify(프론트)

임시 PoC/공유용. 백엔드(Spring, Docker)와 MySQL 은 Railway 에, 프론트(Vue/Vite)는 Netlify 에 올린다.
설정은 전부 환경변수로 주입하므로 코드 수정 없이 배포된다.

```
 [사용자] ── https ──> Netlify (프론트 정적 SPA)
                         │  VITE_API_BASE = https://<백엔드>/api
                         ▼
                      Railway 백엔드(Spring)  ── 내부망 ──>  Railway MySQL
```

---

## A. Railway — 백엔드 + MySQL

### 1) 프로젝트 생성 + MySQL 추가
1. https://railway.app 로그인 → **New Project**
2. **Add → Database → MySQL** 선택 (서비스 이름은 기본 `MySQL` 로 둔다 — 아래 변수 참조가 이 이름을 씀)

### 2) 백엔드 서비스 추가
1. 같은 프로젝트에서 **Add → GitHub Repo** → 이 저장소 선택
2. 서비스 **Settings → Root Directory** 를 **`backend`** 로 지정
   (저장소 루트는 프론트라, 백엔드는 `backend/` 하위임. `backend/railway.json` 이 Dockerfile 빌드를 지정함)
3. **Settings → Networking → Generate Domain** 눌러 공개 도메인 발급
   → 예: `https://mpark-wms-production.up.railway.app`  (이하 `<백엔드도메인>`)

### 3) 백엔드 환경변수 (Variables 탭)
아래를 그대로 추가. `${{MySQL.*}}` 는 Railway 가 MySQL 서비스 값을 자동 주입하는 참조 문법이다.

```
SPRING_DATASOURCE_URL = jdbc:mysql://${{MySQL.MYSQLHOST}}:${{MySQL.MYSQLPORT}}/${{MySQL.MYSQLDATABASE}}?serverTimezone=Asia/Seoul&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true
DB_USER               = ${{MySQL.MYSQLUSER}}
DB_PASSWORD           = ${{MySQL.MYSQLPASSWORD}}
JWT_SECRET            = <32바이트 이상 랜덤 문자열>
CORS_ORIGINS          = https://<네틀리파이도메인>          ← B 단계 끝나고 채움(일단 비워두고 나중에)
STORAGE_DIR           = /app/uploads
STORAGE_PUBLIC_BASE_URL = https://<백엔드도메인>/files
```

> JWT_SECRET 예시 생성: `openssl rand -base64 48`

### 4) 업로드 이미지 보존용 볼륨 (선택, 권장)
컨테이너 파일시스템은 재배포 때 초기화되어 업로드한 상품/SKU 이미지가 사라진다.
- 백엔드 서비스 → **Settings → Volumes → Add Volume** → Mount path **`/app/uploads`**
- (안 붙이면 재배포마다 이미지 유실 — PoC 로 괜찮으면 생략 가능)

### 5) 배포 확인
- 배포 로그에 Flyway 가 `V1~V4` 마이그레이션 적용 → 스키마 자동 생성됨
- `https://<백엔드도메인>/api/...` 접근 가능 상태가 되면 OK

---

## B. Netlify — 프론트

1. https://netlify.com 로그인 → **Add new site → Import from Git** → 이 저장소 선택
2. 빌드 설정은 루트 `netlify.toml` 이 자동 적용 (command/publish/SPA 리다이렉트 포함) — 그대로 두면 됨
3. **Site settings → Environment variables** 에 추가:
   ```
   VITE_API_BASE = https://<백엔드도메인>/api
   ```
   (⚠️ Vite 는 빌드 시점에 값이 박히므로, 이 값 바꾸면 **재배포 필요**)
4. **Deploy** → 발급된 주소가 `<네틀리파이도메인>` (예: `https://mpark-wms.netlify.app`)

---

## C. 연결 마무리 (CORS)

1. Railway 백엔드 `CORS_ORIGINS` 를 **`https://<네틀리파이도메인>`** 로 설정 → 백엔드 자동 재배포
2. Netlify `VITE_API_BASE` 가 **`https://<백엔드도메인>/api`** 인지 확인 → 프론트 재배포(Trigger deploy)
3. 끝. 네틀리파이 주소로 접속해서 로그인/조회 확인

---

## 환경변수 요약

| 위치 | 키 | 값 |
|------|-----|-----|
| Railway(백엔드) | SPRING_DATASOURCE_URL | `jdbc:mysql://${{MySQL.MYSQLHOST}}:${{MySQL.MYSQLPORT}}/${{MySQL.MYSQLDATABASE}}?serverTimezone=Asia/Seoul&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true` |
| Railway(백엔드) | DB_USER | `${{MySQL.MYSQLUSER}}` |
| Railway(백엔드) | DB_PASSWORD | `${{MySQL.MYSQLPASSWORD}}` |
| Railway(백엔드) | JWT_SECRET | 32바이트+ 랜덤 |
| Railway(백엔드) | CORS_ORIGINS | `https://<네틀리파이도메인>` |
| Railway(백엔드) | STORAGE_DIR | `/app/uploads` |
| Railway(백엔드) | STORAGE_PUBLIC_BASE_URL | `https://<백엔드도메인>/files` |
| Netlify(프론트) | VITE_API_BASE | `https://<백엔드도메인>/api` |

---

## 참고 / 주의

- **첫 관리자 계정**: 배포 후 회원가입(`/api/auth/register`)으로 사용자를 만들고, 권한(role/canStock)은 DB 또는 사용자관리 화면에서 부여해야 할 수 있음. (초기 관리자 지정 방식은 기존 로직 확인)
- **콜드 스타트**: 트래픽 없을 때 백엔드가 잠들면 첫 요청이 느릴 수 있음(Spring 기동 ~10-30초). PoC 엔 무방.
- **비용**: Railway 는 무료 크레딧 소진 후 사용량 과금(작은 백엔드+MySQL 이면 소액). 완전 $0 아님.
- **모바일 QR**: QR 이 인코딩하는 접속 URL 이 배포 도메인을 가리키는지 확인(생성 시점 origin 기준). 로컬에서 만든 QR 은 localhost 를 가리킬 수 있음.
- **한글**: Railway MySQL 8 기본 `utf8mb4` + 마이그레이션이 테이블별 `utf8mb4` 지정 → 한글 정상.
- **나중에 AWS 이전 시**: 백엔드는 같은 Docker 이미지 그대로 ECS/EB 등에 올리고, DB 는 RDS(MySQL), 이미지 스토리지는 S3 로 교체(현재는 로컬 디스크 `STORAGE_DIR`). S3 전환 시 스토리지 어댑터만 바꾸면 됨.

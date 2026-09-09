# 배포 가이드 — Railway(백엔드 + MySQL) + Netlify(프론트)

백엔드(Spring Boot, Docker)와 MySQL 은 **Railway**, 프론트(Vue/Vite)는 **Netlify** 에 올린다.
설정은 전부 환경변수로 주입하므로 코드 수정 없이 배포된다.

```
 [사용자] ── https ──> Netlify (프론트 정적 SPA)
                         │  VITE_API_BASE = https://<백엔드도메인>/api
                         ▼
                      Railway 백엔드(Spring Boot)  ── 내부망 ──>  Railway MySQL
```

- **백엔드 배포 브랜치:** `feature/porting01` — 이 브랜치에 push 하면 Railway 가 자동 빌드/배포.
- **빌드 방식:** `backend/Dockerfile`(멀티스테이지 gradle) — `backend/railway.json` 이 지정. 호스트에 Java 불필요.
- **스키마:** 기동 시 Flyway 가 `V1~V22` 를 적용해 자동 생성/이관.

---

## A. Railway — 백엔드 + MySQL

### 1) 프로젝트 생성 + MySQL 추가
1. https://railway.app 로그인 → **New Project**
2. **Add → Database → MySQL** (서비스 이름은 기본 `MySQL` — 아래 변수 참조가 이 이름을 씀). 기본 DB 이름은 보통 `railway`.

### 2) 백엔드 서비스 추가
1. 같은 프로젝트에서 **Add → GitHub Repo** → 이 저장소 선택
2. 서비스 **Settings → Root Directory** 를 **`backend`** 로 지정 (저장소 루트는 프론트라 백엔드는 `backend/` 하위)
3. **Settings → Deploy → Branch** 를 배포 브랜치(**`feature/porting01`**)로 지정
4. **Settings → Networking → Generate Domain** → 공개 도메인 발급 (예: `https://mpark-wms-production.up.railway.app` = `<백엔드도메인>`)

### 3) 백엔드 환경변수 (Variables 탭)
`${{MySQL.*}}` 는 Railway 가 MySQL 서비스 값을 자동 주입하는 참조 문법이다.

```
SPRING_DATASOURCE_URL = jdbc:mysql://${{MySQL.MYSQLHOST}}:${{MySQL.MYSQLPORT}}/${{MySQL.MYSQLDATABASE}}?serverTimezone=Asia/Seoul&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true
DB_USER               = ${{MySQL.MYSQLUSER}}
DB_PASSWORD           = ${{MySQL.MYSQLPASSWORD}}
JWT_SECRET            = <32바이트 이상 랜덤 문자열>       # openssl rand -base64 48
CORS_ORIGINS          = https://<네틀리파이도메인>       # B 단계 후 채움
STORAGE_DIR           = /app/uploads
STORAGE_PUBLIC_BASE_URL = https://<백엔드도메인>/files
OPENAI_API_KEY        = sk-...                          # 챗봇/사진검색/견적추출용 (없으면 AI 기능만 비활성)
# 선택
# OPENAI_MODEL=gpt-4o-mini  OPENAI_VISION_MODEL=gpt-4o  OPENAI_EXTRACT_MODEL=gpt-4o-mini
# MINIMAX_API_KEY=... MINIMAX_GROUP_ID=...   (챗봇 음성 답변 TTS. 없으면 브라우저 기본 음성으로 폴백)
```

> `JWT_SECRET` 이 없으면 백엔드가 기동되지 않는다(필수).

### 4) 업로드 이미지 보존용 볼륨 (권장)
컨테이너 파일시스템은 재배포 때 초기화되어 업로드 이미지가 사라진다.
- 백엔드 서비스 → **Settings → Volumes → Add Volume** → Mount path **`/app/uploads`**

### 5) 배포 확인
- 배포 로그에 Flyway 가 **`V1~V22` 마이그레이션 적용** → 스키마 생성 확인
- 기동 로그에 `Tomcat started on port 8080` / `AdminBootstrap` 이 `admin` 계정 승격 확인
- `https://<백엔드도메인>/api/auth/login` 응답 확인

---

## B. Netlify — 프론트

1. https://netlify.com 로그인 → **Add new site → Import from Git** → 이 저장소 선택 (배포 브랜치 `feature/porting01`)
2. 빌드 설정은 루트 `netlify.toml` 이 자동 적용 (command/publish/SPA 리다이렉트 포함)
3. **Site settings → Environment variables**:
   ```
   VITE_API_BASE = https://<백엔드도메인>/api
   ```
   (⚠️ Vite 는 빌드 시점에 값이 박히므로 바꾸면 **재배포 필요**)
4. **Deploy** → 발급된 주소가 `<네틀리파이도메인>` (예: `https://mpark-wms.netlify.app`)

---

## C. 연결 마무리 (CORS)

1. Railway 백엔드 `CORS_ORIGINS` = **`https://<네틀리파이도메인>`** → 백엔드 자동 재배포
2. Netlify `VITE_API_BASE` = **`https://<백엔드도메인>/api`** 확인 → 프론트 재배포(Trigger deploy)
3. 네틀리파이 주소로 접속 → **아이디 `admin` / 비번 `123123`** 으로 로그인 확인

---

## 환경변수 요약

| 위치 | 키 | 값 |
|------|-----|-----|
| Railway(백엔드) | SPRING_DATASOURCE_URL | `jdbc:mysql://${{MySQL.MYSQLHOST}}:${{MySQL.MYSQLPORT}}/${{MySQL.MYSQLDATABASE}}?serverTimezone=Asia/Seoul&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true` |
| Railway(백엔드) | DB_USER / DB_PASSWORD | `${{MySQL.MYSQLUSER}}` / `${{MySQL.MYSQLPASSWORD}}` |
| Railway(백엔드) | JWT_SECRET | 32바이트+ 랜덤 (필수) |
| Railway(백엔드) | CORS_ORIGINS | `https://<네틀리파이도메인>` |
| Railway(백엔드) | STORAGE_DIR / STORAGE_PUBLIC_BASE_URL | `/app/uploads` / `https://<백엔드도메인>/files` |
| Railway(백엔드) | OPENAI_API_KEY | `sk-...` (AI 기능용, 선택) |
| Railway(백엔드) | MINIMAX_API_KEY / MINIMAX_GROUP_ID | TTS 음성 답변용 (선택) |
| Netlify(프론트) | VITE_API_BASE | `https://<백엔드도메인>/api` |

---

## 참고 / 주의

- **첫 관리자**: 공개 회원가입은 없다. 기동 시 `AdminBootstrap` 이 아이디 `admin`(비번 `123123`, super)을 보장한다. 이후 다른 계정은 **백오피스 사용자관리**(super 전용)에서 생성한다.
- **콜드 스타트**: 트래픽 없을 때 첫 요청이 느릴 수 있음(Spring 기동 ~10–30초).
- **비용**: Railway 무료 크레딧 소진 후 사용량 과금(작은 백엔드+MySQL 이면 소액). 완전 $0 아님.
- **모바일 QR**: QR 은 생성 시점 origin 기준으로 `/{origin}/s/{SKU코드}` URL 을 인코딩. **운영 도메인에서 생성/재발급**해야 스캔이 운영을 가리킴(로컬에서 만든 QR 은 localhost 를 가리킴). 코드 체계 변경 시 기존 라벨은 재발급 필요.
- **한글**: MySQL 8 `utf8mb4` + 마이그레이션 테이블별 `utf8mb4` → 한글 정상.
- **AWS 이전 시**: 백엔드는 같은 Docker 이미지로 ECS/EB 등에 올리고, DB 는 RDS(MySQL), 이미지 스토리지는 S3(현재는 로컬 디스크 `STORAGE_DIR`)로 교체. `SPRING_DATASOURCE_URL`(또는 `DB_URL`)만 바꾸면 DB 전환 가능하도록 설정을 환경변수화해 둠.

---

## D. (선택) 로컬 데이터 → Railway MySQL 이관

로컬에 쌓아둔 데이터(상품/SKU/재고 등)를 옮길 때만. 새로 입력할 거면 생략.

> ⚠️ **A(백엔드 배포)가 끝나 Flyway 가 테이블을 만든 뒤** 실행한다(여기선 **데이터만** 넣는다).
> ⚠️ Railway DB 는 **비어 있어야** 안전(회원/계정 데이터가 이미 있으면 PK/유니크 충돌 가능).

### 1) 로컬 데이터 덤프 (데이터만, flyway 이력 제외)
로컬 MySQL 은 도커 컨테이너 `mpark-mysql`. FK 순서 문제 회피용으로 맨 앞에 `SET FOREIGN_KEY_CHECKS=0`.
```
docker exec mpark-mysql sh -c "(echo 'SET FOREIGN_KEY_CHECKS=0;'; mysqldump -uroot -proot --no-create-info --single-transaction --set-gtid-purged=OFF --ignore-table=mpark_wms.flyway_schema_history mpark_wms) > /tmp/mpark_data.sql"
docker cp mpark-mysql:/tmp/mpark_data.sql ./mpark_data.sql
```

### 2) Railway 외부 접속 정보
Railway → **MySQL → Connect/Variables** 에서 퍼블릭(프록시) Host/Port/User/Password/Database(보통 `railway`) 확인.

### 3) import (도커로)
```
docker run --rm -i mysql:8.0 mysql -h <HOST> -P <PORT> -u <USER> -p"<PASSWORD>" <RW_DB> < mpark_data.sql
```

### 4) 이미지 파일은 별도
업로드 이미지는 DB 가 아니라 서버 디스크(`STORAGE_DIR`)에 있다. DB 의 `image_url` 은 넘어가도 실제 파일이 없으면 이미지가 깨지므로, 로컬 업로드 폴더(도커 볼륨 `backend_uploads`)를 Railway 볼륨(`/app/uploads`)에 복사하거나 재업로드한다.

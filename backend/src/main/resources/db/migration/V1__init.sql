-- 엠파크 WMS — MySQL 8 스키마 (Supabase/PostgreSQL 스키마 포팅)
-- 원본: supabase/migrations/2026061609000{1..11}.sql 을 통합 변환.
-- 차이점 요약:
--   uuid            → VARCHAR(36)  (앱에서 UUID 생성)
--   timestamptz     → DATETIME(6)
--   jsonb           → JSON
--   text            → VARCHAR/적정길이
--   boolean         → TINYINT(1)
--   시퀀스          → seq_counters 테이블 + 앱 로직
--   updated_at 트리거 → ON UPDATE CURRENT_TIMESTAMP
--   RPC/RLS/감사트리거 → Spring 서비스 계층 (Phase 2~5)
--   auth.users      → profiles 에 password_hash 추가 (자체 인증)

SET NAMES utf8mb4;

/* ========================= 프로필(사용자/인증) ========================= */
CREATE TABLE profiles (
  id            VARCHAR(36)     NOT NULL PRIMARY KEY,
  email         VARCHAR(255) UNIQUE,
  password_hash VARCHAR(255),
  display_name  VARCHAR(255),
  role          VARCHAR(20)  NOT NULL DEFAULT 'user',
  can_stock     TINYINT(1)   NOT NULL DEFAULT 0,
  created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT chk_profiles_role CHECK (role IN ('admin','user'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

/* ========================= 자동코드용 시퀀스 카운터 ========================= */
CREATE TABLE seq_counters (
  name VARCHAR(50) NOT NULL PRIMARY KEY,
  val  BIGINT      NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO seq_counters(name, val) VALUES
  ('categories', 0), ('product_codes', 0), ('product_details', 0),
  ('storage_locations', 0), ('products', 0);

/* ========================= 기준정보 (4단계) ========================= */
CREATE TABLE complexes (
  id          VARCHAR(36)      NOT NULL PRIMARY KEY,
  code        VARCHAR(50)   NOT NULL UNIQUE,
  name        VARCHAR(255)  NOT NULL,
  description VARCHAR(1000) DEFAULT '',
  created_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at  DATETIME(6)   NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE categories (
  id           VARCHAR(36)      NOT NULL PRIMARY KEY,
  code         VARCHAR(50)   NOT NULL UNIQUE,
  name         VARCHAR(255)  NOT NULL,
  description  VARCHAR(1000) DEFAULT '',
  path_label   VARCHAR(500)  DEFAULT '',
  created_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at   DATETIME(6)   NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_codes (
  id            VARCHAR(36)      NOT NULL PRIMARY KEY,
  code          VARCHAR(50)   NOT NULL UNIQUE,
  name          VARCHAR(255)  NOT NULL,
  description   VARCHAR(1000) DEFAULT '',
  category_id   VARCHAR(36)      NOT NULL,
  category_name VARCHAR(255)  DEFAULT '',
  path_label    VARCHAR(500)  DEFAULT '',
  created_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at    DATETIME(6)   NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_pc_category FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_details (
  id                VARCHAR(36)      NOT NULL PRIMARY KEY,
  code              VARCHAR(50)   NOT NULL UNIQUE,
  name              VARCHAR(255)  NOT NULL,
  description       VARCHAR(1000) DEFAULT '',
  product_code_id   VARCHAR(36)      NOT NULL,
  product_code_name VARCHAR(255)  DEFAULT '',
  category_id       VARCHAR(36)      NULL,
  category_name     VARCHAR(255)  DEFAULT '',
  path_label        VARCHAR(500)  DEFAULT '',
  created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at        DATETIME(6)   NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_pd_pc       FOREIGN KEY (product_code_id) REFERENCES product_codes(id) ON DELETE CASCADE,
  CONSTRAINT fk_pd_category FOREIGN KEY (category_id)     REFERENCES categories(id)    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

/* ========================= 위치 ========================= */
CREATE TABLE zones (
  id           VARCHAR(36)     NOT NULL PRIMARY KEY,
  name         VARCHAR(255) NOT NULL,
  complex_id   VARCHAR(36)     NOT NULL,
  complex_name VARCHAR(255) DEFAULT '',
  created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at   DATETIME(6)  NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_zone_complex FOREIGN KEY (complex_id) REFERENCES complexes(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sub_zones (
  id           VARCHAR(36)     NOT NULL PRIMARY KEY,
  name         VARCHAR(255) NOT NULL,
  zone_id      VARCHAR(36)     NOT NULL,
  zone_name    VARCHAR(255) DEFAULT '',
  complex_id   VARCHAR(36)     NULL,
  complex_name VARCHAR(255) DEFAULT '',
  created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at   DATETIME(6)  NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_sub_zone    FOREIGN KEY (zone_id)    REFERENCES zones(id)     ON DELETE CASCADE,
  CONSTRAINT fk_sub_complex FOREIGN KEY (complex_id) REFERENCES complexes(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE storage_locations (
  id             VARCHAR(36)     NOT NULL PRIMARY KEY,
  code           VARCHAR(50)  NOT NULL UNIQUE,
  name           VARCHAR(255) DEFAULT '',
  complex_id     VARCHAR(36)     NOT NULL,
  complex_name   VARCHAR(255) DEFAULT '',
  zone_id        VARCHAR(36)     NULL,
  zone_name      VARCHAR(255) DEFAULT '',
  sub_zone_id    VARCHAR(36)     NULL,
  sub_zone_name  VARCHAR(255) DEFAULT '',
  location_label VARCHAR(500) DEFAULT '',
  created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at     DATETIME(6)  NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_loc_complex  FOREIGN KEY (complex_id)  REFERENCES complexes(id)  ON DELETE CASCADE,
  CONSTRAINT fk_loc_zone     FOREIGN KEY (zone_id)     REFERENCES zones(id)      ON DELETE SET NULL,
  CONSTRAINT fk_loc_sub_zone FOREIGN KEY (sub_zone_id) REFERENCES sub_zones(id)  ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

/* ========================= 상품 ========================= */
CREATE TABLE products (
  id                 VARCHAR(36)      NOT NULL PRIMARY KEY,
  code               VARCHAR(50)   NOT NULL UNIQUE,
  name               VARCHAR(255)  NOT NULL,
  maker              VARCHAR(255)  DEFAULT '',
  barcode            VARCHAR(255)  DEFAULT '',
  note               VARCHAR(1000) DEFAULT '',
  main_image_url     VARCHAR(1000) DEFAULT '',
  images             JSON,
  price              DECIMAL(14,2) NOT NULL DEFAULT 0,
  category_id        VARCHAR(36)      NULL,
  category_name      VARCHAR(255)  DEFAULT '',
  product_code_id    VARCHAR(36)      NULL,
  product_code_name  VARCHAR(255)  DEFAULT '',
  product_detail_id  VARCHAR(36)      NULL,
  product_detail_name VARCHAR(255) DEFAULT '',
  path_label         VARCHAR(500)  DEFAULT '',
  sku_seq            INT           NOT NULL DEFAULT 0,
  created_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at         DATETIME(6)   NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_prod_cat     FOREIGN KEY (category_id)       REFERENCES categories(id)      ON DELETE SET NULL,
  CONSTRAINT fk_prod_pc      FOREIGN KEY (product_code_id)   REFERENCES product_codes(id)   ON DELETE SET NULL,
  CONSTRAINT fk_prod_pd      FOREIGN KEY (product_detail_id) REFERENCES product_details(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

/* ========================= SKU ========================= */
CREATE TABLE skus (
  id                     VARCHAR(36)   NOT NULL PRIMARY KEY,
  code                   VARCHAR(60)   NOT NULL UNIQUE,
  product_id             VARCHAR(36)   NOT NULL,
  product_name           VARCHAR(255)  DEFAULT '',
  spec                   VARCHAR(255)  DEFAULT '',
  color                  VARCHAR(100)  DEFAULT '',
  release_year           VARCHAR(20)   DEFAULT '',
  production_year        VARCHAR(20)   DEFAULT '',
  purpose                VARCHAR(255)  DEFAULT '',
  image_url              VARCHAR(1000) DEFAULT '',
  product_main_image_url VARCHAR(1000) DEFAULT '',
  price                  DECIMAL(14,2) NOT NULL DEFAULT 0,
  safety_stock           INT           NOT NULL DEFAULT 0,
  qr_generated           TINYINT(1)    NOT NULL DEFAULT 1,
  category_id            VARCHAR(36)   NULL,
  product_code_id        VARCHAR(36)   NULL,
  product_detail_id      VARCHAR(36)   NULL,
  path_label             VARCHAR(500)  DEFAULT '',
  -- 연한관리 설정(변형 단위)
  lifecycle_enabled      TINYINT(1)    NOT NULL DEFAULT 0,
  cycle_value            INT           DEFAULT 0,
  cycle_unit             VARCHAR(10)   DEFAULT 'month',
  replace_reason         VARCHAR(500)  DEFAULT '',
  lifecycle_note         VARCHAR(1000) DEFAULT '',
  -- 치수(cm)
  dim_w DECIMAL(10,2) NULL, dim_l DECIMAL(10,2) NULL, dim_h DECIMAL(10,2) NULL, dim_d DECIMAL(10,2) NULL,
  created_at             DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at             DATETIME(6)   NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_sku_product FOREIGN KEY (product_id)        REFERENCES products(id)        ON DELETE RESTRICT,
  CONSTRAINT fk_sku_cat     FOREIGN KEY (category_id)       REFERENCES categories(id)      ON DELETE SET NULL,
  CONSTRAINT fk_sku_pc      FOREIGN KEY (product_code_id)   REFERENCES product_codes(id)   ON DELETE SET NULL,
  CONSTRAINT fk_sku_pd      FOREIGN KEY (product_detail_id) REFERENCES product_details(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_skus_product ON skus(product_id);
CREATE INDEX idx_skus_category ON skus(category_id);

/* ========================= 재고행 (SKU x 보관위치) ========================= */
CREATE TABLE stock (
  id                    VARCHAR(36)   NOT NULL PRIMARY KEY,
  sku_id                VARCHAR(36)   NOT NULL,
  complex_id            VARCHAR(36)   NOT NULL,
  complex_name          VARCHAR(255)  DEFAULT '',
  storage_location_id   VARCHAR(36)   NOT NULL,
  storage_location_code VARCHAR(50)   DEFAULT '',
  zone_id               VARCHAR(36)   NULL,
  zone_name             VARCHAR(255)  DEFAULT '',
  sub_zone_id           VARCHAR(36)   NULL,
  sub_zone_name         VARCHAR(255)  DEFAULT '',
  location_label        VARCHAR(500)  DEFAULT '',
  qty                   INT           NOT NULL DEFAULT 0,
  initial_qty           INT           NOT NULL DEFAULT 0,
  total_in              INT           NOT NULL DEFAULT 0,
  total_out             INT           NOT NULL DEFAULT 0,
  status                VARCHAR(20)   NOT NULL DEFAULT 'in_stock',
  last_moved_by         VARCHAR(255)  NULL,
  last_moved_at         DATETIME(6)   NULL,
  location_verified_at  DATETIME(6)   NULL,
  location_verified_by  VARCHAR(255)  NULL,
  last_replaced_at      DATETIME(6)   NULL,
  next_replace_at       DATETIME(6)   NULL,
  last_replaced_by      VARCHAR(255)  NULL,
  created_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at            DATETIME(6)   NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_stock_sku     FOREIGN KEY (sku_id)              REFERENCES skus(id)              ON DELETE CASCADE,
  CONSTRAINT fk_stock_complex FOREIGN KEY (complex_id)          REFERENCES complexes(id)         ON DELETE RESTRICT,
  CONSTRAINT fk_stock_loc     FOREIGN KEY (storage_location_id) REFERENCES storage_locations(id) ON DELETE RESTRICT,
  CONSTRAINT fk_stock_zone    FOREIGN KEY (zone_id)             REFERENCES zones(id)             ON DELETE SET NULL,
  CONSTRAINT fk_stock_subz    FOREIGN KEY (sub_zone_id)         REFERENCES sub_zones(id)         ON DELETE SET NULL,
  CONSTRAINT uq_stock_sku_loc UNIQUE (sku_id, storage_location_id),
  CONSTRAINT chk_stock_status CHECK (status IN ('in_stock','low','out'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_stock_sku ON stock(sku_id);
CREATE INDEX idx_stock_complex ON stock(complex_id);
CREATE INDEX idx_stock_location ON stock(storage_location_id);
CREATE INDEX idx_stock_status ON stock(status);
CREATE INDEX idx_stock_next_replace ON stock(next_replace_at);

/* ========================= 원장 / 이력 / 집계 ========================= */
CREATE TABLE stock_movements (
  id           VARCHAR(36)     NOT NULL PRIMARY KEY,
  sku_id       VARCHAR(36)     NOT NULL,
  stock_id     VARCHAR(36)     NULL,
  sku_code     VARCHAR(60),
  product_name VARCHAR(255),
  type         VARCHAR(20)  NOT NULL,
  qty          INT          NOT NULL,
  delta        INT          NOT NULL,
  before_qty   INT          NOT NULL,
  after_qty    INT          NOT NULL,
  complex_id   VARCHAR(36),
  complex_name VARCHAR(255),
  path_label   VARCHAR(500),
  memo         VARCHAR(500) DEFAULT '',
  reason       VARCHAR(500) DEFAULT '',
  by_user_id   VARCHAR(36),
  by_name      VARCHAR(255),
  at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  -- 취소(역분개) 지원
  voided       TINYINT(1)   NOT NULL DEFAULT 0,
  voided_at    DATETIME(6)  NULL,
  voided_by    VARCHAR(255) NULL,
  void_reason  VARCHAR(500) DEFAULT '',
  reversal_of  VARCHAR(36)     NULL,
  transfer_id  VARCHAR(36)     NULL,
  CONSTRAINT fk_mv_sku      FOREIGN KEY (sku_id)      REFERENCES skus(id)             ON DELETE CASCADE,
  CONSTRAINT fk_mv_reversal FOREIGN KEY (reversal_of) REFERENCES stock_movements(id)  ON DELETE SET NULL,
  CONSTRAINT chk_mv_type    CHECK (type IN ('in','out','adjust','audit','void'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_movements_sku_at ON stock_movements(sku_id, at);
CREATE INDEX idx_movements_at     ON stock_movements(at);

CREATE TABLE lifecycle_logs (
  id              VARCHAR(36)     NOT NULL PRIMARY KEY,
  sku_id          VARCHAR(36)     NOT NULL,
  sku_code        VARCHAR(60),
  product_name    VARCHAR(255),
  replaced_at     DATETIME(6)  NOT NULL,
  next_replace_at DATETIME(6),
  reason          VARCHAR(500) DEFAULT '',
  path_label      VARCHAR(500),
  complex_name    VARCHAR(255),
  by_user_id      VARCHAR(36),
  by_name         VARCHAR(255),
  at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_lc_sku FOREIGN KEY (sku_id) REFERENCES skus(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_lifecycle_logs_sku_at ON lifecycle_logs(sku_id, at);

CREATE TABLE location_logs (
  id                    VARCHAR(36)     NOT NULL PRIMARY KEY,
  sku_id                VARCHAR(36)     NOT NULL,
  sku_code              VARCHAR(60),
  product_name          VARCHAR(255),
  from_label            VARCHAR(500),
  to_label              VARCHAR(500),
  storage_location_id   VARCHAR(36),
  storage_location_code VARCHAR(50),
  location_label        VARCHAR(500),
  complex_name          VARCHAR(255),
  by_user_id            VARCHAR(36),
  by_name               VARCHAR(255),
  at                    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_ll_sku FOREIGN KEY (sku_id) REFERENCES skus(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_location_logs_sku_at ON location_logs(sku_id, at);

CREATE TABLE daily_stats (
  stat_date    DATE        NOT NULL PRIMARY KEY,
  move_count   INT         NOT NULL DEFAULT 0,
  in_count     INT         NOT NULL DEFAULT 0,
  out_count    INT         NOT NULL DEFAULT 0,
  adjust_count INT         NOT NULL DEFAULT 0,
  audit_count  INT         NOT NULL DEFAULT 0,
  in_qty       INT         NOT NULL DEFAULT 0,
  out_qty      INT         NOT NULL DEFAULT 0,
  updated_at   DATETIME(6) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE audit_logs (
  id         VARCHAR(36)     NOT NULL PRIMARY KEY,
  module     VARCHAR(50)  NOT NULL,
  table_name VARCHAR(64),
  action     VARCHAR(20)  NOT NULL,
  row_id     VARCHAR(36),
  label      VARCHAR(255),
  name       VARCHAR(255) DEFAULT '',
  by_user_id VARCHAR(36),
  by_name    VARCHAR(255),
  at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_audit_at        ON audit_logs(at);
CREATE INDEX idx_audit_user_at   ON audit_logs(by_user_id, at);
CREATE INDEX idx_audit_module_at ON audit_logs(module, at);

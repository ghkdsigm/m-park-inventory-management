-- 위치기반 5뎁스 재구조화 (단지 > 위치코드 > 카테고리 > 상품넘버링 > SKU)
--  1) 재고행을 SKU당 1행으로 (멀티위치 폐기). SKU=단일 위치. 재고이동=위치값 변경.
--  2) 상품(넘버링)을 단지+위치코드에 귀속. 같은 실물이라도 단지/위치가 다르면 별도 상품·코드.
--  3) 재고이동을 stock_movements 에 'move' 타입으로 남길 수 있게 CHECK 확장.
-- 주의: 기존 데이터는 이미 SKU당 재고행 1개(398=398)라 별도 병합 불필요.
SET NAMES utf8mb4;

/* ---------- 1) 재고행 1:1화 ---------- */
-- 먼저 유니크를 추가(=FK 인덱스 요건 충족)한 뒤 기존 중복 인덱스 제거
ALTER TABLE stock ADD CONSTRAINT uq_stock_sku UNIQUE (sku_id);
ALTER TABLE stock DROP INDEX uq_stock_sku_loc;
ALTER TABLE stock DROP INDEX idx_stock_sku;

/* ---------- 2) 상품 위치 소속 (단지 + 위치코드) ---------- */
ALTER TABLE products
  ADD COLUMN complex_id            VARCHAR(36)  NULL         AFTER price,
  ADD COLUMN complex_name          VARCHAR(255) DEFAULT ''   AFTER complex_id,
  ADD COLUMN storage_location_id   VARCHAR(36)  NULL         AFTER complex_name,
  ADD COLUMN storage_location_code VARCHAR(50)  DEFAULT ''   AFTER storage_location_id,
  ADD COLUMN zone_id               VARCHAR(36)  NULL         AFTER storage_location_code,
  ADD COLUMN zone_name             VARCHAR(255) DEFAULT ''   AFTER zone_id,
  ADD COLUMN sub_zone_id           VARCHAR(36)  NULL         AFTER zone_name,
  ADD COLUMN sub_zone_name         VARCHAR(255) DEFAULT ''   AFTER sub_zone_id,
  ADD COLUMN location_label        VARCHAR(500) DEFAULT ''   AFTER sub_zone_name;

CREATE INDEX idx_products_complex  ON products(complex_id);
CREATE INDEX idx_products_location ON products(storage_location_id);

ALTER TABLE products
  ADD CONSTRAINT fk_prod_complex FOREIGN KEY (complex_id)          REFERENCES complexes(id)         ON DELETE SET NULL,
  ADD CONSTRAINT fk_prod_loc     FOREIGN KEY (storage_location_id) REFERENCES storage_locations(id) ON DELETE SET NULL;

/* ---------- 3) 재고이동(relocate) 원장 타입 ---------- */
ALTER TABLE stock_movements DROP CHECK chk_mv_type;
ALTER TABLE stock_movements ADD CONSTRAINT chk_mv_type CHECK (type IN ('in','out','adjust','audit','void','move'));

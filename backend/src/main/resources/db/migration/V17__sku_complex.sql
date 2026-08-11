-- SKU 단지 귀속: 같은 품목·규격이라도 단지별로 별도 SKU. skus 에 단지 컬럼 추가.
-- (실제 단지 설정/공유SKU 분리는 데이터 이관 스크립트에서 수행)
SET NAMES utf8mb4;

ALTER TABLE skus ADD COLUMN complex_id   VARCHAR(36)  NULL AFTER product_id;
ALTER TABLE skus ADD COLUMN complex_name VARCHAR(255) DEFAULT '' AFTER complex_id;

CREATE INDEX idx_skus_complex ON skus(complex_id);

-- 기존 상품의 위치 백필: 상품의 SKU들이 '단일 위치'에 있는 경우만 그 위치를 상품에 복사.
--  위치 정보는 이미 재고행(stock)과 SKU(complex_id)에 있으나, 상품(products)의 위치 컬럼은 V18에서 새로 생겨 비어 있음.
--  여러 단지/위치에 걸친 상품(옛 모델 잔재)은 상품=단일위치 원칙과 충돌하므로 건드리지 않고 NULL로 둔다(수동 정리 대상).
--  상품코드는 변경하지 않는다(기존 P-xxxxxx 코드에 QR/참조가 걸려 있으므로 불변).
SET NAMES utf8mb4;

UPDATE products p
JOIN (
  SELECT s.product_id,
         MIN(st.complex_id)            complex_id,
         MIN(st.complex_name)          complex_name,
         MIN(st.storage_location_id)   storage_location_id,
         MIN(st.storage_location_code) storage_location_code,
         MIN(st.zone_id)               zone_id,
         MIN(st.zone_name)             zone_name,
         MIN(st.sub_zone_id)           sub_zone_id,
         MIN(st.sub_zone_name)         sub_zone_name,
         MIN(st.location_label)        location_label
  FROM skus s
  JOIN stock st ON st.sku_id = s.id
  GROUP BY s.product_id
  HAVING COUNT(DISTINCT st.storage_location_id) = 1   -- 단일 위치 상품만
) x ON x.product_id = p.id
SET p.complex_id            = x.complex_id,
    p.complex_name          = x.complex_name,
    p.storage_location_id   = x.storage_location_id,
    p.storage_location_code = x.storage_location_code,
    p.zone_id               = x.zone_id,
    p.zone_name             = x.zone_name,
    p.sub_zone_id           = x.sub_zone_id,
    p.sub_zone_name         = x.sub_zone_name,
    p.location_label        = x.location_label
WHERE p.complex_id IS NULL OR p.complex_id = '';

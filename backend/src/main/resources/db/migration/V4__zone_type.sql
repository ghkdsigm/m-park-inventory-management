-- 구역/상세구역 용도 타입 — warehouse(재고창고) | usage(사용처) | common(공용)
--   입고는 warehouse+common, 출고 사용처는 usage+common 코드를 활용한다. 기존 데이터는 재고창고로 간주.
ALTER TABLE zones     ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'warehouse';
ALTER TABLE sub_zones ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'warehouse';

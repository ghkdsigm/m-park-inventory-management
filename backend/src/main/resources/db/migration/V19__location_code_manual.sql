-- 위치코드 수동화: 상품코드 형식 {단지코드}-{위치코드}-{순번} (예: TWR-A01-0001) 지원.
-- 위치코드(storage_locations.code)를 전역 유일 → 단지별 유일로 완화.
--  같은 짧은코드(A01 등)를 여러 단지가 각자 쓸 수 있어야 하기 때문.
SET NAMES utf8mb4;

ALTER TABLE storage_locations DROP INDEX code;
ALTER TABLE storage_locations ADD CONSTRAINT uq_loc_complex_code UNIQUE (complex_id, code);

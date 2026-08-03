-- 입고 실구매단가 — 원장(stock_movements)에 입고 시점 구매단가를 기록. (백오피스 입고에서만 입력)
-- NULL = 구매단가 미입력(모바일/기존 입고).
ALTER TABLE stock_movements
  ADD COLUMN unit_price DECIMAL(14,2) NULL DEFAULT NULL;

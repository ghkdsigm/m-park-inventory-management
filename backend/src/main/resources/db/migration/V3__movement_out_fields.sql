-- 출고 상세 항목 — 원장(stock_movements)에 사용처/요청부서/요청자/담당자 기록. (주로 출고에서 사용)
ALTER TABLE stock_movements
  ADD COLUMN usage_place  VARCHAR(255) NULL DEFAULT '',
  ADD COLUMN request_dept VARCHAR(255) NULL DEFAULT '',
  ADD COLUMN requester    VARCHAR(255) NULL DEFAULT '',
  ADD COLUMN handler      VARCHAR(255) NULL DEFAULT '';

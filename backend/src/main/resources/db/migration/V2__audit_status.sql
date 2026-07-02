-- 재고실사 상태/오차 스냅샷 — 재고행(stock)에 마지막 실사 결과와 정상처리 정보를 보관.
--   audit_status: NULL=미확정(한 번도 실사 안 함) | 'mismatch'=오차 미처리(비정상) | 'ok'=정상(오차 0 또는 사유 정상처리 완료)
ALTER TABLE stock
  ADD COLUMN last_audited_at      DATETIME(6)  NULL,
  ADD COLUMN last_audited_by      VARCHAR(255) NULL,
  ADD COLUMN last_audit_diff      INT          NULL,
  ADD COLUMN last_audit_counted   INT          NULL,
  ADD COLUMN audit_status         VARCHAR(20)  NULL,
  ADD COLUMN audit_resolved_at    DATETIME(6)  NULL,
  ADD COLUMN audit_resolved_by    VARCHAR(255) NULL,
  ADD COLUMN audit_resolve_reason VARCHAR(500) NULL DEFAULT '';

CREATE INDEX idx_stock_audit_status ON stock(audit_status);

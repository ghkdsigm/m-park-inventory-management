-- 감사로그 확장 — 변경 전/후 값 + 접속 IP 기록.
ALTER TABLE audit_logs
  ADD COLUMN before_value VARCHAR(1000) NULL,
  ADD COLUMN after_value  VARCHAR(1000) NULL,
  ADD COLUMN ip           VARCHAR(64)   NULL;

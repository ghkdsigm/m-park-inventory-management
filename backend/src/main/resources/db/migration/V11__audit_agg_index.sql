-- 감사로그 집계(topUsers 등)용 인덱스 — action + 날짜범위 조회를 위해.
CREATE INDEX idx_audit_action_at ON audit_logs(action, at);

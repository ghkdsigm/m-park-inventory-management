-- 멱등 처리 키 — 입/출고·조정·이동의 중복 요청(재전송/더블클릭/타임아웃 재시도) 1회만 반영.
--   같은 request_id 로 다시 오면 저장된 결과를 그대로 돌려주거나(입/출/조정) 거부(이동)한다.
CREATE TABLE idempotency_keys (
  request_id VARCHAR(64)  NOT NULL PRIMARY KEY,
  before_qty INT          NULL,
  after_qty  INT          NULL,
  delta      INT          NULL,
  created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

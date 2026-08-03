/* ========================= AI 토큰 사용량 (계정별 모니터링) ========================= */
CREATE TABLE ai_usage (
  id                VARCHAR(36)  NOT NULL PRIMARY KEY,
  user_id           VARCHAR(36)  NULL,
  user_name         VARCHAR(255) DEFAULT '',
  feature           VARCHAR(30)  NOT NULL,   -- chat | find_similar | quote_extract | tts
  model             VARCHAR(60)  DEFAULT '',
  prompt_tokens     INT          NOT NULL DEFAULT 0,
  completion_tokens INT          NOT NULL DEFAULT 0,
  total_tokens      INT          NOT NULL DEFAULT 0,
  char_count        INT          NOT NULL DEFAULT 0,   -- TTS 등 문자 기반 사용량
  created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_ai_usage_user_at ON ai_usage(user_id, created_at);
CREATE INDEX idx_ai_usage_at ON ai_usage(created_at);

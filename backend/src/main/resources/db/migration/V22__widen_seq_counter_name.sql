-- 상품 채번 카운터 키가 products:{단지uuid}:{카테고리uuid} (약 82자)라 기존 VARCHAR(50)을 초과.
-- seq_counters.name(PK) 을 넓힌다.
SET NAMES utf8mb4;

ALTER TABLE seq_counters MODIFY COLUMN name VARCHAR(120) NOT NULL;

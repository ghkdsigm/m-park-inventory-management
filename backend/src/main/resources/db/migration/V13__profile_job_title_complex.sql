-- 프로필 확장: 직급(job_title) + 관리단지(managed_complex) 컬럼 추가, 매니저 계정 4명 시드
--  · position 은 MySQL 예약어라 컬럼명은 job_title 로 둔다(직급).
--  · 비번은 API 의 6자 제한을 우회하기 위해 여기서 BCrypt($2a$10) 해시로 직접 시드한다.
SET NAMES utf8mb4;

/* ========== 1) 컬럼 추가 ========== */
ALTER TABLE profiles ADD COLUMN job_title       VARCHAR(50)  DEFAULT '' AFTER display_name;
ALTER TABLE profiles ADD COLUMN managed_complex VARCHAR(255) DEFAULT '' AFTER job_title;

/* ========== 2) 매니저 계정 시드 (아이디 중복 시 건너뜀) ========== */
-- 비번 원문: sm34=4748, byahn3=3028, metusv1=3567, naju63=5038
INSERT IGNORE INTO profiles (id, username, email, password_hash, display_name, job_title, managed_complex, role, can_stock)
VALUES
  (UUID(), 'sm34',    NULL, '$2a$10$Sjxvu0dU.n2u4F1KS0Jq0.6V7DIR77sYoL5ihYF1zBY5O8EBvG6qq', '전재민', '소장',   '랜드', 'manager', 1),
  (UUID(), 'byahn3',  NULL, '$2a$10$AZoYmS/lQIB0kNOT9rZJDu8hnQ5WkSDj2A6KZuLGsBFZHyElYszlO', '안형룡', '소장',   '허브', 'manager', 1),
  (UUID(), 'metusv1', NULL, '$2a$10$MqNqFsAanut8D5NnHcQyO.wYa19XDXTxcbAlelHp9vPKwRjLA70CK', '박성은', '시설과장', '허브', 'manager', 1),
  (UUID(), 'naju63',  NULL, '$2a$10$ztlkwAkx0KpJU9vU9Y0ri.LfrgZV2Y/MDz/3.RReD6fMwVHpyH1W.', '고재열', '미화실장', '허브', 'manager', 1);
